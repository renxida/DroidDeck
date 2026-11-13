import os
from pathlib import Path
import runpy
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
TRACE = runpy.run_path(str(BIN / "droiddeck-dotnet-trace"))
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))
FREQ = 1_000_000_000
SYNC = 5_000_000_000


def utf16z(text):
    return text.encode("utf-16-le") + b"\0\0"


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


class Writer:
    """Writes the subset of NetTrace V4 that the runtime produces: Trace object, metadata and event blocks
    with compressed event headers."""

    def __init__(self):
        self.data = bytearray(b"Nettrace" + struct.pack("<i", 20) + b"!FastSerialization.1")
        self.data += b"\x05\x05\x01" + struct.pack("<ii", 4, 4) + struct.pack("<i", 5) + b"Trace\x06"
        self.data += struct.pack("<8HqqIIII", 2026, 11, 4, 13, 3, 45, 0, 0, SYNC, FREQ, 8, 42, 8, 1000) + b"\x06"

    def block(self, name, events):
        body = bytearray(struct.pack("<hh", 20, 1) + b"\0" * 16)
        timestamp = 0
        for meta_id, ts, payload in events:
            body += bytes([0x01 | 0x80]) + varint(meta_id) + varint((ts - timestamp) & 0xFFFFFFFFFFFFFFFF) + varint(len(payload)) + payload
            timestamp = ts
        self.data += b"\x05\x05\x01" + struct.pack("<ii", 2, 2) + struct.pack("<i", len(name)) + name.encode() + b"\x06"
        self.data += struct.pack("<i", len(body))
        self.data += b"\0" * ((-len(self.data)) % 4)
        self.data += body + b"\x06"

    def metadata(self, entries):
        events = []
        for meta_id, provider, event_id, name, fields in entries:
            payload = struct.pack("<i", meta_id) + utf16z(provider) + struct.pack("<i", event_id) + utf16z(name) + \
                struct.pack("<qii", 1, 0, 4) + (fields or b"")
            events.append((0, 0, payload))
        self.block("MetadataBlock", events)

    def end(self):
        self.data += b"\x01"
        return bytes(self.data)


def counter_fields():
    def field(code, name, nested=b""):
        return struct.pack("<i", code) + nested + utf16z(name)

    def fields(*items):
        return struct.pack("<i", len(items)) + b"".join(items)
    inner = fields(field(18, "Name"), field(14, "Mean"), field(18, "CounterType"))
    return fields(field(1, "", fields(field(1, "Payload", inner))))


def ms(value):
    return SYNC + int(value * FREQ / 1000)


def gc_events(n, start_ms, gen, suspend_ms, pause_ms, gc_type=0):
    return [
        (9, ms(start_ms), struct.pack("<IIH", 1, n, 0)),
        (8, ms(start_ms + suspend_ms), struct.pack("<H", 0)),
        (1, ms(start_ms + suspend_ms + 0.01), struct.pack("<IIIIHQ", n, gen, 0, gc_type, 0, 0)),
        (205, ms(start_ms + suspend_ms + 0.02), struct.pack("<QiIIIIHII", 0, 1, gen, 0, 0, 0x2, 0, 0, 0)),
        (2, ms(start_ms + pause_ms - 0.03), struct.pack("<IIH", n, gen, 0)),
        (4, ms(start_ms + pause_ms - 0.02), struct.pack("<8Q", 4 << 20, 1 << 20, 2 << 20, 0, 30 << 20, 0, 1 << 20, 0)),
        (3, ms(start_ms + pause_ms), struct.pack("<H", 0)),
    ]


def counter(name, value, at_ms):
    return (100, ms(at_ms), utf16z(name) + struct.pack("<d", value) + utf16z("Mean"))


def sample_trace():
    w = Writer()
    runtime = "Microsoft-Windows-DotNETRuntime"
    w.metadata([(i, runtime, i, "", None) for i in (1, 2, 3, 4, 8, 9)] + [(205, runtime, 205, "", None)] +
               [(100, "System.Runtime", 153, "EventCounters", counter_fields())])
    w.block("EventBlock", gc_events(1, 100, 0, 2.5, 10) + gc_events(2, 900, 2, 4.0, 120) +
            [counter("time-in-gc", 13.0, 1000), counter("gc-heap-size", 37.5, 1001)])
    w.block("EventBlock", gc_events(3, 61_000, 1, 1.0, 30) + [counter("time-in-gc", 2.0, 61_500)])
    return w.end()


class ParseTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "dotnet-42.nettrace"
        self.path.write_bytes(sample_trace())

    def tearDown(self):
        self.dir.cleanup()

    def test_gcs_get_pause_suspension_heap_and_compaction(self):
        summary = TRACE["summarise"](self.path)
        self.assertEqual([1, 2, 3], [g["n"] for g in summary.gcs])
        first = summary.gcs[0]
        self.assertEqual((0, "blocking", "alloc_small", True), (first["gen"], first["type"], first["reason"], first["compacting"]))
        self.assertAlmostEqual(10.0, first["pause_ms"], places=3)
        self.assertAlmostEqual(2.5, first["suspend_ms"], places=3)
        self.assertAlmostEqual(30.0, first["gen2_mb"])
        self.assertAlmostEqual(120.0, summary.gcs[1]["pause_ms"], places=3)
        self.assertEqual({1: {"time-in-gc": 13.0, "gc-heap-size": 37.5}, 61: {"time-in-gc": 2.0}}, summary.counters)

    def test_summary_is_per_minute(self):
        text = TRACE["summary_text"](TRACE["summarise"](self.path))
        rows = [line.split(",") for line in text.splitlines()[3:]]
        self.assertEqual(["0", "2", "1", "0", "1"], rows[0][:5])
        self.assertEqual("130.00", rows[0][7])
        self.assertEqual(["1", "1", "0", "1", "0"], rows[1][:5])

    def test_a_growing_trace_is_read_block_by_block(self):
        data = self.path.read_bytes()
        stream, summary = TRACE["TraceStream"](self.path), TRACE["Summary"]()
        cut = data.index(b"EventBlock", data.index(b"EventBlock") + 1) - 15
        self.path.write_bytes(data[:cut])
        summary.add_all(stream, stream.read())
        self.assertEqual(2, len(summary.gcs))
        summary.add_all(stream, stream.read())
        self.assertEqual(2, len(summary.gcs))
        self.path.write_bytes(data)
        summary.add_all(stream, stream.read())
        self.assertEqual([1, 2, 3], [g["n"] for g in summary.gcs])

    def test_watch_writes_the_tables_and_stops_with_the_game(self):
        out = Path(self.dir.name)
        self.assertEqual(0, TRACE["watch"](out, out, 1, pid=None))
        gc = (out / "dotnet-gc.txt").read_text().splitlines()
        self.assertEqual(4, len(gc))
        self.assertTrue(gc[0].startswith("t_ms,n,gen,type,reason,compacting,pause_ms,suspend_ms"))
        self.assertIn("time-in-gc", (out / "dotnet-counters.txt").read_text().splitlines()[0])
        self.assertFalse(list(out.glob("*.tmp")))

    def test_not_a_trace(self):
        self.path.write_bytes(b"hello world, not a trace")
        with self.assertRaises(ValueError):
            TRACE["summarise"](self.path)

    def test_command_line(self):
        result = subprocess.run([sys.executable, str(BIN / "droiddeck-dotnet-trace"), str(self.path), "--print"],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("minute,gcs,gen0", result.stdout)
        self.assertIn("3 GCs", result.stderr)


class GameEnvTest(unittest.TestCase):
    def game(self, experiments, files=("coreclr.dll",)):
        env = {GAME_ENV["experiment_name"](name): "1" for name in experiments}
        return mock.Mock(files=set(files), env=env)

    def test_gen0_and_stock_gc_only_for_dotnet_games(self):
        fix = GAME_ENV["dotnet_runtime_experiments"]
        self.assertIsNone(fix(self.game(["dotnet-gen0"], files=("unityplayer.dll",))))
        self.assertIsNone(fix(self.game([])))
        values, args, note = fix(self.game(["dotnet-gen0"]))
        self.assertEqual({"DOTNET_GCgen0size": "4000000"}, values)
        values, args, note = fix(self.game(["dotnet-gc-stock", "dotnet-gen0"]))
        self.assertEqual({"DOTNET_GCgen0size": "4000000", "DOTNET_gcConcurrent": "1", "DOTNET_GCConserveMemory": "0"}, values)

    def test_stock_gc_wins_over_fex_fast(self):
        env, extra, notes = None, None, None
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "coreclr.dll").write_bytes(b"")
            exe = str(Path(folder, "game.exe"))
            on = {GAME_ENV["experiment_name"](n): "1" for n in ("fex-fast", "dotnet-gc-stock")}
            env, extra, notes = GAME_ENV["engine_fixes"](exe, [], on)
        self.assertEqual("1", env["DOTNET_gcConcurrent"])
        self.assertEqual("0", env["FEX_VECTORTSOENABLED"])
        self.assertEqual("0", env["DOTNET_GCConserveMemory"])
        heap, note = GAME_ENV["dotnet_heap"](env, "2868840")
        self.assertEqual("0", heap["DOTNET_GCConserveMemory"])

    def test_trace_needs_the_experiment_a_dotnet_game_and_the_log_folder(self):
        with tempfile.TemporaryDirectory() as folder:
            game = Path(folder, "game")
            game.mkdir()
            (game / "coreclr.dll").write_bytes(b"")
            logs = Path(folder, "logs")
            logs.mkdir()
            command = ["proton", "waitforexitandrun", str(game / "game.exe")]
            started = []
            popen = lambda args, **kw: started.append(args)
            env = {"DROIDDECK_GAME_LOG_DIR": str(logs)}
            self.assertEqual((env, None), GAME_ENV["dotnet_trace"](command, env, popen))
            env[GAME_ENV["experiment_name"]("dotnet-trace")] = "1"
            result, note = GAME_ENV["dotnet_trace"](command, env, popen)
            self.assertEqual("1", result["DOTNET_EnableEventPipe"])
            self.assertEqual("1", result["DOTNET_EventPipeOutputStreaming"])
            self.assertEqual("0", result["DOTNET_EventPipeRundown"])
            self.assertEqual("Z:" + str(logs).replace("/", "\\") + "\\dotnet-{pid}.nettrace", result["DOTNET_EventPipeOutputPath"])
            self.assertTrue(result["DOTNET_EventPipeConfig"].startswith("Microsoft-Windows-DotNETRuntime:0x400000001:4,"))
            self.assertEqual([str(logs), "--watch", "60", "--pid", str(os.getpid())], started[0][2:])
            self.assertIn("dotnet-trace:", note)
            without_folder = dict(env)
            del without_folder["DROIDDECK_GAME_LOG_DIR"]
            same, note = GAME_ENV["dotnet_trace"](command, without_folder, popen)
            self.assertNotIn("DOTNET_EnableEventPipe", same)
            self.assertIn("no game log folder", note)
            (game / "coreclr.dll").unlink()
            self.assertEqual((env, None), GAME_ENV["dotnet_trace"](command, env, popen))
            self.assertEqual(1, len(started))


if __name__ == "__main__":
    unittest.main()
