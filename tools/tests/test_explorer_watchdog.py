import ast
import os
from pathlib import Path
import runpy
import shutil
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
GAME_ENV = BIN / "droiddeck-game-env"
MODULE = runpy.run_path(str(GAME_ENV))["start"].__globals__

APP = "2868840"
MESSAGE = ('0024:err:winediag:nodrv_CreateWindow Application tried to create a window, but no driver could be loaded.\n'
           '0024:err:winediag:nodrv_CreateWindow L"The explorer process failed to start."\n')

# Stands in for Proton: one mode per attempt, in FAKE_MODES. "hang" prints Wine's explorer failure and waits for
# ever, "quiet" waits for ever, "exitN" exits with N, "sigint" ends itself with SIGINT, "logged" writes the
# failure to Proton's log and waits, "fresh" replaces Proton's log with a clean one and exits 0.
FAKE_PROTON = textwrap.dedent('''\
    import os, signal, stat, sys, time
    d = os.environ["FAKE_DIR"]
    runs = os.path.join(d, "runs")
    os.makedirs(runs, exist_ok=True)
    n = len(os.listdir(runs)) + 1
    mode = os.environ["FAKE_MODES"].split(",")[n - 1]
    with open(os.path.join(runs, str(n)), "w") as out:
        out.write(repr({"pid": os.getpid(), "argv": sys.argv[1:], "mode": mode,
                        "WINEDEBUG": os.environ.get("WINEDEBUG"),
                        "stderr_fifo": stat.S_ISFIFO(os.fstat(2).st_mode)}))
    print("stdout from attempt %d" % n, flush=True)
    sys.stderr.write("err: attempt %d starts\\n" % n)
    sys.stderr.flush()
    log = os.path.join(os.environ.get("PROTON_LOG_DIR", ""), "steam-%s.log" % os.environ.get("SteamGameId", ""))
    if mode == "hang":
        sys.stderr.write(MESSAGE_REPR)
        sys.stderr.flush()
    elif mode == "logged" or mode == "fresh":
        if os.path.exists(log):
            os.remove(log)
        with open(log, "a") as f:
            f.write("======================\\n" + (MESSAGE_REPR if mode == "logged" else "fine\\n"))
        if mode == "fresh":
            time.sleep(0.5)
            sys.exit(0)
    elif mode.startswith("exit"):
        sys.exit(int(mode[4:]))
    elif mode == "sigint":
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        os.kill(os.getpid(), signal.SIGINT)
    while True:
        time.sleep(1000)
''').replace("MESSAGE_REPR", repr(MESSAGE))

FAKE_WINESERVER = textwrap.dedent('''\
    import ast, os, signal, sys
    d = os.environ["FAKE_DIR"]
    with open(os.path.join(d, "wineserver"), "a") as out:
        out.write(repr({"argv": sys.argv[1:], "WINEPREFIX": os.environ.get("WINEPREFIX")}) + "\\n")
    if os.environ.get("FAKE_WINESERVER_FAIL"):
        sys.exit(1)
    runs = os.path.join(d, "runs")
    for name in os.listdir(runs):
        with open(os.path.join(runs, name)) as f:
            try:
                os.kill(ast.literal_eval(f.read())["pid"], signal.SIGKILL)
            except OSError:
                pass
''')

HARNESS = textwrap.dedent('''\
    import runpy, sys
    G = runpy.run_path(sys.argv[1])["start"].__globals__
    G.update(EXPLORER_KILL_WAIT_S=3, EXPLORER_POLL_S=0.1, EXPLORER_DRAIN_S=1)
    launch = sys.argv[2:]
    sys.exit(G["start"](launch, dict(G["os"].environ), None, timeline_app_id="%s", watch=True))
''' % APP)


def executable(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!" + sys.executable + "\n" + body)
    path.chmod(0o755)
    return path


class Launch(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="explorer-watchdog-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.state = self.tmp / "state"
        self.state.mkdir()
        self.proton = executable(self.tmp / "Proton/proton", FAKE_PROTON)
        executable(self.tmp / "Proton/files/bin-arm64/wineserver", FAKE_WINESERVER)
        self.harness = self.tmp / "harness.py"
        self.harness.write_text(HARNESS)
        self.compat = self.tmp / "compatdata" / APP
        self.compat.mkdir(parents=True)
        self.err = self.tmp / "stderr.txt"
        self.out = self.tmp / "stdout.txt"

    def env(self, modes, **extra):
        env = {key: value for key, value in os.environ.items() if key not in ("WINEDEBUG", "PROTON_LOG", "WINESERVER")}
        env.update(FAKE_DIR=str(self.state), FAKE_MODES=modes, STEAM_COMPAT_DATA_PATH=str(self.compat),
                   SteamGameId=APP, HOME=str(self.tmp), PATH=os.defpath)
        env.update(extra)
        return env

    def spawn(self, modes, **extra):
        with self.err.open("wb") as err, self.out.open("wb") as out:
            return subprocess.Popen([sys.executable, str(self.harness), str(GAME_ENV), str(self.proton),
                                     "waitforexitandrun", "Z:\\\\game.exe"],
                                    env=self.env(modes, **extra), stdin=subprocess.DEVNULL, stdout=out, stderr=err)

    def run_launch(self, modes, **extra):
        process = self.spawn(modes, **extra)
        try:
            code = process.wait(timeout=60)
        finally:
            if process.poll() is None:
                process.kill()
        return code, self.err.read_text(), self.out.read_text()

    def wait_for(self, text, timeout=30):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if text in self.err.read_text():
                return
            time.sleep(0.05)
        self.fail("%r not seen in:\n%s" % (text, self.err.read_text()))

    def runs(self):
        folder = self.state / "runs"
        return [ast.literal_eval((folder / str(n)).read_text()) for n in range(1, len(list(folder.iterdir())) + 1)]

    def wineserver_calls(self):
        path = self.state / "wineserver"
        return [ast.literal_eval(line) for line in path.read_text().splitlines()] if path.exists() else []

    def tearDown(self):
        for run in self.runs() if (self.state / "runs").exists() else []:
            try:
                os.kill(run["pid"], signal.SIGKILL)
            except OSError:
                pass


class ExplorerWatchdogTest(Launch):
    def test_a_launch_whose_desktop_never_started_is_ended_and_started_once_more(self):
        code, err, out = self.run_launch("hang,exit0")
        self.assertEqual(code, 0, err)
        self.assertEqual([run["mode"] for run in self.runs()], ["hang", "exit0"])
        self.assertEqual(self.runs()[1]["argv"], ["waitforexitandrun", "Z:\\\\game.exe"])
        self.assertEqual(self.wineserver_calls(), [{"argv": ["-k"], "WINEPREFIX": str(self.compat / "pfx")}])
        self.assertRegex(err, r"DROIDDECK_TIMELINE event=game_explorer_failed app_id=%s attempt=1 action=relaunch "
                              r"run_ms=\d+ wall_ms=\d{13}\n" % APP)
        self.assertRegex(err, r"DROIDDECK_TIMELINE event=game_relaunch app_id=%s attempt=2 reason=explorer_failed "
                              r"ended_by=wineserver wall_ms=\d{13}\n" % APP)
        self.assertEqual(err.count("DROIDDECK_TIMELINE event=game_exit "), 1)
        self.assertRegex(err, r"DROIDDECK_TIMELINE event=game_exit app_id=%s status=0 signal=0" % APP)
        self.assertIn('L"The explorer process failed to start."\n', err)
        self.assertIn("err: attempt 2 starts\n", err)
        self.assertEqual(out, "stdout from attempt 1\nstdout from attempt 2\n")

    def test_a_wineserver_that_cannot_end_it_falls_back_to_the_process_tree(self):
        code, err, _ = self.run_launch("hang,exit3", FAKE_WINESERVER_FAIL="1")
        self.assertEqual(code, 3, err)
        self.assertEqual(len(self.runs()), 2)
        self.assertIn("reason=explorer_failed ended_by=tree ", err)

    def test_a_second_failure_is_only_reported_and_a_stop_still_reaches_the_game(self):
        process = self.spawn("hang,hang,exit0")
        try:
            self.wait_for("attempt=2 action=none")
            time.sleep(0.3)
            process.send_signal(signal.SIGTERM)
            code = process.wait(timeout=30)
        finally:
            if process.poll() is None:
                process.kill()
        err = self.err.read_text()
        self.assertEqual(code, 128 + signal.SIGTERM, err)
        self.assertEqual(len(self.runs()), 2)
        self.assertEqual(len(self.wineserver_calls()), 1)
        self.assertEqual(err.count("event=game_relaunch"), 1)
        self.assertRegex(err, r"DROIDDECK_TIMELINE event=game_exit app_id=%s status=143 signal=15" % APP)

    def test_a_normal_exit_keeps_its_status_and_output(self):
        code, err, out = self.run_launch("exit7")
        self.assertEqual(code, 7, err)
        self.assertEqual(len(self.runs()), 1)
        self.assertTrue(self.runs()[0]["stderr_fifo"])
        self.assertEqual(self.runs()[0]["WINEDEBUG"], "-all,err+winediag")
        self.assertIn("err: attempt 1 starts\n", err)
        self.assertEqual(out, "stdout from attempt 1\n")
        self.assertNotIn("explorer", err)
        self.assertEqual(self.wineserver_calls(), [])

    def test_a_game_ended_by_sigint_is_not_started_again(self):
        code, err, _ = self.run_launch("sigint,exit0")
        self.assertEqual(code, 128 + signal.SIGINT, err)
        self.assertEqual(len(self.runs()), 1)
        self.assertNotIn("game_relaunch", err)

    def test_a_stop_before_any_failure_is_forwarded_and_not_retried(self):
        process = self.spawn("quiet,exit0")
        try:
            self.wait_for("err: attempt 1 starts")
            process.send_signal(signal.SIGINT)
            code = process.wait(timeout=30)
        finally:
            if process.poll() is None:
                process.kill()
        self.assertEqual(code, 128 + signal.SIGINT, self.err.read_text())
        self.assertEqual(len(self.runs()), 1)
        self.assertEqual(self.wineserver_calls(), [])

    def test_with_proton_log_on_the_log_is_watched_and_stderr_is_left_alone(self):
        stale = self.tmp / ("steam-%s.log" % APP)
        stale.write_text("old run: " + MESSAGE)
        code, err, _ = self.run_launch("fresh", PROTON_LOG="1", PROTON_LOG_DIR=str(self.tmp))
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.runs()), 1)
        self.assertFalse(self.runs()[0]["stderr_fifo"])
        self.assertIsNone(self.runs()[0]["WINEDEBUG"])

        shutil.rmtree(self.state / "runs")
        code, err, _ = self.run_launch("logged,exit0", PROTON_LOG="1", PROTON_LOG_DIR=str(self.tmp))
        self.assertEqual(code, 0, err)
        self.assertEqual([run["mode"] for run in self.runs()], ["logged", "exit0"])
        self.assertIn("event=game_relaunch app_id=%s attempt=2" % APP, err)
        self.assertIn("explorer process failed", (self.tmp / ("steam-%s-explorer-failed.log" % APP)).read_text())

    def test_the_watchdog_can_be_turned_off(self):
        process = self.spawn("hang,exit0", DROIDDECK_EXPLORER_WATCHDOG="0")
        try:
            self.wait_for("explorer process failed")
            time.sleep(0.5)
            process.send_signal(signal.SIGTERM)
            code = process.wait(timeout=30)
        finally:
            if process.poll() is None:
                process.kill()
        self.assertEqual(code, 128 + signal.SIGTERM)
        self.assertEqual(len(self.runs()), 1)
        self.assertFalse(self.runs()[0]["stderr_fifo"])


class ExplorerWatchdogPartsTest(unittest.TestCase):
    def test_wine_debug_channels_keep_winediag_errors(self):
        winediag = MODULE["with_winediag"]
        self.assertEqual(winediag({})["WINEDEBUG"], "-all,err+winediag")
        self.assertEqual(winediag({"WINEDEBUG": "-all"})["WINEDEBUG"], "-all,err+winediag")
        self.assertEqual(winediag({"WINEDEBUG": "+loaddll"})["WINEDEBUG"], "+loaddll,err+winediag")
        self.assertEqual(winediag({"WINEDEBUG": "-all,err+winediag"})["WINEDEBUG"], "-all,err+winediag")
        logged = {"PROTON_LOG": "1", "SteamGameId": APP}
        self.assertNotIn("WINEDEBUG", winediag(logged))
        self.assertEqual(winediag({"PROTON_LOG": "0", "SteamGameId": APP})["WINEDEBUG"], "-all,err+winediag")

    def test_protons_log_is_found_where_proton_writes_it(self):
        path = MODULE["proton_log_path"]
        self.assertIsNone(path({"PROTON_LOG": "0", "SteamGameId": APP}))
        self.assertIsNone(path({"PROTON_LOG": "1"}))
        self.assertEqual(path({"PROTON_LOG": "1", "SteamGameId": APP, "HOME": "/root"}), "/root/steam-%s.log" % APP)
        self.assertEqual(path({"PROTON_LOG": "+seh", "SteamGameId": APP, "PROTON_LOG_DIR": "/logs", "HOME": "/root"}),
                         "/logs/steam-%s.log" % APP)

    def test_only_proton_game_launches_are_watched(self):
        wanted = MODULE["explorer_watch_wanted"]
        self.assertTrue(wanted(["/p/proton", "waitforexitandrun", "game.exe"], {}))
        self.assertTrue(wanted(["/pvr/run-proton.py", "/p/proton", "waitforexitandrun", "game.exe"], {}))
        self.assertFalse(wanted(["/p/proton", "run", "setup.exe"], {}))
        self.assertFalse(wanted(["/p/proton", "waitforexitandrun"], {"DROIDDECK_EXPLORER_WATCHDOG": "0"}))

    def test_the_wineserver_is_the_launched_protons_own(self):
        with tempfile.TemporaryDirectory() as directory:
            server = executable(Path(directory) / "Proton/files/bin-arm64/wineserver", "")
            pack = executable(Path(directory) / "pack/wineserver", "")
            find = MODULE["wineserver_for"]
            launch = ["/pvr/run-proton.py", directory + "/Proton/proton", "waitforexitandrun", "game.exe"]
            self.assertEqual(find(launch, {"PATH": ""}), str(server))
            self.assertEqual(find(launch, {"WINESERVER": str(pack), "PATH": ""}), str(pack))
            self.assertIsNone(find(["/elsewhere/proton", "waitforexitandrun"], {"PATH": ""}))

    def relay(self, chunks, pause=0.0):
        read, write = os.pipe()
        out_read, out_write = os.pipe()
        seen = []
        relay = MODULE["StderrRelay"](read, seen.append, out=out_write)
        for chunk in chunks:
            os.write(write, chunk)
            time.sleep(pause)
        os.close(write)
        relay.thread.join(10)
        os.close(out_write)
        pieces = []
        while True:
            data = os.read(out_read, 1 << 20)
            if not data:
                break
            pieces.append(data)
        os.close(out_read)
        return b"".join(pieces), b"".join(seen)

    def test_the_relay_passes_every_byte_through(self):
        chunks = [b"one\ntw", b"o\n", b"x" * 10000 + b"\n" + b"y" * 50, b"\nlast without newline"]
        out, seen = self.relay(chunks)
        self.assertEqual(out, b"".join(chunks))
        self.assertEqual(seen, b"".join(chunks))

    def test_a_partial_line_is_written_once_the_child_goes_quiet(self):
        read, write = os.pipe()
        out_read, out_write = os.pipe()
        relay = MODULE["StderrRelay"](read, lambda data: None, out=out_write)
        os.write(write, b"prompt: ")
        self.assertEqual(os.read(out_read, 100), b"prompt: ")
        os.close(write)
        relay.thread.join(10)
        os.close(out_write)
        os.close(out_read)

    def test_the_relay_keeps_draining_when_its_output_is_gone(self):
        read, write = os.pipe()
        out_read, out_write = os.pipe()
        os.close(out_read)
        relay = MODULE["StderrRelay"](read, lambda data: None, out=out_write)
        for _ in range(64):
            os.write(write, b"z" * 4095 + b"\n")
        os.close(write)
        relay.thread.join(10)
        self.assertFalse(relay.thread.is_alive())
        self.assertTrue(relay.broken)
        os.close(out_write)

    def test_the_message_is_found_across_reads(self):
        guard = MODULE["ExplorerGuard"](["/p/proton", "waitforexitandrun"], {}, APP, 1, lambda: False)
        fired = []
        guard.fire = lambda: fired.append(True)
        guard.deadline = time.monotonic() + 60
        text = MESSAGE.encode()
        at = text.index(b"explorer") + 3
        guard.scan(text[:at])
        self.assertEqual(fired, [])
        guard.scan(text[at:])
        self.assertEqual(fired, [True])

    def test_the_message_after_the_launch_window_is_ignored(self):
        guard = MODULE["ExplorerGuard"](["/p/proton", "waitforexitandrun"], {}, APP, 1, lambda: False)
        fired = []
        guard.fire = lambda: fired.append(True)
        guard.deadline = time.monotonic() - 1
        guard.scan(MESSAGE.encode())
        self.assertEqual(fired, [])


if __name__ == "__main__":
    unittest.main()
