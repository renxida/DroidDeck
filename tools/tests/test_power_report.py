import io
import json
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import power_report  # noqa: E402

T0 = 1_794_559_000_000
HEADER = ("t_wall_ms,current_ua,charge_uah,voltage_mv,power_mw,level_pct,status,plugged,temp_c,"
          "thermal_status,headroom,brightness,auto_brightness,screen_on,game")


def power_row(t, ua, game=1, plugged="none", charge=4_000_000, level=80, mv=4000):
    mw = abs(ua) * mv / 1e6 if plugged == "none" else -ua * mv / 1e6
    status = "discharging" if plugged == "none" else "charging"
    return f"{t},{ua},{charge},{mv},{mw:.0f},{level},{status},{plugged},36.5,0,0.60,120,1,1,{game}"


def frames_csv(seconds, frametime_us=16_667):
    lines = [f"# frames t0_wall_ms={T0} t0_mono_ns=1 source=gamescope", "t_ms,frametime_us",
             "# t_ms=0 engine=GAMESCOPE pid=10 steam=1 output=1920x1080@60000mHz",
             "100,16667",
             "# t_ms=1000 engine=GODOT pid=42 steam=0 output=1920x1080@60000mHz"]
    t = 1000
    while t < 1000 + seconds * 1000:
        t += frametime_us // 1000
        lines.append(f"{t},{frametime_us}")
    return "\n".join(lines) + "\n"


def events(on):
    return "\n".join(json.dumps(e) for e in (
        {"t": T0, "event": "session.created", "sessionId": "s"},
        {"t": T0 + 500, "event": "session.experiments", "sessionId": "s", "on": on},
        {"t": T0 + 900, "event": "game.experiments", "sessionId": "s", "on": on},
        {"t": T0 + 1000, "event": "game.first_frame", "sessionId": "s", "engine": "GODOT", "pid": 42},
    )) + "\n"


def bundle(root, build, start, session, files):
    path = Path(root) / "runs" / build / "Google-Pixel-10" / f"{start}.zip"
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("upload.txt", f"session: {session}\nkind: final\n")
        z.writestr("build.txt", f"build_label: test\nbuild_slug: {build}\n")
        for name, text in files.items():
            z.writestr(name, text)
    return path


class PowerStatsTest(unittest.TestCase):
    def rows(self, lines):
        return power_report.parse_power_rows(HEADER + "\n" + "\n".join(lines) + "\n")

    def test_game_phase_counts_unplugged_game_intervals_only(self):
        rows = self.rows([
            power_row(T0, -1_000_000, game=0),
            power_row(T0 + 5_000, -1_000_000, game=0),
            power_row(T0 + 10_000, -2_000_000, charge=4_000_000),
            power_row(T0 + 15_000, -2_000_000, charge=3_997_000),
            power_row(T0 + 20_000, -3_000_000, charge=3_993_000, level=79),
            power_row(T0 + 25_000, 1_000_000, plugged="usb"),
            power_row(T0 + 30_000, 1_000_000, plugged="usb"),
            power_row(T0 + 35_000, -2_000_000),
            power_row(T0 + 95_000, -2_000_000),  # 60 s gap: the app was stopped
            "# capped at 2097152 bytes",
        ])
        s = power_report.power_stats(rows)
        self.assertEqual(s["rows"], 9)
        self.assertEqual(s["game_ms"], 10_000)
        self.assertAlmostEqual(s["game_avg_mw"], 9000.0)
        self.assertAlmostEqual(s["game_mah"], 6.25)
        self.assertAlmostEqual(s["game_counter_mah"], 7.0)
        self.assertEqual(s["game_charging_ms"], 15_000)
        self.assertEqual(s["unplugged_ms"], 20_000)
        full = (5000.0 * 7 + 3_997_000 / 800 + 3_993_000 / 790) / 9
        self.assertAlmostEqual(s["full_mah"], full)
        self.assertAlmostEqual(s["game_pct_per_hour"], 6.25 / full * 100 * 360)

    def test_pixel_and_inverted_sign_give_the_same_draw(self):
        pixel = self.rows([power_row(T0, -1_500_000), power_row(T0 + 5_000, -1_500_000)])
        other = self.rows([power_row(T0, 1_500_000), power_row(T0 + 5_000, 1_500_000)])
        self.assertAlmostEqual(power_report.power_stats(pixel)["game_avg_mw"], 6000.0)
        self.assertAlmostEqual(power_report.power_stats(other)["game_avg_mw"], 6000.0)
        self.assertAlmostEqual(power_report.power_stats(other)["game_mah"], 1_500_000 * 5_000 / 3.6e9)

    def test_drain_falls_back_to_the_level_without_a_counter(self):
        rows = self.rows([power_row(T0, -2_000_000, charge="", level=80),
                          power_row(T0 + 30_000, -2_000_000, charge="", level=79)])
        s = power_report.power_stats(rows)
        self.assertIsNone(s["full_mah"])
        self.assertAlmostEqual(s["game_pct_per_hour"], 120.0)

    def test_columns_are_read_by_name_and_restarts_repeat_the_header(self):
        text = "t_wall_ms,game,power_mw,plugged\n1000,1,5000,none\n6000,1,5000,none\nt_wall_ms,game\n7000,1\n"
        rows = power_report.parse_power_rows(text)
        self.assertEqual([r["t"] for r in rows], [1000, 6000, 7000])
        self.assertEqual(rows[0]["power_mw"], 5000.0)
        self.assertIsNone(rows[2]["power_mw"])

    def test_no_game_time_gives_no_game_figures(self):
        s = power_report.power_stats(self.rows([power_row(T0, -1_000_000, game=0), power_row(T0 + 5_000, -1_000_000, game=0)]))
        self.assertEqual(s["game_ms"], 0)
        self.assertIsNone(s["game_avg_mw"])
        self.assertIsNone(s["game_pct_per_hour"])
        self.assertAlmostEqual(s["unplugged_avg_mw"], 4000.0)


class PowerReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "config").mkdir()
        (self.root / "config" / "kestrel.json").write_text(json.dumps(
            {"experiments": {"always": ["sched", "all"], "rotate": [["memory"], ["fex-fast"]]}}))
        power = [HEADER] + [power_row(T0 + 1_000 + i * 5_000, -2_000_000, charge=4_000_000 - i * 3_000) for i in range(13)]
        self.cheap = bundle(self.root, "perf-x-1111111", "2026-11-13T10-00-00Z", "2026-11-13-01-steam", {
            "events.jsonl": events("sched,all,memory"),
            "frames.csv": frames_csv(60),
            "power.csv": "\n".join(power) + "\n",
            "clocks.log": "== clocks: test\nstart: wall_ms=%d tz_offset_min=0\n10:00:01 p0 1800/1800 | battery 39.0 C\n" % T0,
        })
        hot = [HEADER] + [power_row(T0 + 1_000 + i * 5_000, -3_000_000) for i in range(13)]
        self.costly = bundle(self.root, "perf-y-2222222", "2026-11-13T11-00-00Z", "2026-11-13-02-steam", {
            "events.jsonl": events("sched,all,fex-fast"),
            "frames.csv": frames_csv(60, 33_333),
            "power.csv": "\n".join(hot) + "\n",
        })
        self.old = bundle(self.root, "perf-y-2222222", "2026-11-12T09-00-00Z", "2026-11-12-01-steam", {
            "events.jsonl": events("sched,all,fex-fast"),
            "frames.csv": frames_csv(120, 33_333),
            "clocks.log": "== clocks: test\nstart: wall_ms=%d tz_offset_min=0\n10:00:01 p0 1800/1800 | battery 41.5 C\n" % T0,
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_report(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = power_report.main([str(self.root / "runs"), *args])
        return code, out.getvalue()

    def test_json_has_per_session_figures_and_old_bundles_degrade(self):
        code, text = self.run_report("--json")
        self.assertEqual(code, 0)
        rows = {r["start"]: r for r in json.loads(text)}
        cheap = rows["2026-11-13T10-00-00Z"]
        self.assertEqual(cheap["run"], "perf-x-1111111")
        self.assertEqual(cheap["experiment_set"], "memory")
        self.assertEqual(cheap["minutes_from"], "power")
        self.assertAlmostEqual(cheap["game_minutes"], 1.0)
        self.assertAlmostEqual(cheap["avg_mw"], 8000.0)
        self.assertAlmostEqual(cheap["mwh_per_min"], 8000.0 / 60)
        self.assertAlmostEqual(cheap["avg_fps"], 1e6 / 16_667, places=3)
        self.assertEqual(cheap["battery_temp_max_c"], 36.5)
        old = rows["2026-11-12T09-00-00Z"]
        self.assertIsNone(old["power"])
        self.assertIsNone(old["avg_mw"])
        self.assertIsNone(old["pct_per_hour"])
        self.assertEqual(old["minutes_from"], "frames")
        self.assertAlmostEqual(old["game_minutes"], 2.0, delta=0.05)
        self.assertEqual(old["battery_temp_max_c"], 41.5)
        self.assertEqual(old["experiment_set"], "fex-fast")

    def test_runs_and_experiment_sets_rank_cheapest_energy_first(self):
        code, text = self.run_report()
        self.assertEqual(code, 0)
        self.assertIn("no power.csv in 1 of 3 session(s)", text)
        per_run = text.split("== per run", 1)[1].split("== per experiment set", 1)[0].strip().splitlines()[2:]
        self.assertTrue(per_run[0].startswith("perf-x-1111111"), per_run)
        self.assertTrue(per_run[1].startswith("perf-y-2222222"), per_run)
        # perf-y: one powered minute at 12 W plus about two minutes without power data, fps over all three
        cells = per_run[1].split()
        self.assertAlmostEqual(float(cells[1]), 3.0, delta=0.05)
        self.assertEqual(cells[2], "30.0")
        self.assertEqual(cells[3], "12000")
        self.assertEqual(cells[5], "200.0")
        sets = text.split("== per experiment set", 1)[1].strip().splitlines()[2:]
        self.assertEqual([line.split()[0] for line in sets], ["memory", "fex-fast"])

    def test_aggregate_weights_power_by_powered_minutes(self):
        rows = [
            {"run": "a", "frames": 60, "frame_seconds": 1.0, "game_minutes": 1.0, "pct_per_hour": 10.0,
             "battery_temp_max_c": 40.0, "power": {"game_ms": 60_000, "game_energy_mwh": 100.0}},
            {"run": "a", "frames": 30, "frame_seconds": 1.0, "game_minutes": 3.0, "pct_per_hour": 20.0,
             "battery_temp_max_c": None, "power": {"game_ms": 180_000, "game_energy_mwh": 900.0}},
        ]
        g = power_report.aggregate(rows, "run")[0]
        self.assertEqual(g["sessions"], 2)
        self.assertAlmostEqual(g["avg_fps"], 45.0)
        self.assertAlmostEqual(g["avg_mw"], 1000.0 * 3_600_000 / 240_000)
        self.assertAlmostEqual(g["mwh_per_min"], 250.0)
        self.assertAlmostEqual(g["pct_per_hour"], 17.5)
        self.assertEqual(g["battery_temp_max_c"], 40.0)

    def test_min_minutes_and_missing_inputs(self):
        code, text = self.run_report("--min-minutes", "1.5", "--json")
        self.assertEqual(code, 0)
        self.assertEqual([r["start"] for r in json.loads(text)], ["2026-11-12T09-00-00Z"])
        empty = self.root / "empty"
        empty.mkdir()
        with redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
            power_report.main([str(self.root / "nope")])
        with redirect_stderr(io.StringIO()):
            self.assertEqual(power_report.main([str(empty)]), 1)

    def test_config_is_found_above_the_inputs(self):
        self.assertEqual(power_report.find_config([self.root / "runs" / "perf-x-1111111"]),
                         (self.root / "config" / "kestrel.json").resolve())
        self.assertEqual(power_report.always_on(None), set())


if __name__ == "__main__":
    unittest.main()
