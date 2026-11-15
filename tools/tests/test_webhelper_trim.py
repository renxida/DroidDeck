import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import runpy
import signal
import socket
import struct
import sys
import tempfile
import threading
import time
from unittest import mock
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))["start"].__globals__

APP = "2868840"
LINE = re.compile(r"DROIDDECK_TIMELINE event=game_webhelper_trim app_id=(\S+) round=(\d+) targets=(\d+) trimmed=(\d+) "
                  r"failed=(\d+) procs=(\d+) rss_mb_before=(\d+) swap_mb_before=(\d+) rss_mb_after=(\d+) "
                  r"swap_mb_after=(\d+) took_ms=(\d+) next_s=(\d+) wall_ms=(\d+)$")
OFF = re.compile(r"DROIDDECK_TIMELINE event=game_webhelper_trim_off app_id=(\S+) reason=(\S+) round=(\d+) detail=(\S+) "
                 r"wall_ms=(\d+)$")
PAGES = [{"type": "page", "title": "SharedJSContext", "webSocketDebuggerUrl": "ws://127.0.0.1:1/devtools/page/A"},
         {"type": "page", "title": "Steam Big Picture Mode", "webSocketDebuggerUrl": "ws://127.0.0.1:1/devtools/page/B"},
         {"type": "worker", "title": "w", "webSocketDebuggerUrl": "ws://127.0.0.1:1/devtools/page/C"},
         {"type": "browser", "title": "x", "webSocketDebuggerUrl": "ws://127.0.0.1:1/devtools/browser/D"},
         {"type": "page", "title": "attached elsewhere"}]


class FakeDevTools:
    """Stands in for SteamDevTools: the targets it lists and the ones it was asked to trim."""

    def __init__(self, targets=PAGES, fail=(), unreachable=None):
        self.listed = targets
        self.fail = set(fail)
        self.unreachable = unreachable
        self.lists = 0
        self.trimmed = []

    def __call__(self, port):
        self.port = port
        return self

    def targets(self):
        self.lists += 1
        if self.unreachable:
            raise self.unreachable
        return self.listed

    def trim(self, target):
        self.trimmed.append(target["title"])
        if target["title"] in self.fail:
            raise ConnectionResetError("devtools closed the connection")
        return 2


class Usage:
    """webhelper_usage readings in turn (processes, resident kB, swapped kB); the last one repeats."""

    def __init__(self, *readings):
        self.readings = list(readings)

    def __call__(self):
        return self.readings.pop(0) if len(self.readings) > 1 else self.readings[0]


def lines(stream, pattern):
    return [m for m in (pattern.match(line) for line in stream.getvalue().splitlines()) if m]


def wait_for(predicate, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(0.01)
    return False


class WebhelperTrimTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(sys, "stderr", new_callable=io.StringIO)
        self.err = patcher.start()
        self.addCleanup(patcher.stop)

    def watch(self, devtools, usage=None, port=8080, first=0, every=60, settle=0):
        watch = MODULE["WebhelperTrim"](APP, port=port, devtools=devtools, usage=usage or Usage((2, 0, 0)),
                                        first=first, every=every, settle=settle)
        self.addCleanup(watch.stop)
        return watch

    def test_trims_every_page_and_worker_and_reports_before_and_after(self):
        devtools = FakeDevTools()
        usage = Usage((2, 147 * 1024, 452 * 1024), (2, 120 * 1024, 300 * 1024))
        watch = self.watch(devtools, usage).start()
        self.assertTrue(wait_for(lambda: lines(self.err, LINE)))
        watch.stop()
        watch.thread.join(5)
        self.assertEqual(devtools.port, 8080)
        self.assertEqual(devtools.trimmed, ["SharedJSContext", "Steam Big Picture Mode", "w"])
        (line,) = lines(self.err, LINE)
        self.assertEqual(line.group(1, 2, 3, 4, 5, 6), (APP, "1", "3", "3", "0", "2"))
        self.assertEqual(line.group(7, 8, 9, 10), ("147", "452", "120", "300"))
        self.assertEqual(line.group(12), "60")
        self.assertEqual(lines(self.err, OFF), [])

    def test_a_target_that_fails_is_counted_and_the_watch_goes_on(self):
        devtools = FakeDevTools(fail={"w"})
        watch = self.watch(devtools, every=0.02).start()
        self.assertTrue(wait_for(lambda: len(lines(self.err, LINE)) >= 2))
        watch.stop()
        watch.thread.join(5)
        self.assertEqual(lines(self.err, LINE)[0].group(3, 4, 5), ("3", "2", "1"))
        self.assertEqual(lines(self.err, OFF), [])

    def test_unreachable_devtools_says_so_once_and_stops(self):
        devtools = FakeDevTools(unreachable=ConnectionRefusedError(111, "Connection refused"))
        watch = self.watch(devtools, every=0.01).start()
        watch.thread.join(5)
        self.assertFalse(watch.thread.is_alive())
        self.assertEqual(devtools.lists, 1)
        (off,) = lines(self.err, OFF)
        self.assertEqual(off.group(1, 2, 3), (APP, "devtools_unreachable", "1"))
        self.assertIn("Connection_refused", off.group(4))
        self.assertEqual(lines(self.err, LINE), [])

    def test_every_target_failing_stops_the_watch(self):
        devtools = FakeDevTools(fail={"SharedJSContext", "Steam Big Picture Mode", "w"})
        watch = self.watch(devtools, every=0.01).start()
        watch.thread.join(5)
        self.assertEqual(devtools.lists, 1)
        self.assertEqual([m.group(2) for m in lines(self.err, OFF)], ["cdp_failed"])

    def test_no_page_targets_stops_the_watch(self):
        devtools = FakeDevTools(targets=[PAGES[3]])
        watch = self.watch(devtools, every=0.01).start()
        watch.thread.join(5)
        self.assertEqual([m.group(2) for m in lines(self.err, OFF)], ["no_targets"])
        self.assertEqual(devtools.trimmed, [])

    def test_no_port_says_so_once_and_never_calls_devtools(self):
        devtools = FakeDevTools()
        with mock.patch.dict(os.environ, {"BL_LAUNCH_DIR": "", "BL_CDP_PORT": ""}):
            watch = self.watch(devtools, port=None).start()
            watch.thread.join(5)
        self.assertEqual([m.group(2) for m in lines(self.err, OFF)], ["no_port"])
        self.assertEqual(devtools.lists, 0)

    def test_an_unexpected_error_never_leaves_the_watch(self):
        def broken():
            raise ValueError("bad /proc")
        watch = self.watch(FakeDevTools(), usage=broken).start()
        watch.thread.join(5)
        self.assertFalse(watch.thread.is_alive())
        self.assertEqual([m.group(2) for m in lines(self.err, OFF)], ["error"])

    def test_nothing_happens_before_the_first_wait_and_it_stops_with_the_game(self):
        devtools = FakeDevTools()
        watch = self.watch(devtools, first=30).start()
        time.sleep(0.05)
        watch.stop()
        watch.thread.join(5)
        self.assertFalse(watch.thread.is_alive())
        self.assertEqual(devtools.lists, 0)
        self.assertEqual(self.err.getvalue(), "")

    def test_stops_between_rounds_when_the_game_exits(self):
        devtools = FakeDevTools()
        watch = self.watch(devtools, every=0.05).start()
        self.assertTrue(wait_for(lambda: len(lines(self.err, LINE)) >= 2))
        watch.stop()
        watch.thread.join(5)
        self.assertFalse(watch.thread.is_alive())
        rounds = devtools.lists
        time.sleep(0.15)
        self.assertEqual(devtools.lists, rounds)
        self.assertEqual([int(m.group(2)) for m in lines(self.err, LINE)], list(range(1, rounds + 1)))


    def test_stops_by_itself_after_the_last_round(self):
        devtools = FakeDevTools()
        watch = self.watch(devtools, every=0.01).start()
        watch.thread.join(5)
        self.assertFalse(watch.thread.is_alive())
        self.assertEqual(devtools.lists, MODULE["WEBHELPER_TRIM_ROUNDS"])
        self.assertEqual([int(m.group(2)) for m in lines(self.err, LINE)], [1, 2])
        self.assertEqual(lines(self.err, OFF), [])

class LaunchTest(unittest.TestCase):
    def run_start(self, env):
        made = []

        class Fake:
            def __init__(self, app_id, stopper=None):
                made.append([app_id, "made", type(stopper).__name__])

            def start(self):
                made[-1].append("started")
                return self

            def stop(self):
                made[-1].append("stopped")

        with mock.patch.dict(MODULE, {"WebhelperTrim": Fake, "record_final_env": lambda *args: None}), \
                mock.patch.object(sys, "stderr", new_callable=io.StringIO):
            code = MODULE["start"]([sys.executable, "-c", "pass"], env, None, None, None, "42", True, None)
        self.assertEqual(code, 0)
        return made

    def test_off_by_default(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith("DROIDDECK_EXP_")}
        self.assertEqual(self.run_start(env), [])
        self.assertFalse(MODULE["exit_watch"]("7", {}))

    def test_on_it_runs_while_the_game_runs_and_stops_when_it_exits(self):
        env = dict(os.environ, DROIDDECK_EXP_WEBHELPER_TRIM="1")
        self.assertEqual(self.run_start(env), [["42", "made", "WebhelperStop", "started", "stopped"]])

    def test_on_it_makes_the_launch_wait_unless_the_exit_watch_is_switched_off(self):
        watch = MODULE["exit_watch"]
        self.assertTrue(watch("7", {"DROIDDECK_EXP_WEBHELPER_TRIM": "1"}))
        self.assertFalse(watch("7", {"DROIDDECK_EXP_WEBHELPER_TRIM": "1", "DROIDDECK_GAME_EXIT_WATCH": "0"}))

    def test_the_experiment_is_listed(self):
        self.assertIn("webhelper-trim", MODULE["EXPERIMENTS"])
        self.assertEqual(MODULE["experiment_name"]("webhelper-trim"), "DROIDDECK_EXP_WEBHELPER_TRIM")


class ReadingsTest(unittest.TestCase):
    def test_usage_sums_the_web_helper_processes_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)
            for pid, cmdline, status in (
                    ("10", b"/s/steamrtarm64/steamwebhelper\0-lang=en\0", "VmRSS:\t  100000 kB\nVmSwap:\t  300000 kB\n"),
                    ("11", b"/s/steamrtarm64/steamwebhelper\0--type=renderer\0", "VmRSS:\t   50000 kB\nVmSwap:\t  163000 kB\n"),
                    ("12", b"FEXInterpreter\0/s/steamrt64/steamwebhelper\0", "VmRSS:\t    1024 kB\n"),
                    ("13", b"/s/steamrtarm64/steam\0-gamepadui\0", "VmRSS:\t  400000 kB\nVmSwap:\t  9000 kB\n"),
                    ("14", b"/s/steamrtarm64/steamwebhelper\0", "State:\tZ (zombie)\n"),
                    ("self", b"steamwebhelper", "VmRSS:\t 1 kB\n")):
                (proc / pid).mkdir()
                (proc / pid / "cmdline").write_bytes(cmdline)
                (proc / pid / "status").write_text(status)
            self.assertEqual(MODULE["webhelper_usage"](str(proc)), (3, 151024, 463000))
            self.assertEqual(MODULE["webhelper_usage"](str(proc / "missing")), (0, 0, 0))

    def test_port_comes_from_the_session_file_then_the_environment(self):
        port = MODULE["webhelper_cdp_port"]
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "agent").mkdir()
            self.assertEqual(port({"BL_LAUNCH_DIR": tmp, "BL_CDP_PORT": "8080"}), 8080)
            (Path(tmp) / "agent" / "cdp-port").write_text("31337\n")
            self.assertEqual(port({"BL_LAUNCH_DIR": tmp, "BL_CDP_PORT": "8080"}), 31337)
        self.assertIsNone(port({}))
        self.assertIsNone(port({"BL_CDP_PORT": "70000"}))
        self.assertIsNone(port({"BL_CDP_PORT": "x"}))


class FakeCef(threading.Thread):
    """A DevTools endpoint: /json/list, and a WebSocket per target that answers each call (HeapProfiler.collectGarbage
    with an error until HeapProfiler.enable)."""

    def __init__(self, targets):
        super().__init__(daemon=True)
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(8)
        self.port = self.server.getsockname()[1]
        self.targets = [dict(t, webSocketDebuggerUrl="ws://127.0.0.1:%d/devtools/page/%s" % (self.port, t["id"]))
                        for t in targets]
        self.calls = []

    def run(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def close(self):
        self.server.close()

    @staticmethod
    def read(conn, n):
        data = b""
        while len(data) < n:
            chunk = conn.recv(n - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        return data

    def serve(self, conn):
        with conn:
            head = b""
            while b"\r\n\r\n" not in head:
                head += conn.recv(4096)
            request = head.decode()
            if request.startswith("GET /json"):
                body = json.dumps(self.targets).encode()
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n"
                             % len(body) + body)
                return
            target = request.split(" ", 2)[1].rsplit("/", 1)[-1]
            key = [l.split(":", 1)[1].strip() for l in request.split("\r\n") if l.lower().startswith("sec-websocket-key")][0]
            accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
            conn.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                         b"Sec-WebSocket-Accept: " + accept + b"\r\n\r\n")
            enabled = False
            try:
                while True:
                    b0, b1 = self.read(conn, 2)
                    n = b1 & 0x7F
                    if n == 126:
                        n = struct.unpack(">H", self.read(conn, 2))[0]
                    mask = self.read(conn, 4)
                    data = bytes(b ^ mask[i % 4] for i, b in enumerate(self.read(conn, n)))
                    if b0 & 0x0F == 8:
                        return
                    message = json.loads(data)
                    self.calls.append((target, message["method"], message.get("params")))
                    if message["method"] == "HeapProfiler.enable":
                        enabled = True
                    if message["method"] == "HeapProfiler.collectGarbage" and not enabled:
                        reply = {"id": message["id"], "error": {"code": -32000, "message": "HeapProfiler is not enabled"}}
                    else:
                        reply = {"id": message["id"], "result": {}}
                    for out in ({"method": "Inspector.event", "params": {}}, reply):
                        payload = json.dumps(out).encode()
                        conn.sendall(bytes([0x81, len(payload)]) + payload if len(payload) < 126 else
                                     bytes([0x81, 126]) + struct.pack(">H", len(payload)) + payload)
            except (EOFError, OSError):
                return


class SteamDevToolsTest(unittest.TestCase):
    def test_pressure_then_gc_on_each_target_through_steam_compat_client(self):
        cef = FakeCef([{"id": "A", "type": "page", "title": "SharedJSContext"},
                       {"id": "B", "type": "worker", "title": "w"},
                       {"id": "C", "type": "browser", "title": "b"}])
        cef.start()
        self.addCleanup(cef.close)
        err = io.StringIO()
        with mock.patch.object(sys, "stderr", err):
            watch = MODULE["WebhelperTrim"](APP, port=cef.port, usage=Usage((2, 2048, 4096)), first=0, every=60,
                                            settle=0).start()
            self.addCleanup(watch.stop)
            self.assertTrue(wait_for(lambda: lines(err, LINE) or lines(err, OFF)))
            watch.stop()
        self.assertEqual(lines(err, OFF), [])
        (line,) = lines(err, LINE)
        self.assertEqual(line.group(3, 4, 5), ("2", "2", "0"))
        for target in ("A", "B"):
            self.assertEqual([(m, p) for t, m, p in cef.calls if t == target], [
                ("Memory.simulatePressureNotification", {"level": "critical"}),
                ("HeapProfiler.collectGarbage", {}),
                ("HeapProfiler.enable", {}),
                ("HeapProfiler.collectGarbage", {}),
                ("HeapProfiler.disable", {}),
            ])
        self.assertNotIn("C", [t for t, _, _ in cef.calls])

    def test_a_closed_port_is_one_line(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        err = io.StringIO()
        with mock.patch.object(sys, "stderr", err):
            watch = MODULE["WebhelperTrim"](APP, port=port, usage=Usage((0, 0, 0)), first=0, every=0.01, settle=0).start()
            watch.thread.join(10)
        self.assertEqual([m.group(2) for m in lines(err, OFF)], ["devtools_unreachable"])
        self.assertEqual(lines(err, LINE), [])


STOP = re.compile(r"DROIDDECK_TIMELINE event=game_webhelper_stop app_id=(\S+) procs=(\d+) browsers=(\d+) termed=(\d+) "
                  r"left=(\d+) browser_left=(\d+) killed=(\d+) rss_mb_before=(\d+) swap_mb_before=(\d+) "
                  r"rss_mb_after=(\d+) swap_mb_after=(\d+) took_ms=(\d+) wall_ms=(\d+)$")
SKIPPED = re.compile(r"DROIDDECK_TIMELINE event=game_webhelper_stop_skipped app_id=(\S+) reason=(\S+) (.*) wall_ms=(\d+)$")
BACK = re.compile(r"DROIDDECK_TIMELINE event=game_webhelper_back app_id=(\S+) procs=(\d+) rss_mb=(\d+) swap_mb=(\d+) "
                  r"after_s=(\d+) game_running=(\d) wall_ms=(\d+)$")


class FakeTree:
    """The web helper's processes as webhelper_processes lists them. SIGTERM to a browser ends it and its children
    unless it ignores SIGTERM; children can be left behind; the client can start a new helper on its own."""

    def __init__(self, procs, ignore_term=False, orphans=(), respawn=None):
        self.procs = list(procs)
        self.ignore_term = ignore_term
        self.orphans = set(orphans)
        self.respawn = respawn
        self.signals = []

    def __call__(self):
        return list(self.procs)

    def kill(self, pid, signum):
        self.signals.append((pid, signum))
        if signum == signal.SIGTERM and not self.ignore_term:
            self.procs = [p for p in self.procs if p.pid in self.orphans]
            if self.respawn:
                self.procs += self.respawn
        elif signum == signal.SIGKILL:
            self.procs = [p for p in self.procs if p.pid != pid]


def web(pid, browser=False, rss_mb=100, swap_mb=50, start=None):
    return MODULE["WebProc"](pid, start if start is not None else pid * 10, browser, rss_mb * 1024, swap_mb * 1024)


HELPER = [web(200, browser=True, rss_mb=150, swap_mb=60), web(201, rss_mb=300, swap_mb=40), web(202, rss_mb=166, swap_mb=0)]


class WebhelperStopTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(sys, "stderr", new_callable=io.StringIO)
        self.err = patcher.start()
        self.addCleanup(patcher.stop)

    def run_stop(self, tree, done=None, min_mb=256, grace=0.2, settle=0):
        done = done or threading.Event()
        stop = MODULE["WebhelperStop"](APP, procs=tree, kill=tree.kill, after=0, min_mb=min_mb, grace=grace,
                                       poll=0.01, settle=settle)
        worker = threading.Thread(target=stop.run, args=(done,), daemon=True)
        worker.start()
        return stop, done, worker

    def test_terms_only_the_browser_and_reports_the_fresh_helper(self):
        fresh = [web(300, browser=True, rss_mb=90, swap_mb=0), web(301, rss_mb=110, swap_mb=0)]
        tree = FakeTree(HELPER, respawn=fresh)
        stop, done, worker = self.run_stop(tree)
        worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(tree.signals, [(200, signal.SIGTERM)])
        (line,) = lines(self.err, STOP)
        self.assertEqual(line.group(2, 3, 4, 5, 6, 7), ("3", "1", "1", "0", "0", "0"))
        self.assertEqual(line.group(8, 9), ("616", "100"))
        (back,) = lines(self.err, BACK)
        self.assertEqual(back.group(2, 3, 4, 6), ("2", "200", "0", "1"))
        stop.finish()
        self.assertEqual(len(lines(self.err, BACK)), 1)

    def test_children_left_by_a_gone_browser_are_killed(self):
        tree = FakeTree(HELPER, orphans={202})
        stop, done, worker = self.run_stop(tree)
        self.assertTrue(wait_for(lambda: lines(self.err, STOP)))
        self.assertEqual(tree.signals, [(200, signal.SIGTERM), (202, signal.SIGKILL)])
        self.assertEqual(lines(self.err, STOP)[0].group(5, 7), ("0", "1"))
        done.set()
        stop.finish()
        worker.join(5)
        (back,) = lines(self.err, BACK)
        self.assertEqual(back.group(2, 6), ("0", "0"))

    def test_a_browser_that_ignores_sigterm_is_left_alone(self):
        tree = FakeTree(HELPER, ignore_term=True)
        stop, done, worker = self.run_stop(tree, grace=0.1)
        worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(tree.signals, [(200, signal.SIGTERM)])
        self.assertEqual(lines(self.err, STOP)[0].group(5, 6, 7), ("3", "1", "0"))
        stop.finish()
        self.assertEqual(lines(self.err, BACK), [])

    def test_a_small_helper_or_none_is_not_touched(self):
        for procs, reason in (([web(200, browser=True, rss_mb=100, swap_mb=100)], "small"), ([], "no_helper"),
                              ([web(201, rss_mb=900)], "no_browser")):
            self.err.truncate(0)
            self.err.seek(0)
            tree = FakeTree(procs)
            stop, done, worker = self.run_stop(tree)
            worker.join(5)
            stop.finish()
            self.assertEqual(tree.signals, [])
            self.assertEqual([m.group(2) for m in lines(self.err, SKIPPED)], [reason])
            self.assertEqual(lines(self.err, STOP) + lines(self.err, BACK), [])

    def test_nothing_is_signalled_once_the_game_has_exited(self):
        tree = FakeTree(HELPER)
        done = threading.Event()
        done.set()
        stop = MODULE["WebhelperStop"](APP, procs=tree, kill=tree.kill, after=0)
        stop.run(done)
        stop.finish()
        self.assertEqual(tree.signals, [])
        self.assertEqual(self.err.getvalue(), "")

    def test_a_reused_pid_is_never_signalled(self):
        stop = MODULE["WebhelperStop"](APP, kill=lambda *a: self.fail("signalled %r" % (a,)))
        self.assertFalse(stop.signal(web(200, browser=True), signal.SIGTERM, [web(200, browser=True, start=1)]))
        self.assertFalse(stop.signal(web(os.getpid(), browser=True), signal.SIGTERM, [web(os.getpid(), browser=True)]))
        self.assertFalse(stop.signal(web(1, browser=True), signal.SIGTERM, [web(1, browser=True)]))

    def test_a_failing_listing_never_leaves_the_watch(self):
        def broken():
            raise ValueError("bad /proc")
        stop = MODULE["WebhelperStop"](APP, procs=broken, kill=lambda *a: self.fail("signalled"), after=0)
        stop.run(threading.Event())
        self.assertEqual([m.group(2) for m in lines(self.err, SKIPPED)], ["error"])


class TrimThenStopTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(sys, "stderr", new_callable=io.StringIO)
        self.err = patcher.start()
        self.addCleanup(patcher.stop)

    def test_the_stop_follows_the_trim_rounds_and_also_runs_without_devtools(self):
        for devtools, port in ((FakeDevTools(), 8080), (FakeDevTools(unreachable=ConnectionRefusedError(111, "x")), 8080)):
            self.err.truncate(0)
            self.err.seek(0)
            tree = FakeTree(HELPER, respawn=[web(300, browser=True)])
            stopper = MODULE["WebhelperStop"](APP, procs=tree, kill=tree.kill, after=0, grace=0.2, poll=0.01, settle=0)
            watch = MODULE["WebhelperTrim"](APP, port=port, devtools=devtools, usage=Usage((3, 0, 0)), first=0,
                                            every=0.01, settle=0, stopper=stopper).start()
            watch.thread.join(5)
            self.assertFalse(watch.thread.is_alive())
            self.assertEqual(tree.signals, [(200, signal.SIGTERM)])
            self.assertEqual(len(lines(self.err, STOP)), 1)
            self.assertEqual(len(lines(self.err, BACK)), 1)
            watch.stop()
            self.assertEqual(len(lines(self.err, BACK)), 1)

    def test_a_game_that_exits_during_the_trim_rounds_stops_nothing(self):
        tree = FakeTree(HELPER)
        stopper = MODULE["WebhelperStop"](APP, procs=tree, kill=tree.kill, after=0)
        watch = MODULE["WebhelperTrim"](APP, port=8080, devtools=FakeDevTools(), usage=Usage((3, 0, 0)), first=0,
                                        every=30, settle=0, stopper=stopper).start()
        self.assertTrue(wait_for(lambda: lines(self.err, LINE)))
        watch.stop()
        watch.thread.join(5)
        self.assertFalse(watch.thread.is_alive())
        self.assertEqual(tree.signals, [])
        self.assertEqual(lines(self.err, STOP) + lines(self.err, BACK) + lines(self.err, SKIPPED), [])


class WebhelperProcessesTest(unittest.TestCase):
    def test_lists_only_steamwebhelper_programs_with_their_start_time_and_role(self):
        def stat(pid, state, start):
            return "%s (weird) name) %s 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 20 0 30 0 %d 0 0\n" % (pid, state, start)
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)
            entries = (
                ("10", b"/s/steamrtarm64/steamwebhelper\0-lang=en\0", "S", 500, "VmRSS:\t  100000 kB\nVmSwap:\t  300000 kB\n"),
                ("11", b"/s/steamrtarm64/steamwebhelper\0--type=renderer\0", "S", 501, "VmRSS:\t   50000 kB\n"),
                ("12", b"FEXInterpreter\0/s/steamrt64/steamwebhelper\0--type=gpu-process\0", "S", 502, "VmRSS:\t 1024 kB\n"),
                ("13", b"/s/steamrtarm64/steam\0-gamepadui\0", "S", 503, "VmRSS:\t  400000 kB\n"),
                ("14", b"/s/steamrtarm64/steamwebhelper\0", "Z", 504, ""),
                ("15", b"/bin/bash\0/s/steamrtarm64/steamwebhelper.sh\0", "S", 505, "VmRSS:\t 10 kB\n"),
                ("16", b"/g/SlayTheSpire2\0--steamwebhelper\0", "S", 506, "VmRSS:\t 9000000 kB\n"),
                ("17", b"/s/steamrtarm64/steamwebhelper.sh\0", "S", 507, "VmRSS:\t 10 kB\n"),
                (str(os.getpid()), b"/x/steamwebhelper\0", "S", 508, "VmRSS:\t 1 kB\n"),
                ("self", b"/x/steamwebhelper\0", "S", 509, "VmRSS:\t 1 kB\n"),
            )
            for pid, cmdline, state, start, status in entries:
                (proc / pid).mkdir()
                (proc / pid / "cmdline").write_bytes(cmdline)
                (proc / pid / "stat").write_text(stat(pid, state, start))
                (proc / pid / "status").write_text(status)
            found = sorted(MODULE["webhelper_processes"](str(proc)))
            WebProc = MODULE["WebProc"]
            self.assertEqual(found, [WebProc(10, 500, True, 100000, 300000), WebProc(11, 501, False, 50000, 0),
                                     WebProc(12, 502, False, 1024, 0)])
            self.assertEqual(MODULE["webhelper_processes"](str(proc / "missing")), [])


if __name__ == "__main__":
    unittest.main()
