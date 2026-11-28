import io
import json
import os
from pathlib import Path
import re
import runpy
import signal
import struct
import subprocess
import sys
import tempfile
import time
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-steam-ui-park"), run_name="steam_ui_park")
WebProc = MODULE["WebProc"]
GAME = 2868840


class Rig:
    """A Park over a fake clock, process table, focus, cloud log and pad."""

    def __init__(self, tmp, dwell=20):
        self.now = 1000.0
        self.tmp = Path(tmp)
        (self.tmp / "agent").mkdir()
        self.helpers = [WebProc(100, 1, True, 300 * 1024, 50 * 1024), WebProc(101, 1, False, 100 * 1024, 0)]
        self.games = {GAME}
        self.focused, self.overlay = GAME, False
        self.signals = []
        self.ready = True
        self.respawn = None
        self.out = io.StringIO()
        self.cloud = MODULE["CloudLog"](str(self.tmp / "cloud_log.txt"), clock=lambda: self.now)
        self.pressed = False

        class Pad:
            seen = {}

            def pressed(pad):
                hit, self.pressed = self.pressed, False
                return hit

        self.park = MODULE["Park"](
            str(self.tmp), str(self.tmp / "steam-ui-gate"), cloud=self.cloud, pad=Pad(),
            scan=lambda: (list(self.helpers), set(self.games)),
            mem=lambda: {"MemAvailable": 700, "SwapFree": 2000, "Gpu": 2990 - (0 if self.helpers else 540)},
            focus=lambda path: (self.focused, self.overlay), ready=lambda port: self.ready, kill=self.kill,
            clock=lambda: self.now, wall=lambda: 1_700_000_000 + self.now, out=self.out, dwell=dwell)

    def kill(self, pid, signum):
        self.signals.append((pid, signum))
        if self.respawn == "ignore":
            return
        self.helpers = []

    def run(self, seconds, step=0.5):
        end = self.now + seconds
        while self.now < end:
            self.park.step()
            self.now += step

    def events(self):
        return re.findall(r"event=steam_ui_(\w+)", self.out.getvalue())

    def line(self, event):
        return [l for l in self.out.getvalue().splitlines() if "event=steam_ui_%s " % event in l][-1]

    def gate(self):
        return (self.tmp / "steam-ui-gate").exists()


class ParkTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="park-")
        self.rig = Rig(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def test_parks_after_the_dwell_and_restores_at_game_exit(self):
        rig = self.rig
        rig.run(19)
        self.assertEqual(rig.signals, [])
        self.assertFalse(rig.gate())
        rig.run(4)
        self.assertEqual(rig.signals, [(100, signal.SIGTERM)])
        self.assertTrue(rig.gate())
        self.assertEqual((rig.tmp / "steam-ui-gate").read_text().strip(), str(os.getpid()))
        self.assertEqual(rig.park.state, "parked")
        self.assertIn("before_gpu_mb=2990", rig.line("parked"))
        self.assertIn("after_gpu_mb=2450", rig.line("parked"))
        rig.run(12)
        self.assertIn("park_settled", rig.events())
        rig.games = set()
        rig.run(3)
        self.assertFalse(rig.gate())
        self.assertEqual(rig.park.state, "restoring")
        self.assertIn("reason=game_exit", rig.line("restore_begin"))
        rig.helpers = [WebProc(200, 9, True, 400 * 1024, 0)]
        rig.run(1)
        self.assertEqual(rig.park.state, "idle")
        self.assertRegex(rig.line("restored"), r"reason=game_exit took_ms=\d+ procs=1 rss_mb=400")
        rig.run(60)
        self.assertEqual(rig.signals, [(100, signal.SIGTERM)])

    def test_never_parks_without_a_focused_game_or_with_the_overlay_up(self):
        rig = self.rig
        rig.focused = 769
        rig.run(60)
        rig.focused, rig.overlay = GAME, True
        rig.run(60)
        rig.overlay, rig.games = False, set()
        rig.run(60)
        self.assertEqual(rig.signals, [])
        self.assertFalse(rig.gate())

    def test_the_dwell_starts_again_when_focus_leaves_the_game(self):
        rig = self.rig
        rig.run(15)
        rig.focused = 769
        rig.run(2)
        rig.focused = GAME
        rig.run(15)
        self.assertEqual(rig.signals, [])
        rig.run(8)
        self.assertEqual(len(rig.signals), 1)

    def test_an_open_cloud_sync_blocks_parking_and_brings_a_parked_interface_back(self):
        rig = self.rig
        log = rig.tmp / "cloud_log.txt"
        log.write_text("[2026-11-27 21:11:21] old line\n")
        rig.run(4)
        with log.open("a") as f:
            f.write("[2026-11-27 21:11:41] [AppID 2868840] Starting sync (AC Launch,)\n")
        rig.run(40)
        self.assertEqual(rig.signals, [])
        self.assertIn("reason=cloud_sync", rig.line("park_waits"))
        with log.open("a") as f:
            f.write("[2026-11-27 21:11:43] [AppID 2868840] Successfully synced to ChangeNumber 5\n"
                    "[2026-11-27 21:11:43] [AppID 2868840] YldWriteCacheDirectoryToFile - saved to 'x'\n")
        rig.run(6)
        self.assertEqual(len(rig.signals), 1)
        self.assertEqual(rig.park.state, "parked")
        with log.open("a") as f:
            f.write("[2026-11-27 21:17:48] [AppID 2868840] Starting sync (AC Exit,)\n")
        rig.run(4)
        self.assertFalse(rig.gate())
        self.assertIn("reason=cloud_sync", rig.line("restore_begin"))

    def test_a_sync_with_no_end_line_stops_blocking_after_the_stale_limit(self):
        rig = self.rig
        log = rig.tmp / "cloud_log.txt"
        log.write_text("")
        rig.run(3)
        with log.open("a") as f:
            f.write("[t] [AppID 7] Starting sync (init,)\n")
        rig.run(MODULE["SYNC_STALE_S"] - 10)
        self.assertEqual(rig.signals, [])
        rig.run(20)
        self.assertEqual(len(rig.signals), 1)

    def test_the_steam_button_restores_and_holds_parking_off_for_a_while(self):
        rig = self.rig
        rig.run(25)
        self.assertEqual(rig.park.state, "parked")
        rig.pressed = True
        rig.run(1)
        self.assertIn("reason=steam_button", rig.line("restore_begin"))
        rig.helpers = [WebProc(200, 9, True, 1, 0)]
        rig.run(1)
        self.assertEqual(rig.park.state, "idle")
        rig.run(MODULE["PAD_HOLDOFF_S"] - 5)
        self.assertEqual(len(rig.signals), 1)
        rig.run(10)
        self.assertEqual(rig.signals[-1], (200, signal.SIGTERM))

    def test_a_helper_that_returns_while_the_gate_is_up_turns_parking_off(self):
        rig = self.rig
        rig.run(25)
        rig.helpers = [WebProc(300, 5, True, 1, 0)]
        rig.run(3)
        self.assertEqual(rig.park.state, "off")
        self.assertFalse(rig.gate())
        self.assertIn("action=parking_off", rig.line("park_hold_missed"))
        rig.run(120)
        self.assertEqual(len(rig.signals), 1)

    def test_a_helper_that_ignores_sigterm_is_left_alone_and_parking_goes_off(self):
        rig = self.rig
        rig.respawn = "ignore"
        rig.run(25 + MODULE["TERM_GRACE_S"])
        self.assertEqual(rig.park.state, "off")
        self.assertFalse(rig.gate())
        self.assertEqual([s for _, s in rig.signals], [signal.SIGTERM])
        self.assertIn("reason=helper_ignored_sigterm", rig.line("park_failed"))

    def test_children_left_by_a_dead_browser_are_killed(self):
        rig = self.rig

        def kill(pid, signum):
            rig.signals.append((pid, signum))
            rig.helpers = [p for p in rig.helpers if p.pid != pid]

        rig.park.kill = kill
        rig.run(25)
        self.assertEqual(rig.signals, [(100, signal.SIGTERM), (101, signal.SIGKILL)])
        self.assertIn("killed=1", rig.line("parked"))

    def test_an_interface_that_does_not_return_restarts_the_client_only_without_a_game(self):
        rig = self.rig
        rig.run(25)
        rig.focused = 769
        rig.run(MODULE["RESTORE_S"] + 5)
        self.assertIn("action=wait_for_game_exit", rig.line("restore_late"))
        self.assertFalse((rig.tmp / "steam-ui-restart").exists())
        rig.games = set()
        rig.run(MODULE["RESTORE_RETRY_S"] + 5)
        self.assertTrue((rig.tmp / "steam-ui-restart").exists())
        self.assertIn("action=client_restart", rig.line("restore_failed"))
        self.assertEqual(rig.park.state, "off")

    def test_a_helper_that_is_back_but_does_not_answer_is_not_called_restored(self):
        rig = self.rig
        rig.run(25)
        rig.games, rig.ready = set(), False
        rig.run(3)
        rig.helpers = [WebProc(200, 9, True, 1, 0)]
        rig.run(10)
        self.assertEqual(rig.park.state, "restoring")
        rig.ready = True
        rig.run(1)
        self.assertEqual(rig.park.state, "idle")

    def test_requests_park_without_a_game_and_restore_keeps_it_back(self):
        rig = self.rig
        rig.games, rig.focused = set(), 769
        (rig.tmp / "steam-ui-park").write_text("park\n")
        rig.run(2)
        self.assertEqual(rig.park.state, "parked")
        self.assertIn("reason=asked", rig.line("park_begin"))
        rig.run(30)
        self.assertEqual(rig.park.state, "parked")
        (rig.tmp / "steam-ui-park").write_text("restore\n")
        rig.run(1)
        self.assertIn("reason=asked", rig.line("restore_begin"))
        rig.helpers = [WebProc(200, 9, True, 1, 0)]
        rig.games, rig.focused = {GAME}, GAME
        rig.run(60)
        self.assertEqual(len(rig.signals), 1)
        (rig.tmp / "steam-ui-park").write_text("bogus")
        rig.run(1)
        self.assertIn("park_request_ignored", rig.events())
        (rig.tmp / "steam-ui-park").write_text("auto")
        rig.run(30)
        self.assertEqual(len(rig.signals), 2)

    def test_closing_removes_the_gate(self):
        rig = self.rig
        rig.run(25)
        self.assertTrue(rig.gate())
        rig.park.close()
        self.assertFalse(rig.gate())
        self.assertIn("reason=controller_exit", rig.line("restore_begin"))


class ProbeTest(unittest.TestCase):
    def test_scan_finds_helpers_and_running_games(self):
        with tempfile.TemporaryDirectory() as tmp:
            def proc(pid, args, rss=0, swap=0, state="S"):
                d = Path(tmp) / str(pid)
                d.mkdir()
                (d / "cmdline").write_bytes(b"\0".join(a.encode() for a in args) + b"\0")
                (d / "stat").write_text("%d (x y) %s " % (pid, state) + " ".join(["0"] * 18) + " 777 0\n")
                (d / "status").write_text("VmRSS:\t%d kB\nVmSwap:\t%d kB\n" % (rss, swap))
            proc(10, ["/s/steamrtarm64/steamwebhelper", "-lang=en_US"], 2048, 1024)
            proc(11, ["/s/steamrtarm64/steamwebhelper", "--type=renderer"], 1024)
            proc(12, ["/s/steamrtarm64/steamwebhelper", "--type=gpu"], state="Z")
            proc(13, ["bash", "/s/steamrtarm64/steamwebhelper.sh"])
            proc(14, ["/s/reaper", "SteamLaunch", "AppId=2868840", "--", "game"])
            proc(15, ["/s/reaper", "SteamLaunch", "AppId=769", "--"])
            proc(16, ["/s/steamrtarm64/steam", "-gamepadui"])
            helpers, games = MODULE["scan"](tmp)
            self.assertEqual(sorted((p.pid, p.browser, p.rss, p.swap, p.start) for p in helpers),
                             [(10, True, 2048, 1024, 777), (11, False, 1024, 0, 777)])
            self.assertEqual(games, {2868840})

    def test_focus_reads_the_agents_snapshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "focus.json"
            self.assertEqual(MODULE["focus"](str(path)), (None, False))
            path.write_text(json.dumps({"focusedApp": GAME, "displays": {":0": [
                {"map": "viewable", "appId": 769}, {"map": "unmapped", "overlay": 1}]}}))
            self.assertEqual(MODULE["focus"](str(path)), (GAME, False))
            path.write_text(json.dumps({"focusedApp": GAME, "displays": {":0": [{"map": "viewable", "overlay": 1}]}}))
            self.assertEqual(MODULE["focus"](str(path)), (GAME, True))

    def test_meminfo_reports_gpu_where_the_kernel_has_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "meminfo"
            path.write_text("MemTotal: 7753000 kB\nMemAvailable:  716800 kB\nSwapFree: 2048000 kB\nGpu: 3061760 kB\n")
            self.assertEqual(MODULE["meminfo"](str(path)), {"MemAvailable": 700, "SwapFree": 2000, "Gpu": 2990})
            path.write_text("MemAvailable:  716800 kB\n")
            self.assertEqual(MODULE["meminfo"](str(path))["Gpu"], -1)

    def test_pad_sees_the_steam_button_in_the_snapshot_and_in_events(self):
        with tempfile.TemporaryDirectory() as tmp:
            ring = Path(tmp) / "ring0"
            data = bytearray(64 + 24 * 4096)

            def write(seq, buttons, events=()):
                for index, (kind, code, value) in events:
                    struct.pack_into("<HHi", data, 64 + (index % 4096) * 24 + 16, kind, code, value)
                struct.pack_into("<q", data, 16, seq)
                struct.pack_into("<I", data, 40, buttons)
                ring.write_bytes(data)

            pad = MODULE["Pad"](tmp)
            write(0, 0)
            self.assertFalse(pad.pressed())
            write(2, 1, [(0, (1, 0x130, 1)), (1, (0, 0, 0))])
            self.assertFalse(pad.pressed())
            write(2, 1 << 10)
            self.assertTrue(pad.pressed())
            write(2, 1 << 11)
            self.assertTrue(pad.pressed())
            write(6, 0, [(2, (1, 0x13C, 1)), (3, (0, 0, 0)), (4, (1, 0x13C, 0)), (5, (0, 0, 0))])
            self.assertTrue(pad.pressed())
            self.assertFalse(pad.pressed())
            self.assertFalse(MODULE["Pad"](str(Path(tmp) / "none")).pressed())
            write(6, 1 << 10)
            self.assertEqual(MODULE["Pad"]("", exported="0=%s;1=/nonexistent/ring1" % ring).paths, [str(ring), "/nonexistent/ring1"])
            self.assertTrue(MODULE["Pad"]("", exported="0=%s" % ring).pressed())


class GateHoldTest(unittest.TestCase):
    """droiddeck-steam-taskset holds the web helper's start while the gate is up."""

    def start(self, root, gate_owner):
        helper = root / "steamwebhelper"
        helper.write_text("#!/bin/sh\necho started > \"$STARTED\"\nexit 23\n")
        helper.chmod(0o755)
        real = root / "real-taskset"
        real.write_text("#!/bin/sh\nexit 0\n")
        real.chmod(0o755)
        wrapper = root / "taskset"
        wrapper.write_text((BIN / "droiddeck-steam-taskset").read_text().replace("/usr/bin/taskset", str(real)))
        gate = root / "gate"
        if gate_owner is not None:
            gate.write_text("%s\n" % gate_owner)
        env = dict(os.environ, BL_STEAM_WEBHELPER=str(helper), BL_STEAM_UI_GATE=str(gate), STARTED=str(root / "started"))
        return subprocess.Popen(["bash", str(wrapper), "0x7c", str(helper), "-lang=en_US"], env=env,
                                stderr=subprocess.PIPE, text=True), gate

    def test_holds_until_the_gate_is_removed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            process, gate = self.start(root, os.getpid())
            time.sleep(1.0)
            self.assertIsNone(process.poll())
            self.assertFalse((root / "started").exists())
            self.assertEqual(len(list(root.glob("gate.held.*"))), 1)
            gate.unlink()
            _, err = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 23)
            self.assertTrue((root / "started").exists())
            self.assertEqual(list(root.glob("gate.held.*")), [])
            self.assertRegex(err, r"event=steam_ui_park_hold pid=\d+")
            self.assertRegex(err, r"event=steam_ui_park_hold_end pid=\d+ reason=released held_s=\d+")

    def test_runs_at_once_without_a_gate_or_when_its_owner_is_gone(self):
        with tempfile.TemporaryDirectory() as tmp:
            process, _ = self.start(Path(tmp), None)
            _, err = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 23)
            self.assertNotIn("steam_ui_park_hold", err)
        gone = subprocess.Popen(["true"])
        gone.wait()
        for owner in (gone.pid, "not-a-pid", ""):
            with tempfile.TemporaryDirectory() as tmp:
                process, _ = self.start(Path(tmp), owner)
                process.communicate(timeout=10)
                self.assertEqual(process.returncode, 23, owner)

    def test_gives_up_when_the_starter_is_gone(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "gate").write_text("%d\n" % os.getpid())
            helper = root / "steamwebhelper"
            helper.write_text("#!/bin/sh\necho started > \"$STARTED\"\n")
            helper.chmod(0o755)
            wrapper = root / "taskset"
            wrapper.write_text((BIN / "droiddeck-steam-taskset").read_text().replace("/usr/bin/taskset", "/bin/true"))
            env = dict(os.environ, BL_STEAM_WEBHELPER=str(helper), BL_STEAM_UI_GATE=str(root / "gate"),
                       STARTED=str(root / "started"))
            starter = subprocess.Popen(["bash", "-c", 'bash "$0" 0x7c "$1" & echo $! > "$2"; sleep 30',
                                        str(wrapper), str(helper), str(root / "pid")], env=env, stderr=subprocess.DEVNULL)
            time.sleep(1.0)
            held = int((root / "pid").read_text())
            starter.kill()
            starter.wait()
            for _ in range(50):
                try:
                    os.kill(held, 0)
                except OSError:
                    break
                time.sleep(0.1)
            else:
                os.kill(held, signal.SIGKILL)
                self.fail("the held start outlived its starter")
            self.assertFalse((root / "started").exists())


class XSoftTest(unittest.TestCase):
    """droiddeck-steam-xwayland takes glamor from the first X server of a gamescope only."""

    def run_wrapper(self, root, display, locks):
        real = root / "Xwayland-real"
        real.write_text("#!/bin/sh\nprintf '%s|%s\\n' \"${XWAYLAND_NO_GLAMOR:-}\" \"$*\"\n")
        real.chmod(0o755)
        for number, owner in locks.items():
            (root / (".X%d-lock" % number)).write_text("%10d\n" % owner)
        notes = root / "notes"
        notes.mkdir(exist_ok=True)
        env = dict(os.environ, BL_XWAYLAND_REAL=str(real), BL_X_LOCK_DIR=str(root), BL_X_SOFT_DIR=str(notes))
        env.pop("XWAYLAND_NO_GLAMOR", None)
        args = ([display] if display else []) + ["-rootless", "-core", "-listenfd", "40"]
        result = subprocess.run(["bash", str(BIN / "droiddeck-steam-xwayland"), *args], env=env, capture_output=True,
                                text=True, timeout=10)
        return result.stdout.strip(), result.stderr, notes

    def test_the_first_server_of_a_gamescope_is_soft_and_the_second_is_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out, err, notes = self.run_wrapper(root, ":0", {0: 4242, 1: 4242})
            self.assertEqual(out, "1|:0 -rootless -core -listenfd 40")
            self.assertEqual((notes / "soft").read_text().strip(), ":0")
            self.assertIn("starts without glamor", err)
            out, err, notes = self.run_wrapper(root, ":1", {0: 4242, 1: 4242})
            self.assertEqual(out, "|:1 -rootless -core -listenfd 40")
            self.assertTrue((notes / "glamor.1").exists())

    def test_another_compositors_lower_display_does_not_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            out, _, _ = self.run_wrapper(Path(tmp), ":3", {0: 1, 3: 4242, 4: 4242})
            self.assertEqual(out, "1|:3 -rootless -core -listenfd 40")
        with tempfile.TemporaryDirectory() as tmp:
            out, _, _ = self.run_wrapper(Path(tmp), ":4", {0: 1, 3: 4242, 4: 4242})
            self.assertEqual(out, "|:4 -rootless -core -listenfd 40")

    def test_without_a_lock_or_a_display_glamor_stays(self):
        with tempfile.TemporaryDirectory() as tmp:
            out, err, _ = self.run_wrapper(Path(tmp), ":0", {})
            self.assertEqual(out, "|:0 -rootless -core -listenfd 40")
            self.assertIn("keeps glamor", err)
        with tempfile.TemporaryDirectory() as tmp:
            out, _, _ = self.run_wrapper(Path(tmp), "", {0: 4242})
            self.assertEqual(out, "|-rootless -core -listenfd 40")


class SessionWiringTest(unittest.TestCase):
    SESSION = (BIN / "droiddeck-session").read_text()

    def function(self, name):
        start = self.SESSION.index("%s() {" % name)
        return self.SESSION[start:self.SESSION.index("\n}\n", start) + 3]

    def x_soft(self, **env):
        with tempfile.TemporaryDirectory() as tmp:
            wrapper = Path(tmp) / "Xwayland"
            wrapper.write_text("#!/bin/sh\n")
            wrapper.chmod(0o755)
            script = (self.function("steam_x_soft").replace("/usr/local/libexec/droiddeck-steam/Xwayland", str(wrapper))
                      + '\nsteam_x_soft\nprintf "WLR=%s\\n" "${WLR_XWAYLAND:-}"\n')
            base = {k: v for k, v in os.environ.items() if not k.startswith(("BL_", "WLR_", "XWAYLAND_"))}
            result = subprocess.run(["bash", "-c", script], env=dict(base, XDG_RUNTIME_DIR=tmp, **env),
                                    capture_output=True, text=True, timeout=10)
            return result.stdout, str(wrapper)

    def test_x_soft_applies_only_where_steam_composites_in_software(self):
        out, wrapper = self.x_soft(BL_STEAM_SOFT_COMPOSITING="1")
        self.assertIn("WLR=" + wrapper, out)
        out, wrapper = self.x_soft(BL_STEAM_X_SOFT="1")
        self.assertIn("WLR=" + wrapper, out)
        for env, why in (({}, "composites on the GPU"),
                         ({"BL_STEAM_SOFT_COMPOSITING": "1", "BL_STEAM_X_SOFT": "0"}, "BL_STEAM_X_SOFT=0"),
                         ({"BL_STEAM_SOFT_COMPOSITING": "1", "BL_XWAYLAND_COUNT": "1"}, "one Xwayland"),
                         ({"BL_STEAM_SOFT_COMPOSITING": "1", "XWAYLAND_NO_GLAMOR": "1"}, "already runs without glamor"),
                         ({"BL_STEAM_SOFT_COMPOSITING": "1", "BL_STEAM_UI": "desktop"}, "desktop UI")):
            out, _ = self.x_soft(**env)
            self.assertIn("WLR=\n", out, env)
            self.assertIn("not applied: ", out)
            self.assertIn(why, out)
            self.assertIn("event=steam_x_soft applied=0", out)

    def test_the_report_names_the_display_that_lost_glamor(self):
        with tempfile.TemporaryDirectory() as tmp:
            script = self.function("steam_x_soft_report") + "\nsteam_x_soft_report\n"
            (Path(tmp) / "soft").write_text(":0\n")
            env = dict(os.environ, BL_X_SOFT_DIR=tmp, DISPLAY=":0", STEAM_GAME_DISPLAY_0=":1")
            out = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True).stdout
            self.assertIn("event=steam_x_soft applied=1 display=:0 game_display=:1", out)
            out = subprocess.run(["bash", "-c", script], env=dict(env, DISPLAY=":1"), capture_output=True, text=True).stdout
            self.assertIn("NOT in effect", out)
            self.assertIn("event=steam_x_soft applied=0", out)
            env.pop("BL_X_SOFT_DIR")
            self.assertEqual(subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True).stdout, "")

    def test_both_switches_are_wired_and_default_off(self):
        self.assertIn('[ "${DROIDDECK_EXP_STEAM_X_SOFT:-}" = 1 ]; then steam_x_soft; fi', self.SESSION)
        self.assertIn('if [ "${DROIDDECK_EXP_STEAM_UI_PARK:-}" = 1 ]; then', self.SESSION)
        self.assertIn('/usr/local/bin/droiddeck-steam-ui-park "$steam_root" &', self.SESSION)
        kotlin = (BIN.parents[5] / "app/src/main/java/com/droiddeck/launcher/session/Experiments.kt").read_text()
        defaults = re.search(r"val defaults = setOf\(([^)]*)\)", kotlin).group(1)
        self.assertNotIn("STEAM_X_SOFT", defaults)
        self.assertNotIn("STEAM_UI_PARK", defaults)
        files = (BIN.parents[5] / "app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt").read_text()
        self.assertIn('"usr/local/bin/droiddeck-steam-xwayland" to "usr/local/libexec/droiddeck-steam/Xwayland"', files)
        self.assertIn('"usr/local/bin/droiddeck-steam-ui-park" to "usr/local/bin/droiddeck-steam-ui-park"', files)


if __name__ == "__main__":
    unittest.main()
