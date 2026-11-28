import io
import re
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / "tools/linuxfs/overlay/usr/local/bin"
KOTLIN = (ROOT / "app/src/main/java/com/droiddeck/launcher/session/BootLine.kt").read_text()
NATIVE_GODOT = (BIN / "droiddeck-native-godot").read_text()


def kotlin_const(name):
    return re.search(r'const val %s = "([^"]+)"' % name, KOTLIN).group(1)


def kotlin_parse(line):
    """BootLine.parse, line for line: (path, build, skipped) or None."""
    text = line.strip()
    marker, skipped_sep = kotlin_const("MARKER"), kotlin_const("SKIPPED")
    if not text.startswith(marker):
        return None
    rest = text[len(marker):]
    head = rest.split(skipped_sep, 1)[0].strip()
    skipped = rest.split(skipped_sep, 1)[1].strip() if skipped_sep in rest else None
    path = {"native": "NATIVE", "proton": "PROTON"}.get(head.split(" ", 1)[0])
    if path is None:
        return None
    return path, (head.split(" ", 1)[1].strip() if " " in head else "") or "unknown", skipped or None


class BootLineMarkerTest(unittest.TestCase):
    """droiddeck-game-env's engine line is read by the app (session/BootLine.kt) from session.log: it must print exactly
    what the app parses."""

    def setUp(self):
        self.env = runpy.run_path(str(BIN / "droiddeck-game-env"))

    def test_the_marker_and_separator_match_the_app(self):
        self.assertEqual(kotlin_const("MARKER"), self.env["ENGINE_LINE"])
        self.assertIn(kotlin_const("SKIPPED"), self.env["engine_line"]("proton", "FEX-x", "why"))

    def test_each_line_the_script_prints_parses_as_the_app_expects(self):
        line = self.env["engine_line"]
        self.assertEqual(("NATIVE", "native-engine-54", None), kotlin_parse(line("native", "native-engine-54")))
        self.assertEqual(("PROTON", "FEX-Kestrel-2510-p9", "Steam library gap"),
                         kotlin_parse(line("proton", "FEX-Kestrel-2510-p9", "Steam library gap")))
        self.assertEqual(("PROTON", "unknown", None), kotlin_parse(line("proton", None)))
        self.assertEqual(("PROTON", "FEX-with-space", None), kotlin_parse(line("proton", " FEX with space ")))

    def test_the_fex_version_name_comes_from_the_fex_build_line(self):
        name = self.env["fex_name"]
        self.assertEqual("FEX-Kestrel-2510-p9", name("fex: FEX-Kestrel-2510-p9 123 bytes sha256 abcdef012345"))
        self.assertIsNone(name("fex: (no version name) 123 bytes sha256 abcdef012345"))
        self.assertIsNone(name("fex: no files/lib/wine/aarch64-windows/libarm64ecfex.dll"))
        self.assertIsNone(name(None))

    def test_why_native_was_skipped_in_a_few_words(self):
        why = self.env["native_skip_reason"]
        self.assertIsNone(why([]))
        self.assertEqual("last check failed", why(
            ["native-engine: skipped, the last check of these game files failed (compose stopped); Proton"]))
        self.assertEqual("not a Godot C# game", why(["native-engine: not a Godot C# game; Proton"]))
        self.assertEqual("could not start", why(["native-engine: could not start (ENOENT); Proton"]))
        self.assertEqual("exited with 3", why(["native-engine: exited with 3 after 5 s; starting on Proton"]))
        self.assertEqual("no first frame", why(["native-engine: no first frame after 90 s; starting on Proton"]))

    def test_a_steam_library_gap_is_named_from_the_wrappers_own_words(self):
        # The wrapper's log line, as droiddeck-native-godot writes it, reaches the notes through native-engine.log.
        gap = self.env["NATIVE_STEAM_GAP"]
        self.assertIn('log(f"%s' % gap, NATIVE_GODOT)
        notes = ["native-engine log: droiddeck-native-godot: %s with this game's Steamworks.NET: the library lacks "
                 "SteamAPI_SteamGameSearch_v001" % gap,
                 "native-engine: no first frame after 2 s; starting on Proton"]
        self.assertEqual("Steam library gap", self.env["native_skip_reason"](notes))


    def test_a_failed_steam_init_and_an_unserved_interface_are_named(self):
        for const, reason in (("NATIVE_STEAM_INIT", "Steam init failed"), ("NATIVE_STEAM_UNSERVED", "Steam interface missing"),
                              ("NATIVE_GAME_FAILED", "game startup failed")):
            words = self.env[const]
            self.assertIn(words, NATIVE_GODOT)
            notes = ["native-engine log: droiddeck-native-godot: %s (...); Proton" % words,
                     "native-engine: no first frame after 2 s; starting on Proton"]
            self.assertEqual(reason, self.env["native_skip_reason"](notes))

class NativeEngineMarkerTest(unittest.TestCase):
    """native_engine says which component it starts before it runs it (the run blocks until the game ends)."""

    def test_native_engine_prints_its_line_with_the_component_tag_before_the_run(self):
        env = runpy.run_path(str(BIN / "droiddeck-game-env"))
        with tempfile.TemporaryDirectory() as tmp:
            game = Path(tmp) / "Game"
            (game / "data_game_windows_x86_64").mkdir(parents=True)
            seen = []

            class Done:
                returncode = 0

            err = io.StringIO()

            def run(args, **kw):
                seen.append(err.getvalue())
                return Done()
            ng = {"fresh_check": lambda folder, name=None: {"result": "pass"}, "CHECK_DIR": tmp,
                  "COMPONENT": {"tag": "native-engine-54"}}
            times = iter([0.0, 500.0])
            with mock.patch("sys.stderr", err):
                rc, _ = env["native_engine"](["/proton", "waitforexitandrun", str(game / "Game.exe")],
                                             {"DROIDDECK_EXP_NATIVE_ENGINE": "1"}, run=run, clock=lambda: next(times),
                                             popen=lambda *a, **k: None, ng=ng)
        self.assertEqual(0, rc)
        self.assertEqual(("NATIVE", "native-engine-54", None), kotlin_parse(seen[0].strip().splitlines()[-1]))


class ProtonMarkerTest(unittest.TestCase):
    """start() says Proton started, with the FEX version name and why native was skipped, for a game launch only."""

    def start(self, timeline_app_id, native_skipped=None):
        """start() for a plain launch, which ends in exec (stopped here), with record_final_env's FEX line given."""
        env = runpy.run_path(str(BIN / "droiddeck-game-env"))
        g = env["start"].__globals__
        g.update({"record_final_env": lambda *a: "fex: FEX-Kestrel-2510-p9 10 bytes sha256 0123456789ab",
                  "system_graphics_launch": lambda launch, launch_env: (launch, launch_env),
                  "ubisoft_composition_off": lambda *a: None})
        err = io.StringIO()
        with mock.patch("sys.stderr", err), mock.patch("os.execvpe", side_effect=SystemExit(0)):
            with self.assertRaises(SystemExit):
                env["start"](["/proton", "waitforexitandrun", "/game.exe"], {}, None, None, None, timeline_app_id,
                             False, None, native_skipped)
        return [l for l in err.getvalue().splitlines() if l.startswith(env["ENGINE_LINE"])]

    def test_a_game_launch_names_proton_and_its_fex(self):
        self.assertEqual([("PROTON", "FEX-Kestrel-2510-p9", "Steam library gap")],
                         [kotlin_parse(l) for l in self.start("2868840", "Steam library gap")])

    def test_other_proton_runs_say_nothing(self):
        self.assertEqual([], self.start(None))


if __name__ == "__main__":
    unittest.main()


class NativePrestartTest(unittest.TestCase):
    """droiddeck-game-env --native-prestart starts droiddeck-native-godot prestart with the arguments native_engine()
    gives run, so run can take the engine over."""

    def call(self, env, check="pass"):
        ge = runpy.run_path(str(BIN / "droiddeck-game-env"))
        with tempfile.TemporaryDirectory() as tmp:
            game = Path(tmp) / "Game"
            (game / "data_game_windows_x86_64").mkdir(parents=True)
            seen = []

            class Done:
                returncode = 0

            def run(args, **kw):
                seen.append(args)
                return Done()
            ng = {"fresh_check": lambda folder, name=None: {"result": check}, "CHECK_DIR": tmp}
            with mock.patch("sys.stderr", io.StringIO()):
                rc = ge["native_prestart"](["", "waitforexitandrun", str(game / "Game.exe"), "-x"], env, run=run, ng=ng)
            started = []

            def run_native(args, **kw):
                started.append(args)
                return Done()
            with mock.patch("sys.stderr", io.StringIO()):
                ge["native_engine"](["/proton", "waitforexitandrun", str(game / "Game.exe"), "-x"], env, run=run_native,
                                    clock=iter([0.0, 500.0]).__next__, popen=lambda *a, **k: None, ng=ng)
        return rc, seen, started

    def test_prestart_gets_runs_arguments(self):
        rc, seen, started = self.call({"DROIDDECK_EXP_NATIVE_ENGINE": "1", "STEAM_COMPAT_DATA_PATH": "/c/2868840"})
        self.assertEqual(0, rc)
        self.assertEqual("prestart", seen[0][2])
        self.assertEqual("run", started[0][2])
        self.assertEqual(seen[0][3:], started[0][3:])

    def test_nothing_starts_with_native_engine_off_or_a_failed_check(self):
        self.assertEqual([], self.call({"STEAM_COMPAT_DATA_PATH": "/c/1"})[1])
        self.assertEqual([], self.call({"DROIDDECK_EXP_NATIVE_ENGINE": "1"}, check="fail")[1])
