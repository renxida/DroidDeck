from pathlib import Path
import json
import struct
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bench"))
import benchlib


def frames_csv(t0, rows):
    return f"# frames t0_wall_ms={t0} t0_mono_ns=1 source=gamescope\nt_ms,frametime_us\n# t_ms=1 engine=VULKAN pid=1\n" + \
        "".join(f"{at},{us}\n" for at, us in rows)


def picture(width=2424, height=1080, fill=(0, 0, 0), boxes=()):
    rows = [bytearray(bytes(fill + (255,)) * width) for _ in range(height)]
    for x0, y0, x1, y1, colour in boxes:
        for y in range(y0, y1):
            rows[y][x0 * 4:x1 * 4] = bytes(colour + (255,)) * (x1 - x0)
    return benchlib.Picture(width, height, b"".join(bytes(row) for row in rows))


class FramesTest(unittest.TestCase):
    def test_parse_uses_the_wall_clock_origin_and_skips_notes(self):
        frames = benchlib.parse_frames(frames_csv(1000, [(10, 16000), (27, 17000)]) + "garbage\n")
        self.assertEqual(frames, [(1010, 16.0), (1027, 17.0)])

    def test_stats_match_the_fp_sh_percentiles(self):
        times = [16.0] * 97 + [34.0, 40.0, 60.0]
        stats = benchlib.frame_stats(times, 2000)
        self.assertEqual(stats["n"], 100)
        self.assertEqual(stats["fps"], 50.0)
        self.assertEqual((stats["p50"], stats["p95"], stats["p99"], stats["max"]), (16.0, 16.0, 60.0, 60.0))
        self.assertEqual(stats["over33_per_min"], 90.0)
        self.assertEqual(stats["over50_per_min"], 30.0)

    def test_empty_stats_keep_every_key(self):
        stats = benchlib.frame_stats([], 0)
        self.assertEqual(stats["n"], 0)
        for key in benchlib.FRAME_KEYS:
            self.assertIn(key, stats)

    def test_windows_merge_and_select(self):
        self.assertEqual(benchlib.merge_windows([(50, 100), (90, 150), (300, 400), (500, 500)]), [(50, 150), (300, 400)])
        frames = [(40, 1.0), (60, 2.0), (150, 3.0), (151, 4.0), (350, 5.0), (401, 6.0)]
        picked, span = benchlib.select_frames(frames, [(50, 100), (90, 150), (300, 400)])
        self.assertEqual(picked, [2.0, 3.0, 5.0])
        self.assertEqual(span, 200)

    def test_animation_windows_run_from_the_first_press_to_the_settle_time(self):
        steps = [
            {"kind": "focus", "t_first_done_ms": 900, "t_done_ms": 900, "settle_ms": 0},
            {"kind": "card", "t_first_done_ms": 1000, "t_done_ms": 2500, "settle_ms": 2000},
            {"kind": "end_turn", "t_first_done_ms": 9000, "t_done_ms": 9000, "settle_ms": 6000},
        ]
        lead = benchlib.INPUT_LEAD_MS
        self.assertEqual(benchlib.animation_windows(steps), [(1000 - lead, 4500), (9000 - lead, 15000)])
        self.assertEqual(benchlib.animation_windows(steps, ("end_turn",)), [(9000 - lead, 15000)])

    def test_window_stats_count_missing_frames_in_fps(self):
        frames = [(1000 + 20 * index, 20.0) for index in range(1, 51)]
        stats = benchlib.window_stats(frames, [(1000, 3000)])
        self.assertEqual(stats["n"], 50)
        self.assertEqual(stats["fps"], 25.0)


class ParsersTest(unittest.TestCase):
    SAMPLER = ("@ 100\nMemAvailable:    2048000 kB\nSwapFree:  1024000 kB\nGpu:  512000 kB\nGlobal total: 1073741824\n"
               "Proc 11 total: 536870912\nProc 12 total: 104857600\nP 11 libvkbridge_ser\nP 12 surfaceflinger\nT 301 0\nF 600000 1\n"
               "@ 110\nMemAvailable:    1024000 kB\nGlobal total: 2147483648\nProc 11 total: 1073741824\nP 11 libvkbridge_ser\n")

    def test_sampler_blocks_and_owner_names(self):
        samples = benchlib.parse_sampler(self.SAMPLER)
        self.assertEqual([sample["t_ms"] for sample in samples], [100000, 110000])
        self.assertEqual(samples[0]["gpu_owner_mb"], {"libvkbridge_ser": 512.0, "surfaceflinger": 100.0})
        summary = benchlib.sampler_summary(samples, 0, 200000)
        self.assertEqual(summary["MemAvailable_mb"], 1500)
        self.assertEqual(summary["MemAvailable_min_mb"], 1000)
        self.assertEqual(summary["gpu_owner_mb"]["libvkbridge_ser"], 768)
        self.assertEqual(benchlib.sampler_summary(samples, 105000, 200000)["samples"], 1)

    def test_csv_cpu_sums_a_name_per_sample(self):
        rows = benchlib.parse_csv("t_wall_ms,pid,name,side,rss_mb,swap_mb,cpu_pct\n100,1,sts2,game,1,0,60\n100,2,web,droiddeck,1,0,2\n"
                                  "100,3,web,droiddeck,1,0,4\n200,1,sts2,game,1,0,80\n200,2,web,droiddeck,1,0,6\n900,1,sts2,game,1,0,500\n")
        cpu = benchlib.csv_cpu(rows, 0, 300, ("name", "side"))
        self.assertEqual(cpu[0], {"name": "sts2", "side": "game", "cpu_pct": 70.0})
        self.assertEqual(cpu[1], {"name": "web", "side": "droiddeck", "cpu_pct": 6.0})

    def test_top_skips_the_first_iteration(self):
        text = ("Tasks: 1\n  PID   TID %CPU CMD             NAME\n  10    10 99.0 sts2            sts2\n"
                "Tasks: 1\n  PID   TID %CPU CMD             NAME\n  10    10 50.0 sts2            sts2\n  10    11 10.0 WorkerThread 1  sts2\n"
                "  624   700  4.0 RenderEngine    surfaceflinger\n"
                "Tasks: 1\n  PID   TID %CPU CMD             NAME\n  10    10 70.0 sts2            sts2\n")
        rows = benchlib.parse_top(text)
        self.assertEqual(rows[0], {"process": "sts2", "thread": "sts2", "cpu_pct": 60.0})
        self.assertEqual(benchlib.top_by_process(rows), {"sts2": 65.0, "surfaceflinger": 2.0})

    def test_engine_counter_and_game_log(self):
        line = ("native_compat: frame split 10.0s 600 frames 60.0 fps | per frame: process peak 13.31 ms, physics peak 1.93 ms, "
                "spine update 0.13 ms (signals 0.01 ms), spine draw 3.23 ms, main thread cpu 6.30 ms (busy 37%)\n")
        other = line.replace("60.0 fps", "50.0 fps").replace("13.31", "40.00")
        self.assertEqual(benchlib.parse_frame_splits(other + line + line, last=2)["fps_mean"], 60.0)
        whole = benchlib.parse_frame_splits(other + line)
        self.assertEqual((whole["fps_min"], whole["process_peak_ms_max"], whole["main_thread_busy_pct"]), (50.0, 40.0, 37.0))
        self.assertIsNone(benchlib.parse_frame_splits("nothing"))
        log = ("[INFO] Embarking on a singleplayer IRONCLAD run. Ascension: 0 Seed: ABC123\n[INFO] Creating NCombatRoom with "
               "mode=ActiveCombat encounter=NIBBITS_WEAK.\n[INFO] Player 1 playing card STRIKE_IRONCLAD (targeting Nibbit (index 1))\n"
               "[INFO] Monster NIBBIT performing move BUTT_MOVE\n")
        counted = benchlib.count_game_log(log)
        self.assertEqual((counted["cards_played"], counted["monster_moves"], counted["encounters"], counted["seeds"]),
                         (1, [("NIBBIT", "BUTT_MOVE")], ["NIBBITS_WEAK"], ["ABC123"]))

    def test_lmkd_kills_by_time(self):
        text = ("     100.500   579   579 I lowmemorykiller: Kill 'a' (1), uid 1, oom_score_adj 915 to free 10kB\n"
                "     200.500   579   579 I lowmemorykiller: Kill 'b' (2), uid 1, oom_score_adj 200 to free 10kB\n"
                "     210.000   579   579 I lowmemorykiller: Reclaimed\n")
        self.assertEqual(benchlib.lmkd_kills(text), {"kills": 2, "lowest_adj": 200})
        self.assertEqual(benchlib.lmkd_kills(text, start_s=150), {"kills": 1, "lowest_adj": 200})
        self.assertEqual(benchlib.lmkd_kills(text, end_s=50), {"kills": 0, "lowest_adj": None})


class PictureTest(unittest.TestCase):
    def test_screencap_header_and_png(self):
        body = bytes([10, 20, 30, 255]) * 6
        shot = benchlib.Picture.from_screencap(struct.pack("<IIII", 3, 2, 1, 0) + body)
        self.assertEqual((shot.width, shot.height, shot.pixel(2, 1)), (3, 2, (10, 20, 30)))
        self.assertTrue(shot.png().startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertEqual(shot.crop(1, 0, 3, 1).width, 2)

    def test_enemy_bar_fill(self):
        red = (200, 30, 30)
        full = benchlib.Layout(picture(boxes=[(1551, 750, 1831, 765, red)]))
        self.assertAlmostEqual(full.enemy_hp(), 1.0, places=2)
        low = benchlib.Layout(picture(boxes=[(1551, 750, 1601, 765, red), (1700, 750, 1712, 765, red)]))
        self.assertAlmostEqual(low.enemy_hp(), 50 / 280, places=2)
        partial = benchlib.Layout(picture(boxes=[(1551, 750, 1665, 765, red), (1690, 750, 1794, 765, red)]))
        self.assertAlmostEqual(partial.enemy_hp(), 243 / 280, places=2)
        self.assertIsNone(benchlib.Layout(picture()).enemy_hp())

    def test_menu_features(self):
        menu = benchlib.Layout(picture(fill=(62, 76, 91), boxes=[(905, 730, 955, 740, (230, 180, 60)), (280, 30, 320, 66, (63, 100, 184))]))
        self.assertTrue(menu.main_menu())
        self.assertFalse(menu.early_access_prompt())
        self.assertEqual(menu.menu_focus_y(), 734)
        self.assertEqual(menu.profile_icon(), "blue")
        prompt = benchlib.Layout(picture(fill=(10, 16, 22), boxes=[(1100, 840, 1150, 870, (138, 56, 58))]))
        self.assertTrue(prompt.early_access_prompt())
        self.assertFalse(prompt.main_menu())
        steam = benchlib.Layout(picture(boxes=[(1900, 30, 1912, 42, (181, 110, 240))]))
        self.assertEqual(steam.steam_offline_icon(), 144)
        self.assertEqual(benchlib.Layout(picture()).steam_offline_icon(), 0)

    def test_hand_glow(self):
        hand = benchlib.Layout(picture(boxes=[(700, 900, 900, 960, (40, 220, 220))]))
        self.assertGreater(hand.hand_glow(), 0.011)
        self.assertEqual(benchlib.Layout(picture()).hand_glow(), 0.0)


class RecordingTest(unittest.TestCase):
    def frames(self, dot_at, answer_at, count=40, width=20, height=10):
        out = []
        for index in range(count):
            frame = bytearray(width * height)
            if index >= dot_at:
                for y in range(0, 4):
                    frame[y * width:y * width + 4] = b"\xff" * 4
            if index >= answer_at:
                for y in range(6, 10):
                    frame[y * width + 10:y * width + 20] = b"\xc8" * 10
            out.append(bytes(frame))
        return out

    def test_touch_dot_to_answer(self):
        frames = self.frames(dot_at=20, answer_at=26)
        times = [index / 60.0 for index in range(len(frames))]
        touch = benchlib.region_change(frames, 20, (0, 0, 4, 4))
        answer = benchlib.region_change(frames, 20, (10, 6, 20, 10))
        self.assertGreater(touch[20], 100)
        self.assertEqual(sum(1 for value in touch if value > 0), 1)
        result = benchlib.touch_to_picture(times, touch, answer, after_s=0.1)
        self.assertEqual(result["ms"], 100.0)
        self.assertEqual(result["frame_ms"], 16.7)

    def test_no_dot_or_no_answer(self):
        frames = self.frames(dot_at=99, answer_at=99)
        times = [index / 60.0 for index in range(len(frames))]
        quiet = benchlib.region_change(frames, 20, (0, 0, 4, 4))
        self.assertIsNone(benchlib.touch_to_picture(times, quiet, quiet, after_s=0.1))
        frames = self.frames(dot_at=20, answer_at=99)
        touch = benchlib.region_change(frames, 20, (0, 0, 4, 4))
        answer = benchlib.region_change(frames, 20, (10, 6, 20, 10))
        self.assertIsNone(benchlib.touch_to_picture(times, touch, answer, after_s=0.1)["ms"])


class SummaryTest(unittest.TestCase):
    def summary(self):
        stats = benchlib.frame_stats([16.0] * 100, 2000)
        return {
            "schema": benchlib.SCHEMA_VERSION, "name": "fight-x", "started_utc": "2026-11-28T08:00:00Z", "scenario": "fight",
            "path": "native", "route": "bridge", "switches": ["vk-count"], "env": [], "build": {"commit": "21a115726888"},
            "engine": {"path": "native"}, "preflight": {}, "frames": {"overall": stats, "animation": stats}, "pacing": None,
            "latency": {"probe": {"toScreen": {"n": 60, "p50Ms": 48.1, "p99Ms": 71.0}}},
            "memory": {"window": {"MemAvailable_mb": 1400, "Gpu_mb": 1300, "ION_heap_mb": 900}}, "cpu": {}, "thermal": {},
            "lmkd": {}, "artifacts": {}, "checks": {}, "notes": [],
        }

    def test_a_complete_summary_passes(self):
        self.assertEqual(benchlib.validate_summary(self.summary()), [])
        json.dumps(self.summary())

    def test_problems_are_named(self):
        broken = self.summary()
        del broken["latency"]
        broken["route"] = "zink"
        del broken["frames"]["animation"]
        del broken["frames"]["overall"]["p99"]
        problems = benchlib.validate_summary(broken)
        for expected in ("missing latency", "route", "frames.animation missing", "frames.overall.p99 missing"):
            self.assertIn(expected, problems)

    def test_menu_summary_needs_no_animation_block(self):
        menu = self.summary()
        menu["scenario"] = "menu"
        del menu["frames"]["animation"]
        self.assertEqual(benchlib.validate_summary(menu), [])

    def test_results_row_has_one_cell_per_column(self):
        row = benchlib.results_row(self.summary())
        self.assertEqual(row.count("|"), benchlib.RESULTS_HEADER.count("|"))
        self.assertIn("Native, bridge, `vk-count`", row)
        self.assertIn("48.1 / 71.0 ms (n=60)", row)
        self.assertIn("1300 MB + 900 MB dma-buf", row)
        fallen = self.summary()
        fallen["engine"]["path"] = "proton"
        self.assertIn("(ran: proton)", benchlib.results_row(fallen))


if __name__ == "__main__":
    unittest.main()
