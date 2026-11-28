"""tools/bench/run.py's screen and confirm modes against a scripted stand-in for the phone: no adb."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import sys
import tempfile
import types
import unittest

BENCH = Path(__file__).resolve().parents[1] / "bench"
sys.path.insert(0, str(BENCH))
import benchlib

SCRIPT = json.loads((BENCH / "scenarios" / "fight.json").read_text())
INBOX = "/storage/emulated/0/Android/data/pkg/files/agent-inbox"


class Clock:
    def __init__(self):
        self.now = 1_800_000_000.0

    def time(self):
        return self.now

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += max(0.0, seconds)


def load_run(out: Path, clock: Clock):
    os.environ["BENCH_OUT"] = str(out)
    spec = importlib.util.spec_from_file_location("bench_run_under_test", BENCH / "run.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.time = types.SimpleNamespace(time=clock.time, sleep=clock.sleep, monotonic=clock.monotonic)
    return module


class Shot:
    def __init__(self, phone):
        self.phone = phone
        self.state = phone.screen

    def png(self): return b""
    def offline_crop(self): return self
    def steam_offline_icon(self): return 145 if self.state == "steam" and self.phone.offline_icon else 0
    def early_access_prompt(self): return False
    def dimmed(self): return self.state == "quit-confirm"
    def main_menu(self): return self.state == "menu"
    def profile_icon(self): return self.phone.profile
    def menu_focus_y(self): return 734 + 40 * self.phone.focus if self.state == "menu" and self.phone.focus is not None else None
    def hand_glow(self): return 0.02 if self.state == "fight" else 0.0
    def enemy_hp(self): return self.phone.hp if self.state == "fight" else None


class Phone:
    """The parts of the phone, the app and the game the harness talks to."""

    def __init__(self, clock: Clock):
        self.clock = clock
        self.phase, self.session, self.sessions = "IDLE", None, 0
        self.game, self.launches, self.screen = False, 0, "lanner"
        self.focus, self.hp, self.step, self.paused_downs = None, None, 0, 0
        self.cards, self.moves = 0, 0
        self.profile, self.offline_icon, self.offline_pref = "blue", True, True
        self.bridge, self.venus = True, False
        self.experiments = {name: "Off" for name in benchlib.EXPERIMENT_LEVEL}
        self.experiments.update({"native-engine": "On", "bridge-default": "On"})
        self.session_experiments: dict = {}
        self.pending_env, self.active_env = [], []
        self.files = {"/opt/engine/libgodot.so": b"original"}
        self.inbox: dict = {}
        self.restore_on_continue = True
        self.settings_with_session = True
        self.calls: list[tuple] = []
        self.frames_t0 = None

    def ms(self) -> int:
        return int(self.clock.now * 1000)

    def state(self) -> dict:
        focus = {"focusedApp": 2868840 if self.game else 769, "focusableApps": [769, 2868840] if self.game else [769]}
        return {"ok": True, "build": "test", "apkCommit": "abc1234", "appVersion": "1", "runtime": {"version": "1"},
                "session": {"phase": self.phase, "id": self.session, "mode": "steam" if self.session else None,
                            "running": self.phase == "READY", "focus": focus if self.phase == "READY" else None}}

    def nodes(self) -> list[dict]:
        nodes = [{"tag": "setting-offline", "state": "On" if self.offline_pref else "Off"},
                 {"tag": "setting-boot-movie-sound", "state": "Off"}, {"tag": "setting-game-sound", "state": "Off"},
                 {"text": f"Mali bridge: {'on' if self.bridge else 'off'}"}, {"text": f"Mali (Venus): {'on' if self.venus else 'off'}"},
                 {"text": "Safe mode: on"}]
        return nodes + [{"tag": f"setting-exp-{name}", "state": state} for name, state in self.experiments.items()]

    def ctl(self, *command):
        self.calls.append(command)
        self.clock.now += 0.05
        head = command[0]
        if head == "state":
            return self.state()
        if head == "access":
            return {"ok": True, "commands": True, "inbox": INBOX}
        if head == "ui":
            if self.phase != "IDLE" and not self.settings_with_session:
                return {"ok": False, "error": "no such node"}
            if command[1] == "click":
                target = command[-1] if command[2] != "--match" else command[3]
                if target.startswith("setting-exp-"):
                    name = target[len("setting-exp-"):]
                    self.experiments[name] = "Off" if self.experiments[name] == "On" else "On"
                elif target.startswith("Mali bridge"):
                    self.bridge = not self.bridge
                elif target.startswith("Mali (Venus)"):
                    self.venus = not self.venus
                return {"ok": True}
            return {"ok": True, "windows": [{"nodes": self.nodes()}]}
        if head == "prefs":
            return {"ok": True, "values": {"steam_offline": self.offline_pref, "gameSound": False, "bootMovieSound": False,
                                           "maliRouteBy": "auto"}}
        if head == "env":
            if command[1] == "set":
                self.pending_env.append(command[2])
            elif command[1] == "clear":
                self.pending_env = []
            return {"ok": True, "pending": list(self.pending_env), "persistent": [], "active": list(self.active_env)}
        if head == "thermal":
            return {"ok": True, "batteryC": 30.0, "status": 0, "cpu": {}}
        if head == "start":
            assert self.phase == "IDLE"
            self.sessions += 1
            self.phase, self.session, self.screen = "READY", f"s{self.sessions}", "steam"
            self.session_experiments = dict(self.experiments)
            self.active_env, self.pending_env = self.pending_env, []
            self.frames_t0 = self.ms()
            return {"ok": True, "session": {"id": self.session}}
        if head == "launch":
            assert self.phase == "READY" and not self.game
            self.game, self.screen, self.focus = True, "menu", None
            self.launches += 1
            self.cards = self.moves = 0
            return {"ok": True}
        if head == "quit":
            self.game, self.screen = False, "steam"
            return {"ok": True}
        if head == "stop":
            self.phase, self.game, self.screen, self.active_env = "IDLE", False, "lanner", []
            return {"ok": True}
        if head in ("wait", "resume", "input"):
            return {"ok": True}
        if head == "logs":
            self.write_logs(Path(command[2]))
            return {"ok": True}
        if head in ("guest", "native"):
            return self.shell(command[-1])
        raise AssertionError(f"unexpected droiddeckctl {command}")

    def shell(self, script: str) -> dict:
        ok = lambda text: {"ok": True, "exitCode": 0, "stdout": {"text": text}, "stderr": {"text": ""}}
        if "frame split" in script:
            return ok(f"{self.ms() // 10000 % 1000}\n{self.cards}\n{self.moves}\n")
        target = script.split("T=")[1].split(";")[0]
        digest = lambda data: hashlib.sha256(data).hexdigest()
        if "bench-new" in script:
            staged = script.split("S=")[1].split(";")[0]
            if target not in self.files or target + ".bench-orig" in self.files:
                return {"ok": True, "exitCode": 1, "stdout": {"text": ""}, "stderr": {"text": "test failed"}}
            assert not self.game, "a file was replaced under a running game"
            self.files[target + ".bench-orig"] = self.files[target]
            self.files[target] = self.inbox.pop(staged)
            return ok(f"{digest(self.files[target + '.bench-orig'])}  {target}.bench-orig\n{digest(self.files[target])}  {target}\n")
        assert not self.game, "a file was restored under a running game"
        self.files[target] = self.files.pop(target + ".bench-orig")
        return ok(f"{digest(self.files[target])}  {target}\n")

    def sh(self, script: str) -> str:
        self.clock.now += 0.01
        if script.startswith("date +%s%N"):
            return str(int(self.clock.now * 1e9))
        if script.startswith("date '+%F %T'"):
            return "2027-01-15 08:00:00"
        if "topResumedActivity" in script:
            return "topResumedActivity=ActivityRecord{chat.comb.lanner/.Main}"
        if "powered: true" in script:
            return "1"
        if "--get" in script:
            return "volume is 0"
        if "/proc/meminfo" in script:
            return "MemAvailable:    4096000 kB\nSwapFree: 1000 kB\n"
        return ""

    def pads(self, buttons: list[str]) -> None:
        was = self.screen
        for button in buttons:
            if self.screen == "menu":
                if button in ("down", "up"):
                    self.focus = 0 if self.focus is None else max(0, min(5, self.focus + (1 if button == "down" else -1)))
                elif button == "a" and self.focus == 0:
                    self.screen, self.step = "fight", 0
                    if self.restore_on_continue or self.hp is None:
                        self.hp = 1.0
                elif button == "a" and self.focus == 5:
                    self.screen = "quit-confirm"
            elif self.screen == "quit-confirm" and button == "a":
                self.game, self.screen = False, "steam"
            elif self.screen == "paused":
                if button == "down":
                    self.paused_downs += 1
                elif button == "a" and self.paused_downs == 3:
                    self.screen, self.focus = "menu", None
            elif self.screen == "fight" and button == "start":
                self.screen, self.paused_downs = "paused", 0
        if was == "fight" and self.screen == "fight" and self.step < len(SCRIPT["steps"]):
            step = SCRIPT["steps"][self.step]
            assert step["pads"] == buttons, (self.step, buttons)
            self.hp = step.get("expect_enemy_hp", self.hp)
            self.cards += step["kind"] == "card"
            self.moves += step["kind"] == "end_turn"
            self.step += 1

    def write_logs(self, folder: Path) -> None:
        folder.mkdir(parents=True)
        rows = "".join(f"{at},16000\n" for at in range(16, self.ms() - self.frames_t0, 16))
        (folder / "frames.csv").write_text(f"# frames t0_wall_ms={self.frames_t0} t0_mono_ns=1 source=gamescope\nt_ms,frametime_us\n{rows}")
        (folder / "events.jsonl").write_text(json.dumps({"t": self.frames_t0, "event": "game.engine", "path": "native", "build": "e"}) + "\n")
        (folder / "device.txt").write_text(f"steam_offline={'true' if self.offline_pref else 'false'}\n")
        (folder / "steam.txt").write_text("[2027-01-15 07:59:00] [ None ] Start offline - 1\n[2027-01-15 08:00:05] User is offline\n")
        (folder / "native-engine.log").write_text(
            "native_compat: frame split 10.0s 600 frames 60.0 fps | per frame: process peak 13.31 ms, physics peak 1.93 ms, spine update "
            "0.13 ms (signals 0.01 ms), spine draw 3.23 ms, main thread cpu 6.30 ms (busy 37%)\n"
            + "".join(f"[INFO] Player 1 playing card CARD_{index % 9}\n" for index in range(self.cards))
            + "".join(f"[INFO] Monster NIBBIT performing move MOVE_{index % 3}\n" for index in range(self.moves)))


def arguments(name: str, **changes) -> argparse.Namespace:
    values = dict(scenario="fight", path="native", route="bridge", switch=[], switch_off=[], env=[], name=name, serial="fake",
                  latency_count=0, menu_region="", fight_region="", xcheck=False, keep_config=False, no_results_row=True,
                  mode="screen", reps=2, warmup_reps=0, turns=0, with_latency=0, with_cpu=False, with_perfetto=False,
                  with_pacing=False, push_file=[], restore_pushed=False, stop_after=False, stop=False, restart=False,
                  max_thermal_status=2, a="", b="", max_pairs=6, min_pairs=3, noise_sd=2.0, seed=benchlib.CONFIRM_SEED,
                  keep_running=False)
    values.update(changes)
    return argparse.Namespace(**values)


class ModesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.out = Path(self.temp.name)
        self.clock = Clock()
        self.run = load_run(self.out, self.clock)
        self.phone = phone = Phone(self.clock)
        clock = self.clock

        class Fake(self.run.ScreenBench):
            def log(self, text): pass
            def ctl(self, *command, timeout=300, check=False):
                answer = phone.ctl(*command)
                if check and not answer.get("ok"):
                    raise self_run.Abort(f"droiddeckctl {' '.join(command)} failed")
                return answer
            def sh(self, script, timeout=120): return phone.sh(script)
            def adb(self, *command, timeout=120, binary=False): return b"" if binary else ""
            def adb_push(self, local, remote):
                phone.inbox[remote] = Path(local).read_bytes()
            def pads(self, buttons, between_ms=0):
                out = []
                for index, _button in enumerate(buttons):
                    clock.now += between_ms / 1000.0 if index else 0
                    out.append((phone.ms(), phone.ms() + 90))
                    clock.now += 0.1
                phone.pads(buttons)
                return out
            def screen(self, label):
                clock.now += 0.3
                return Shot(phone)
            def background(self, name, command): (self.dir / name).write_text("")
            def tap(self, x, y): pass
        self_run = self.run
        self.Fake = Fake

    def tearDown(self):
        self.temp.cleanup()

    def screen(self, name: str, **changes):
        bench = self.Fake(arguments(name, **changes))
        code = bench.run()
        return code, json.loads((self.out / name / "summary.json").read_text())

    def test_reps_repeat_the_fight_and_the_session_is_kept(self):
        code, summary = self.screen("one")
        self.assertEqual(code, 0, summary.get("failure"))
        self.assertEqual(benchlib.validate_summary(summary) + benchlib.validate_screen(summary), [])
        self.assertEqual(summary["reuse"]["level"], "session")
        self.assertEqual([rep["rep_in_launch"] for rep in summary["reps"]], [1, 2])
        self.assertEqual([rep["cold"] for rep in summary["reps"]], [True, False])
        self.assertEqual(summary["reps"][0]["cards"], summary["reps"][1]["cards"])
        self.assertEqual(len(summary["reps"][0]["cards"]), 9)
        self.assertTrue(summary["identical"]["ok"])
        self.assertEqual(summary["spread"]["anim_p99_ms"]["n"], 2)
        self.assertEqual(summary["reps"][0]["frames"]["animation"]["p99"], 16.0)
        self.assertTrue(summary["left_running"])
        self.assertEqual((self.phone.phase, self.phone.game, self.phone.screen), ("READY", True, "menu"))
        names = summary["phases"]["by_name"]
        self.assertEqual(names["fight"]["count"], 2)
        self.assertNotIn("fixed-wait", names)
        self.assertNotIn("latency", names)

    def test_the_same_configuration_reuses_the_launch_and_stop_puts_everything_back(self):
        self.screen("one")
        code, summary = self.screen("two", reps=1)
        self.assertEqual(code, 0, summary.get("failure"))
        self.assertEqual((summary["reuse"]["level"], self.phone.sessions, self.phone.launches), ("none", 1, 1))
        self.assertEqual((summary["reps"][0]["rep_in_launch"], summary["reps"][0]["cold"]), (3, False))
        self.assertEqual(self.Fake(arguments("x", stop=True)).stop_only(), 0)
        self.assertEqual((self.phone.phase, self.phone.game), ("IDLE", False))
        self.assertFalse((self.out / ".screen-session.json").exists())

    def test_a_launch_level_switch_relaunches_the_game_only_and_a_session_level_one_restarts(self):
        self.phone.bridge = False
        self.screen("one", route="panvk", reps=1)
        code, summary = self.screen("two", route="panvk", reps=1, switch=["present-dmabuf"])
        self.assertEqual(code, 0, summary.get("failure"))
        self.assertEqual((summary["reuse"]["level"], self.phone.sessions, self.phone.launches), ("launch", 1, 2))
        self.assertEqual(self.phone.experiments["present-dmabuf"], "On")
        code, summary = self.screen("three", route="panvk", reps=1, switch=["zram-game-late"])
        self.assertEqual((code, summary["reuse"]["level"], self.phone.sessions), (0, "session", 2))
        self.assertEqual((self.phone.experiments["present-dmabuf"], self.phone.session_experiments["zram-game-late"]), ("Off", "On"))
        code, summary = self.screen("four", route="bridge", reps=1, stop_after=True)
        self.assertEqual((code, summary["reuse"]["level"], self.phone.sessions, self.phone.phase), (0, "session", 3, "IDLE"))
        self.assertEqual((self.phone.experiments["zram-game-late"], self.phone.bridge), ("Off", False))
        self.assertFalse(summary["left_running"])

    def test_settings_out_of_reach_with_a_session_up_falls_back_to_a_new_session(self):
        self.screen("one", reps=1)
        self.phone.settings_with_session = False
        code, summary = self.screen("two", reps=1, switch=["godot-render-thread"])
        self.assertEqual((code, summary["reuse"]["level"], self.phone.sessions), (0, "session", 2))
        self.assertEqual(self.phone.session_experiments["godot-render-thread"], "On")

    def test_a_fight_that_does_not_restart_stops_the_run_and_the_session(self):
        self.phone.restore_on_continue = False
        code, summary = self.screen("one", reps=3)
        self.assertEqual(code, 1)
        self.assertIn("Save and Quit did not put it back", summary["failure"])
        self.assertEqual(len(summary["reps"]), 1)
        self.assertEqual(self.phone.phase, "IDLE")

    def test_rules_are_rechecked_on_a_kept_session(self):
        self.screen("one", reps=1)
        self.phone.offline_pref = False
        code, summary = self.screen("two", reps=1)
        self.assertEqual(code, 1)
        self.assertIn("rule 1", summary["failure"])
        self.assertEqual(self.phone.phase, "IDLE")

    def test_a_session_it_did_not_start_is_left_alone(self):
        self.phone.ctl("start", "steam")
        code, summary = self.screen("one")
        self.assertEqual(code, 1)
        self.assertIn("did not start it", summary["failure"])
        self.assertEqual(self.phone.phase, "READY")

    def test_the_other_profile_is_refused(self):
        self.phone.profile = "red"
        code, summary = self.screen("one")
        self.assertEqual(code, 1)
        self.assertIn("not the bench profile", summary["failure"])

    def test_push_file_keeps_the_original_and_restore_puts_it_back(self):
        local = self.out / "libgodot.so"
        local.write_bytes(b"rebuilt")
        spec = f"{local}:/opt/engine/libgodot.so"
        code, summary = self.screen("one", reps=1, push_file=[spec])
        self.assertEqual(code, 0, summary.get("failure"))
        pushed = summary["pushed_files"][0]
        self.assertEqual(pushed["sha256_pushed"], hashlib.sha256(b"rebuilt").hexdigest())
        self.assertEqual(pushed["sha256_original"], hashlib.sha256(b"original").hexdigest())
        self.assertEqual(self.phone.files["/opt/engine/libgodot.so"], b"rebuilt")
        code, summary = self.screen("two", reps=1, push_file=[spec])
        self.assertEqual((summary["reuse"]["level"], self.phone.launches), ("none", 1))
        local.write_bytes(b"rebuilt again")
        code, summary = self.screen("three", reps=1, push_file=[spec])
        self.assertEqual((code, summary["reuse"]["level"], self.phone.launches, self.phone.sessions), (0, "launch", 2, 1))
        self.assertEqual(summary["pushed_files"][0]["sha256_original"], hashlib.sha256(b"original").hexdigest())
        code, summary = self.screen("four", reps=1, restore_pushed=True)
        self.assertEqual((code, summary["reuse"]["level"]), (0, "launch"))
        self.assertEqual(self.phone.files, {"/opt/engine/libgodot.so": b"original"})
        self.assertTrue(summary["pushed_files"][0]["restored"])

    def test_confirm_interleaves_and_stops_at_max_pairs_when_nothing_differs(self):
        runs = []
        original = self.run.ScreenBench
        self.run.ScreenBench = self.Fake
        try:
            code = self.run.run_confirm(arguments("ab", mode="confirm", reps=1, a="", b="godot-render-thread", max_pairs=3, min_pairs=2))
        finally:
            self.run.ScreenBench = original
        summary = json.loads((self.out / "ab" / "summary.json").read_text())
        self.assertEqual(code, 0, summary.get("failure"))
        self.assertEqual(benchlib.validate_confirm(summary), [])
        self.assertEqual(summary["runs"], ["ab/p01-a", "ab/p01-b", "ab/p02-a", "ab/p02-b", "ab/p03-a", "ab/p03-b"])
        self.assertEqual(summary["share"]["levels"], ["session"] + ["launch"] * 5)
        self.assertEqual((summary["pairs"], summary["decision"]["why"], summary["decision"]["verdict"]), (3, "max pairs", "no difference shown"))
        self.assertEqual(summary["intervals"]["anim_p99_ms"]["diff"], 0.0)
        self.assertEqual((self.phone.phase, self.phone.sessions, self.phone.experiments["godot-render-thread"]), ("IDLE", 1, "Off"))


if __name__ == "__main__":
    unittest.main()
