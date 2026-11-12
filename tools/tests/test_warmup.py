import contextlib
import io
import json
import os
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
WARMUP = runpy.run_path(str(BIN / "droiddeck-warmup"))
STS2 = "2868840"


def quiet(function, *args):
    err = io.StringIO()
    with contextlib.redirect_stderr(err):
        result = function(*args)
    return result, err.getvalue()


class Proton:
    """A Proton folder with an ARM64EC FEX library carrying a version string, and a prefix next to it."""

    def __init__(self, root, version=b"FEX-2610"):
        self.root = Path(root)
        self.proton = self.root / "tool/proton"
        dll = self.root / "tool" / WARMUP["FEX_DLL"]
        dll.parent.mkdir(parents=True)
        dll.write_bytes(b"MZ\0\0" + b"\0" * 64 + version + b"\0more")
        self.proton.write_text("")
        self.prefix = self.root / "compatdata" / STS2
        (self.prefix / "pfx/dosdevices").mkdir(parents=True)
        os.symlink("/", self.prefix / "pfx/dosdevices/z:")


class DotnetProfileTest(unittest.TestCase):
    def test_only_coreclr_games_get_a_profile(self):
        self.assertEqual(({}, None), WARMUP["dotnet_profile"]({"game.exe"}, {"DROIDDECK_DOTNET_WARMUP": "lean"}))

    def test_off_and_unset_keep_the_runtime_defaults(self):
        for value in (None, "", "off", "0"):
            env = {} if value is None else {"DROIDDECK_DOTNET_WARMUP": value}
            self.assertEqual(({}, None), WARMUP["dotnet_profile"]({"coreclr.dll"}, env))

    def test_each_profile_sets_its_variables(self):
        chosen, note = WARMUP["dotnet_profile"]({"coreclr.dll"}, {"DROIDDECK_DOTNET_WARMUP": "lean"})
        self.assertEqual({"DOTNET_TieredPGO": "0", "DOTNET_TC_QuickJitForLoops": "0",
                          "DOTNET_TC_CallCountingDelayMs": "0"}, chosen)
        self.assertIn(".NET profile lean: DOTNET_TC_CallCountingDelayMs=0", note)
        chosen, _ = WARMUP["dotnet_profile"]({"coreclr.dll"}, {"DROIDDECK_DOTNET_WARMUP": "Single-Tier"})
        self.assertEqual({"DOTNET_TieredCompilation": "0"}, chosen)

    def test_a_variable_already_set_keeps_its_value_in_either_spelling(self):
        env = {"DROIDDECK_DOTNET_WARMUP": "lean", "DOTNET_TieredPGO": "1", "COMPlus_TC_QuickJitForLoops": "1"}
        chosen, note = WARMUP["dotnet_profile"]({"coreclr.dll"}, env)
        self.assertEqual({"DOTNET_TC_CallCountingDelayMs": "0"}, chosen)
        self.assertIn("already set, kept: DOTNET_TC_QuickJitForLoops DOTNET_TieredPGO", note)

    def test_an_unknown_profile_changes_nothing_and_says_so(self):
        chosen, note = WARMUP["dotnet_profile"]({"coreclr.dll"}, {"DROIDDECK_DOTNET_WARMUP": "fast"})
        self.assertEqual({}, chosen)
        self.assertIn("'fast' unknown", note)


class FexDiskCacheTest(unittest.TestCase):
    def test_on_by_default_for_slay_the_spire_2_with_a_folder_in_the_prefix(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            chosen, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton), {})
            folder = tool.prefix / "droiddeck-fex-cache"
            self.assertTrue(folder.is_dir())
            self.assertEqual({"FEX_DISKCACHE": "1",
                              "FEX_APP_CACHE_LOCATION": "Z:" + str(folder).replace("/", "\\") + "\\"}, chosen)
            self.assertEqual(["FEX disk cache on for app %s (FEX-2610, no cache yet, folder %s)" % (STS2, folder)], notes)

    def test_the_cache_size_is_reported_on_the_next_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            blobs = tool.prefix / "droiddeck-fex-cache/DiskCache/0123456789abcdef"
            blobs.mkdir(parents=True)
            (blobs / "RWCacheDB_1.foz").write_bytes(b"x" * 2097152)
            (blobs / "RWCacheDB_1.idx").write_bytes(b"x" * 1024)
            _, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton), {})
            self.assertIn("(FEX-2610, 2 files, 2.0 MB, folder", notes[0])

    def test_other_games_only_on_request(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            self.assertEqual(({}, []), WARMUP["fex_disk_cache"]("620", str(tool.prefix), str(tool.proton), {}))
            chosen, _ = WARMUP["fex_disk_cache"]("620", str(tool.prefix), str(tool.proton), {"DROIDDECK_FEX_DISKCACHE": "1"})
            self.assertEqual("1", chosen["FEX_DISKCACHE"])

    def test_the_setting_turns_it_off(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            chosen, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton), {"DROIDDECK_FEX_DISKCACHE": "0"})
            self.assertEqual({}, chosen)
            self.assertIn("off for app 2868840", notes[0])
            self.assertFalse((tool.prefix / "droiddeck-fex-cache").exists())

    def test_a_fex_older_than_the_cache_gets_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp, b"FEX-2604-12-gabcdef12")
            chosen, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton), {})
            self.assertEqual({}, chosen)
            self.assertIn("FEX-2604-12-gabcdef12, the cache came in FEX-2609", notes[0])

    def test_an_unknown_fex_version_still_gets_the_setting(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp, b"no version")
            chosen, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton), {})
            self.assertEqual("1", chosen["FEX_DISKCACHE"])
            self.assertIn("FEX version not found", notes[0])

    def test_the_players_own_fex_settings_win(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            chosen, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton), {"FEX_DISKCACHE": "0"})
            self.assertEqual({}, chosen)
            self.assertIn("FEX_DISKCACHE=0", notes[0])
            chosen, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton),
                                                     {"FEX_APP_CACHE_LOCATION": "C:\\fex\\"})
            self.assertEqual({"FEX_DISKCACHE": "1"}, chosen)
            self.assertIn("FEX_APP_CACHE_LOCATION=C:\\fex\\", notes[1])

    def test_no_folder_when_z_is_not_the_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            link = tool.prefix / "pfx/dosdevices/z:"
            link.unlink()
            os.symlink("/home", link)
            chosen, notes = WARMUP["fex_disk_cache"](STS2, str(tool.prefix), str(tool.proton), {})
            self.assertEqual({"FEX_DISKCACHE": "1"}, chosen)
            self.assertIn("Z: drive is not /", notes[1])

    def test_a_new_prefix_gets_the_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            fresh = Path(tmp) / "compatdata/new"
            fresh.mkdir()
            chosen, _ = WARMUP["fex_disk_cache"](STS2, str(fresh), str(tool.proton), {})
            self.assertIn("FEX_APP_CACHE_LOCATION", chosen)


class PrefixAndGodotTest(unittest.TestCase):
    def test_a_wineboot_update_is_reported_only_when_wine_will_run_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            inf = tool.proton.parent / "files/share/wine/wine.inf"
            inf.parent.mkdir(parents=True)
            inf.write_text("")
            os.utime(inf, (1700000000, 1700000000))
            stamp = tool.prefix / "pfx/.update-timestamp"
            self.assertIsNone(WARMUP["wine_update_pending"](str(tool.prefix), str(tool.proton)))
            for recorded, expected in (("1700000000", None), ("disable", None), ("1600000000", "1600000000")):
                stamp.write_text(recorded + "\n")
                line = WARMUP["wine_update_pending"](str(tool.prefix), str(tool.proton))
                if expected is None:
                    self.assertIsNone(line)
                else:
                    self.assertEqual("Wine will update this prefix (wineboot) before the game: "
                                     ".update-timestamp 1600000000, wine.inf 1700000000", line)

    def test_godot_caches_are_summarised(self):
        with tempfile.TemporaryDirectory() as tmp:
            roaming = Path(tmp) / "pfx/drive_c/users/steamuser/AppData/Roaming"
            self.assertIsNone(WARMUP["godot_caches"](tmp))
            (roaming / "SlayTheSpire2/vulkan").mkdir(parents=True)
            (roaming / "SlayTheSpire2/vulkan/pipelines.abc.cache").write_bytes(b"x" * 4096)
            (roaming / "SlayTheSpire2/shader_cache/CanvasShaderRD").mkdir(parents=True)
            (roaming / "SlayTheSpire2/shader_cache/CanvasShaderRD/a.cache").write_bytes(b"x")
            self.assertEqual("Godot caches: pipelines SlayTheSpire2/vulkan/pipelines.abc.cache 4 KB; "
                             "shaders SlayTheSpire2/shader_cache 1 file, 0.0 MB", WARMUP["godot_caches"](tmp))


class TimingTest(unittest.TestCase):
    def test_process_age_comes_from_proc(self):
        with tempfile.TemporaryDirectory() as tmp:
            stat = Path(tmp) / "stat"
            ticks = os.sysconf("SC_CLK_TCK")
            fields = ["S"] + ["0"] * 18 + [str(100 * ticks)] + ["0"] * 10
            stat.write_text("123 (python3 (x)) " + " ".join(fields))
            uptime = Path(tmp) / "uptime"
            uptime.write_text("101.25 50.00\n")
            self.assertEqual(1250, WARMUP["process_age_ms"](str(stat), str(uptime)))
            self.assertIsNone(WARMUP["process_age_ms"](str(Path(tmp) / "none"), str(uptime)))
        self.assertIsInstance(WARMUP["process_age_ms"](), int)

    def test_the_prep_line_names_each_phase(self):
        timer = WARMUP["Timer"]()
        timer.phases = [("components", 12), ("prefix DLL check", 40)]
        _, err = quiet(timer.report, STS2, 900)
        self.assertEqual("droiddeck-game-env: warmup: launch prep for app 2868840: 900 ms before Proton starts "
                         "(components 12 ms, prefix DLL check 40 ms)\n", err)


class GameEnvironmentWarmupTest(unittest.TestCase):
    def test_slay_the_spire_2_starts_with_the_cache_and_the_chosen_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Proton(tmp)
            probe = tool.proton
            probe.write_text("#!" + sys.executable + "\nimport os\nprint(' '.join(sorted(k + '=' + v for k, v in os.environ.items()"
                             " if k.startswith(('FEX_DISKCACHE', 'FEX_APP_CACHE', 'DOTNET_T')))))\n")
            probe.chmod(0o755)
            game = Path(tmp) / "common/Slay the Spire 2/SlayTheSpire2.exe"
            (game.parent / "data_sts2_windows_x86_64").mkdir(parents=True)
            (game.parent / "data_sts2_windows_x86_64/coreclr.dll").write_bytes(b"MZ")
            (game.parent / "SlayTheSpire2.pck").write_bytes(b"GDPC")
            game.write_bytes(b"MZ")
            config = Path(tmp) / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text(json.dumps({"version": 1, "games": {STS2: {"DROIDDECK_DOTNET_WARMUP": "pgo-off"}}}))
            env = {k: v for k, v in os.environ.items() if not k.startswith(("FEX_", "DOTNET_", "COMPlus_", "DROIDDECK_"))}
            env.update(HOME=tmp, STEAM_COMPAT_DATA_PATH=str(tool.prefix))
            off = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", str(game)],
                                 env=env, text=True, capture_output=True, check=True)
            self.assertEqual(off.stdout.strip(), "", off.stderr)
            self.assertNotIn("warmup:", off.stderr)
            config.write_text(json.dumps({"version": 1, "shared": {"DROIDDECK_EXP_WARMUP": "1"},
                                          "games": {STS2: {"DROIDDECK_DOTNET_WARMUP": "pgo-off"}}}))
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", str(game)],
                                    env=env, text=True, capture_output=True, check=True)
            folder = "Z:" + str(tool.prefix / "droiddeck-fex-cache").replace("/", "\\") + "\\"
            self.assertEqual("DOTNET_TieredPGO=0 FEX_APP_CACHE_LOCATION=%s FEX_DISKCACHE=1" % folder, result.stdout.strip(), result.stderr)
            self.assertIn("droiddeck-game-env: warmup: .NET profile pgo-off: DOTNET_TieredPGO=0\n", result.stderr)
            self.assertIn("droiddeck-game-env: warmup: FEX disk cache on for app 2868840 (FEX-2610", result.stderr)
            self.assertRegex(result.stderr, r"droiddeck-game-env: warmup: launch prep for app 2868840: \d+ ms before Proton starts "
                                            r"\(components \d+ ms, prefix DLL check \d+ ms, warmup \d+ ms\)")

    def test_other_launches_are_left_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            probe = Path(tmp) / "probe"
            probe.write_text("#!" + sys.executable + "\nimport os\nprint(os.environ.get('FEX_DISKCACHE', 'unset'))\n")
            probe.chmod(0o755)
            env = {k: v for k, v in os.environ.items() if not k.startswith(("FEX_", "DROIDDECK_"))}
            env.update(HOME=tmp, STEAM_COMPAT_DATA_PATH=tmp + "/compatdata/" + STS2)
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "run", "x.exe"],
                                    env=env, text=True, capture_output=True, check=True)
            self.assertEqual("unset", result.stdout.strip())
            self.assertNotIn("warmup:", result.stderr)


if __name__ == "__main__":
    unittest.main()
