import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import runpy
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
            def __init__(self, app_id):
                made.append([app_id, "made"])

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
        self.assertEqual(self.run_start(env), [["42", "made", "started", "stopped"]])

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


if __name__ == "__main__":
    unittest.main()
