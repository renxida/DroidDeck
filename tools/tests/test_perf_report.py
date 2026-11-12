import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import perf_report  # noqa: E402

T0 = 1762941600000  # 2025-11-12 10:00:00 UTC


def events(*rows):
    return "".join(json.dumps({"t": T0 + int(at * 1000), "event": name, "sessionId": "s"}) + "\n" for name, at in rows)


def frames(game_ms, slow=(), steam_frames=30):
    lines = [f"# frames t0_wall_ms={T0} t0_mono_ns=1 source=gamescope", "t_ms,frametime_us",
             "# t_ms=1000 engine=GAMESCOPE pid=10 steam=1 output=1920x1080@60000mHz"]
    t = 1000
    for _ in range(steam_frames):
        t += 100
        lines.append(f"{t},100000")
    t += 1000
    lines.append(f"# t_ms={t} engine=DXVK pid=42 steam=0 output=1920x1080@60000mHz")
    for i, ft in enumerate(game_ms):
        t += round(ft)
        lines.append(f"{t},{round(ft * 1000)}")
    for ft in slow:
        t += ft
        lines.append(f"{t},{ft * 1000}")
    return "\n".join(lines) + "\n"


CLOCKS = """== clocks: Google Pixel 10 (tensor), every 2s
start: wall_ms={start} tz_offset_min=-480
cpu policies: policy0 0-3, policy4 4-6, policy7 7
gpu clock from: nothing readable
02:00:01 p0 1800/1800 p4 2400/2400 p7 3000/3000 | thermal 0 headroom 0.40 | battery 30.0 C
02:00:03 p0 1800/1800 p4 2400/2400 p7 3000/3000 | thermal 0 headroom 0.60 | battery 31.5 C
02:00:05 p0 1800/1800 p4 2400/2400 p7 2600/2600(of 3000) | thermal 1 headroom 0.90 | battery 33.0 C
02:00:07 p0 1800/1800 p4 2400/2400 p7 2200/2200(of 3000) | thermal 2 headroom 1.00 | battery 34.5 C
""".format(start=T0 + 4000)

SESSION_LOG = """starting
== STEP 10:00:06.000 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)
== STEP 10:00:08.500 Steam client ready
== STEP 10:00:09.000 starting the Steam client (gamepadui UI)
DROIDDECK_TIMELINE event=game_exec app_id=2868840 wall_ms={exec}
""".format(exec=T0 + 40_000)

EVENTS = events(
    ("app.process_start", -3), ("frontend.first_frame", -2), ("session.created", 0), ("service.started", 1),
    ("compositor.starting", 1.5), ("compositor.started", 2), ("guest.starting", 2), ("guest.started", 5),
    ("steam.ui_first_frame", 20), ("steam.signed_in", 25), ("steam.game_launch_requested", 30),
    ("game.first_frame", 50),
)

BUILD = "build_label: remote clean · {branch} at 1a2b3c4\nbranch: {branch}\ncommit: 1a2b3c4d\nbuild_slug: {slug}\n" \
        "device: Google Pixel 10 (frankel, tensor)\ngpu: PowerVR D-Series DXT-48-1536 (PowerVR)\n"


class PerfReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def share_zips(self, name="2025-11-12-01-steam", branch="main", game=None):
        game = game if game is not None else [16.667] * 600
        session = self.root / f"DroidDeck-{name}-session.zip"
        with zipfile.ZipFile(session, "w") as z:
            z.writestr(f"{name}/events.jsonl", EVENTS)
            z.writestr(f"{name}/session.log", SESSION_LOG)
            z.writestr(f"{name}/frames.csv", frames(game, slow=(50,) * 10))
            z.writestr(f"{name}/entry-0123456789abcdef.log", CLOCKS)
            z.writestr(f"{name}/build.txt", BUILD.format(branch=branch, slug=branch.replace("/", "-") + "-1a2b3c4"))
            z.writestr(f"{name}/memory.csv", "t_wall_ms,used_mb,available_mb,swap_used_mb,game_mb,droiddeck_mb,app_mb,gpu_mb\n"
                                              f"{T0},6000,5000,100,2000,800,300,\n{T0 + 5000},7000,4000,200,2500,900,320,\n")
            z.writestr(f"{name}/steam/connection_log.txt", "ignored")
            z.writestr("diagnostics-manifest.json", "{}")
        graphics = self.root / f"DroidDeck-{name}-graphics.zip"
        with zipfile.ZipFile(graphics, "w") as z:
            z.writestr(f"{name}/graphics-report.txt", "== graphics report\npath: system Vulkan (PowerVR)\ndriver mode: auto\n")
        return session, graphics

    def upload_zip(self, path, session, branch, game):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("upload.txt", f"session: {session}\nkind: final\n")
            z.writestr("events.jsonl", EVENTS)
            z.writestr("frames.csv", frames(game))
            z.writestr("clocks.log", CLOCKS)
            z.writestr("build.txt", BUILD.format(branch=branch, slug=branch.replace("/", "-") + "-1a2b3c4"))
        return path

    def run_report(self, *inputs):
        out = io.StringIO()
        with redirect_stdout(out):
            code = perf_report.main([str(i) for i in inputs])
        self.assertEqual(code, 0)
        return out.getvalue()

    def test_share_bundle_zips_of_one_session_merge_into_one_report(self):
        session, graphics = self.share_zips()
        [s] = [perf_report.summarize(x) for x in perf_report.load([session, graphics])]
        self.assertEqual(s["session"], "2025-11-12-01-steam")
        self.assertEqual(s["build"], "remote clean · main at 1a2b3c4")
        self.assertEqual(s["graphics_path"], "system Vulkan (PowerVR)")
        self.assertIn("clocks.log", s["files"])
        phases = dict(s["phases"])
        self.assertEqual(phases["app start -> front end drawn"], 1.0)
        self.assertEqual(phases["guest start"], 3.0)
        self.assertEqual(phases["Steam client check"], 2.5)
        self.assertEqual(phases["Steam start -> first Steam frame"], 11.0)
        self.assertEqual(phases["Steam start -> signed in"], 16.0)
        self.assertEqual(phases["game launch request -> game exec"], 10.0)
        self.assertEqual(phases["game exec -> first game frame"], 10.0)
        self.assertIsNone(phases["runtime install"])

    def test_frame_stats_cover_only_the_game_and_count_slow_frames(self):
        session, _ = self.share_zips()
        [s] = [perf_report.summarize(x) for x in perf_report.load([session])]
        fr = s["frames"]
        self.assertEqual(fr["frames"], 610)
        self.assertEqual(fr["frames_total"], 640)
        self.assertEqual(fr["engines"], ["DXVK"])
        self.assertAlmostEqual(fr["median_ms"], 16.667, places=3)
        self.assertAlmostEqual(fr["p99_ms"], 50.0)
        self.assertEqual(fr["spikes_2x_median"], 10)
        self.assertAlmostEqual(fr["avg_fps"], 610 / (600 * 0.016667 + 10 * 0.05), places=2)
        self.assertAlmostEqual(fr["low1_fps"], 20.0)

    def test_a_game_without_an_engine_name_still_counts_frames_without_steam_focus(self):
        text = frames([16.667] * 100).replace("engine=DXVK", "engine=GAMESCOPE")
        fr = perf_report.parse_frames(text)
        self.assertEqual(fr["frames"], 100)
        self.assertEqual(fr["engines"], [])

    def test_clocks_give_thermal_caps_and_battery(self):
        session, _ = self.share_zips()
        [s] = [perf_report.summarize(x) for x in perf_report.load([session])]
        ck = s["clocks"]
        self.assertEqual(ck["samples"], 4)
        self.assertEqual(ck["thermal_max"], 2)
        self.assertEqual(ck["thermal_first_s"], 4)
        self.assertEqual(ck["first_cap"], (4, 7, 2600, 3000))
        self.assertEqual([mx for _, mx, _ in ck["caps"][7]], [3000, 2600, 2200])
        self.assertEqual((ck["battery_start"], ck["battery_max"]), (30.0, 34.5))
        self.assertEqual(ck["offset_s"], 4.0)
        self.assertEqual(s["memory"]["game_mb_max"], 2500)
        self.assertEqual(s["memory"]["available_mb_min"], 4000)
        text = perf_report.report(s)
        self.assertIn("first CPU cap: +4s p7 2600/3000 MHz", text)
        self.assertIn("p7 max: 3000@+0s -> 2600@+4s -> 2200@+6s", text)

    def test_a_directory_of_upload_zips_compares_two_builds(self):
        runs = self.root / "runs"
        self.upload_zip(runs / "main-1a2b3c4/Google-Pixel-10/a.zip", "2025-11-12-01-steam", "main", [16.667] * 600)
        self.upload_zip(runs / "perf-all-1a2b3c4/Google-Pixel-10/b.zip", "2025-11-12-02-steam", "perf/all", [8.333] * 1200)
        text = self.run_report(runs)
        self.assertIn("== comparison", text)
        self.assertIn("perf-all-1a2b3c4", text)
        avg = next(line for line in text.splitlines() if line.strip().startswith("avg fps"))
        self.assertIn("better", avg)
        self.assertIn("+60.0", avg)

    def test_missing_files_are_tolerated(self):
        only = self.root / "2025-11-12-03-steam"
        only.mkdir()
        (only / "events.jsonl").write_text(events(("session.created", 0), ("guest.started", 4)) + "not json\n")
        text = self.run_report(only)
        self.assertIn("== 2025-11-12-03-steam", text)
        self.assertIn("guest.started", text)
        empty = self.root / "empty.zip"
        with zipfile.ZipFile(empty, "w") as z:
            z.writestr("readme.txt", "nothing")
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(perf_report.main([str(empty)]), 1)

    def test_json_output_is_machine_readable(self):
        session, _ = self.share_zips()
        out = io.StringIO()
        with redirect_stdout(out):
            perf_report.main([str(session), "--json"])
        data = json.loads(out.getvalue())
        self.assertEqual(data[0]["frames"]["frames"], 610)


if __name__ == "__main__":
    unittest.main()
