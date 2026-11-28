from pathlib import Path
import json
import re
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


class PhasesTest(unittest.TestCase):
    def test_phases_book_wall_time_and_the_two_kinds_of_sleep(self):
        now = [100.0]
        log = benchlib.PhaseLog(clock=lambda: now[0])
        log.begin("preflight")
        now[0] += 40
        log.slept(10)
        log.begin("fight", rep=1)
        now[0] += 53
        log.slept(45)
        log.slept(3, fixed=False)
        log.begin("fight", rep=2)
        now[0] += 52
        block = log.block()
        self.assertEqual(benchlib.validate_phases(block), [])
        self.assertEqual((block["total_s"], block["fixed_sleep_s"], block["poll_sleep_s"]), (145.0, 55.0, 3.0))
        self.assertEqual(block["by_name"]["fight"], {"count": 2, "seconds": 105.0, "fixed_sleep_s": 45.0, "poll_sleep_s": 3.0})
        self.assertEqual(block["items"][1], {"name": "fight", "rep": 1, "start_s": 40.0, "seconds": 53.0, "fixed_sleep_s": 45.0,
                                             "poll_sleep_s": 3.0})
        json.dumps(block)

    def test_broken_phase_blocks_are_named(self):
        self.assertEqual(benchlib.validate_phases(None), ["phases is not an object"])
        self.assertIn("phases.items missing", benchlib.validate_phases({"total_s": 1}))
        block = {"total_s": 1, "fixed_sleep_s": 9, "poll_sleep_s": 0, "by_name": {},
                 "items": [{"name": "x", "rep": None, "start_s": 0, "seconds": 1, "fixed_sleep_s": 9, "poll_sleep_s": 0}]}
        self.assertEqual(benchlib.validate_phases(block), ["phases.items[0] slept longer than it lasted"])


class LevelsTest(unittest.TestCase):
    EXPERIMENTS_KT = Path(__file__).resolve().parents[2] / "app/src/main/java/com/droiddeck/launcher/session/Experiments.kt"
    GAME_ENV = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-game-env"

    def test_every_experiment_of_the_app_has_a_level(self):
        source = self.EXPERIMENTS_KT.read_text()
        names = dict(re.findall(r'const val (\w+) = "([a-z0-9-]+)"', source))
        listed = re.search(r"val ids = listOf\((.*?)\)", source, re.S).group(1)
        ids = {names[word] for word in re.findall(r"\b[A-Z][A-Z0-9_]+\b", listed)}
        self.assertEqual(ids, set(benchlib.EXPERIMENT_LEVEL))
        self.assertEqual(set(benchlib.EXPERIMENT_LEVEL.values()), {"session", "launch"})

    def test_what_the_guest_calls_session_level_is_session_level_here(self):
        block = re.search(r"SESSION_EXPERIMENTS = [({\[](.*?)[)}\]]", self.GAME_ENV.read_text(), re.S).group(1)
        guest = set(re.findall(r'"([a-z0-9-]+)"', block))
        self.assertGreaterEqual(len(guest), 8)
        for name in guest:
            self.assertEqual(benchlib.apply_level(name), "session", name)

    def test_the_switches_the_bench_has_used(self):
        level = benchlib.apply_level
        self.assertEqual([level(name) for name in ("native-engine", "present-dmabuf", "vk-count", "vk-blocks-64", "pe-align")], ["launch"] * 5)
        self.assertEqual([level(name) for name in ("pad-idle-reports", "hints-idle", "zram-game-late", "bridge-default")], ["session"] * 4)
        self.assertEqual(level("setting-exp-hints-idle"), "session")
        self.assertEqual(level("something-new"), "session")
        self.assertEqual((benchlib.OTHER_LEVEL["route"], benchlib.OTHER_LEVEL["env"], benchlib.OTHER_LEVEL["push"]), ("session", "session", "launch"))

    def current(self, **changes):
        base = {"path": "native", "route": "bridge", "env": [], "pushes": {}, "game_running": True,
                "switches": {"native-engine": "On", "present-dmabuf": "Off", "zram-game-late": "Off"}, "baseline": {}}
        base.update(changes)
        return base

    def wanted(self, **changes):
        base = {"path": "native", "route": "bridge", "env": [], "pushes": {}, "switches": {"native-engine": "On"}}
        base.update(changes)
        return base

    def test_plans(self):
        plan = benchlib.plan_change
        self.assertEqual(plan(None, self.wanted())[0], "session")
        self.assertEqual(plan(self.current(), self.wanted()), ("none", []))
        self.assertEqual(plan(self.current(game_running=False), self.wanted())[0], "launch")
        self.assertEqual(plan(self.current(), self.wanted(switches={"native-engine": "On", "present-dmabuf": "On"})),
                         ("launch", ["launch: present-dmabuf Off -> On"]))
        self.assertEqual(plan(self.current(), self.wanted(path="proton", switches={"native-engine": "Off"}))[0], "launch")
        self.assertEqual(plan(self.current(), self.wanted(switches={"native-engine": "On", "zram-game-late": "On"}))[0], "session")
        self.assertEqual(plan(self.current(), self.wanted(route="panvk"))[0], "session")
        self.assertEqual(plan(self.current(), self.wanted(env=["VKBRIDGE_STATS=10"]))[0], "session")
        self.assertEqual(plan(self.current(), self.wanted(pushes={"/opt/x.so": "ab"}))[0], "launch")
        self.assertEqual(plan(self.current(), self.wanted(switches={"native-engine": "On", "unknown-one": "On"}))[0], "session")

    def test_a_switch_an_earlier_arm_set_goes_back_to_its_baseline(self):
        current = self.current(switches={"native-engine": "On", "present-dmabuf": "On", "zram-game-late": "On"},
                               baseline={"setting-exp-present-dmabuf": "Off", "setting-exp-zram-game-late": "Off"})
        level, reasons = benchlib.plan_change(current, self.wanted(switches={"native-engine": "On", "present-dmabuf": "On"}))
        self.assertEqual((level, reasons), ("session", ["session: zram-game-late On -> Off"]))
        level, reasons = benchlib.plan_change(current, self.wanted(switches={"native-engine": "On", "zram-game-late": "On"}))
        self.assertEqual((level, reasons), ("launch", ["launch: present-dmabuf On -> Off"]))

    def test_arms(self):
        arm = benchlib.parse_arm("zram-game-late, -hints-idle VKBRIDGE_STATS=10 route:panvk path:proton")
        self.assertEqual(arm, {"on": ["zram-game-late"], "off": ["hints-idle"], "env": ["VKBRIDGE_STATS=10"], "path": "proton", "route": "panvk"})
        self.assertEqual(benchlib.parse_arm(""), {"on": [], "off": [], "env": [], "path": None, "route": None})
        for bad in ("Zram", "route:zink", "a -a", "x;y"):
            with self.assertRaises(ValueError):
                benchlib.parse_arm(bad)


class PushAndScriptTest(unittest.TestCase):
    def test_push_targets(self):
        ok = benchlib.push_target_problem
        self.assertIsNone(ok("/opt/droiddeck-native-godot/lib/libgodot.so"))
        self.assertIsNone(ok("/usr/local/bin/droiddeck-game-env"))
        for bad in ("relative/file", "/opt/a b", "/opt/../root/x", "/opt/dir/", "/opt/$(id)", "/opt//x",
                    "/root/.local/share/Steam/steamapps/common/Slay the Spire 2/x.pck",
                    "/root/.local/share/Steam/steamapps/common/Game/data.pck",
                    "/root/.local/share/SlayTheSpire2/steam/1/profile3/saves/current_run.save",
                    "/root/.local/share/Steam/userdata/1/2868840/remote/profile1/saves/progress.save",
                    "/root/.local/share/Steam/userdata/1/2868840/remotecache.vdf",
                    "/root/.local/share/Steam/steamapps/compatdata/2868840/pfx/drive_c/x.dll", "/opt/x.so.bench-orig"):
            self.assertIsNotNone(ok(bad), bad)
        self.assertEqual(benchlib.parse_push("./build/libgodot.so:/opt/e/libgodot.so"), ("./build/libgodot.so", "/opt/e/libgodot.so"))
        for bad in ("nocolon", ":/opt/x", "a:", "a:/root/.local/share/SlayTheSpire2/x"):
            with self.assertRaises(ValueError):
                benchlib.parse_push(bad)

    def steps(self):
        return json.loads((Path(__file__).resolve().parents[1] / "bench/scenarios/fight.json").read_text())["steps"]

    def test_turns(self):
        steps = self.steps()
        self.assertEqual(benchlib.truncate_steps(steps, 0), steps)
        one = benchlib.truncate_steps(steps, 1)
        self.assertEqual((len(one), one[-1]["name"]), (6, "End Turn 1"))
        self.assertEqual(len(benchlib.truncate_steps(steps, 3)), len(steps))
        with self.assertRaises(ValueError):
            benchlib.truncate_steps(steps, 4)

    def test_the_script_is_about_as_long_as_the_fights_measured(self):
        self.assertTrue(50 <= benchlib.script_seconds(self.steps()) <= 56)
        self.assertTrue(15 <= benchlib.script_seconds(benchlib.truncate_steps(self.steps(), 1)) <= 19)

    def test_offline_evidence(self):
        good = ["steam_offline=true", "[2026-11-28 02:41:40] [ None ] Start offline - 0\n[2026-11-28 04:59:28] [ None ] Start offline - 1",
                "[2026-11-28 03:10:00] User is offline\n[2026-11-28 04:59:38] User is offline"]
        self.assertTrue(benchlib.offline_evidence(good, "2026-11-28 04:59:37")["ok"])
        self.assertTrue(benchlib.offline_evidence(good)["ok"])
        stale = benchlib.offline_evidence(good, "2026-11-28 05:30:00")
        self.assertFalse(stale["ok"])
        self.assertIn("older than the game launch", stale["problems"][0])
        self.assertEqual(benchlib.offline_evidence(["steam_offline=false"] + good[1:])["problems"], ["steam_offline=false"])
        online = benchlib.offline_evidence([good[0], "[2026-11-28 05:00:00] [ None ] Start offline - 0", good[2]])
        self.assertEqual(online["problems"], ["Steam's last 'Start offline' line: 0"])
        self.assertEqual(len(benchlib.offline_evidence([])["problems"]), 3)

    def test_reps_must_be_the_same_fight(self):
        rep = lambda index, cards, checks: {"index": index, "cards": cards, "monster_moves": [["NIBBIT", "BUTT"]], "enemy_hp_checks": checks}
        same = [rep(1, ["STRIKE", "DEFEND"], [0.867, 0.733]), rep(2, ["STRIKE", "DEFEND"], [0.864, 0.729])]
        self.assertEqual(benchlib.reps_identical(same), [])
        self.assertEqual(benchlib.reps_identical([]), [])
        other_hand = benchlib.reps_identical([same[0], rep(2, ["BASH", "DEFEND"], [0.867, 0.733])])
        self.assertEqual(len(other_hand), 1)
        self.assertIn("rep 2: cards", other_hand[0])
        self.assertIn("health checks", benchlib.reps_identical([same[0], rep(2, ["STRIKE", "DEFEND"], [0.6, 0.733])])[0])
        self.assertIn("health checks", benchlib.reps_identical([same[0], rep(2, ["STRIKE", "DEFEND"], [0.867])])[0])
        proton = [{"index": 1, "enemy_hp_checks": [0.867]}, {"index": 2, "enemy_hp_checks": [0.867]}]
        self.assertEqual(benchlib.reps_identical(proton), [])


class RepeatsTest(unittest.TestCase):
    def test_spread(self):
        self.assertEqual(benchlib.spread([36.1, 40.0, 35.7, 40.1]),
                         {"n": 4, "mean": 37.975, "median": 38.05, "sd": 2.402, "min": 35.7, "max": 40.1, "range": 4.4})
        self.assertEqual(benchlib.spread([5, None])["sd"], None)
        self.assertEqual(benchlib.spread([])["n"], 0)

    def test_pooled_sd_is_within_groups_only(self):
        self.assertEqual(benchlib.pooled_sd([[36.0, 40.0], [136.0, 140.0], [7.0]]), (2.828, 2))
        self.assertEqual(benchlib.pooled_sd([[1.0]]), (None, 0))

    def test_bootstrap_interval_is_deterministic_and_known(self):
        a = [[36.1], [40.0], [35.7], [40.1]]
        b = [[30.5], [29.6], [30.5], [30.5]]
        first = benchlib.bootstrap_diff_ci(a, b)
        self.assertEqual(first, benchlib.bootstrap_diff_ci(a, b))
        self.assertEqual(first["diff"], -7.7)
        self.assertEqual((first["pairs"], first["seed"], first["draws"], first["level"]), (4, benchlib.CONFIRM_SEED, 10000, 0.95))
        self.assertTrue(first["excludes_zero"])
        self.assertTrue(-10.4 <= first["low"] < first["diff"] < first["high"] <= -5.2)
        self.assertNotEqual(benchlib.bootstrap_diff_ci(a, b, seed=7, draws=200)["low"], None)

    def test_bootstrap_limits_stay_inside_the_pair_differences(self):
        interval = benchlib.bootstrap_diff_ci([[10.0], [10.0], [10.0]], [[11.0], [9.0], [12.0]])
        self.assertEqual((interval["low"], interval["high"]), (-1.0, 2.0))
        self.assertFalse(interval["excludes_zero"])

    def test_one_pair_of_single_values_cannot_exclude_zero(self):
        interval = benchlib.bootstrap_diff_ci([[36.0]], [[30.0]])
        self.assertTrue(interval["degenerate"])
        self.assertFalse(interval["excludes_zero"])
        self.assertEqual(interval["diff"], -6.0)

    def test_repetitions_inside_a_pair_are_resampled(self):
        interval = benchlib.bootstrap_diff_ci([[36.0, 37.0, 38.0]], [[30.0, 31.0, 29.0]])
        self.assertEqual(interval["diff"], -7.0)
        self.assertFalse(interval["degenerate"])
        self.assertTrue(interval["excludes_zero"])
        with self.assertRaises(ValueError):
            benchlib.bootstrap_diff_ci([[1.0]], [[]])
        with self.assertRaises(ValueError):
            benchlib.bootstrap_diff_ci([[1.0], [2.0]], [[1.0]])

    def test_detectable_differences(self):
        self.assertEqual(benchlib.detectable(2.0, 1), {"pairs": 1, "se": 2.83, "excludes_zero_from": 5.54, "power80_from": 7.92})
        self.assertEqual(benchlib.detectable(2.0, 6)["excludes_zero_from"], 2.26)

    def test_stopping_rule(self):
        decide = benchlib.confirm_decision
        clear = {"diff": -7.0, "excludes_zero": True}
        small = {"diff": -1.0, "excludes_zero": True}
        none = {"diff": -0.5, "excludes_zero": False}
        self.assertEqual(decide(clear, 2, 3, 6, 2.0)["why"], "fewer than min pairs")
        stop = decide(clear, 3, 3, 6, 2.0)
        self.assertEqual((stop["stop"], stop["verdict"], stop["beyond_noise_floor"]), (True, "different", True))
        self.assertEqual(decide(small, 3, 3, 6, 2.0), {"stop": False, "why": "inside the earlier noise floor", "verdict": None,
                                                       "beyond_noise_floor": False})
        self.assertEqual(decide(small, 3, 3, 6, None)["verdict"], "different")
        self.assertEqual(decide(small, 6, 3, 6, 2.0)["verdict"], "interval excludes 0 but the difference is inside the earlier noise floor")
        self.assertEqual(decide(none, 5, 3, 6, 2.0)["stop"], False)
        self.assertEqual((decide(none, 6, 3, 6, 2.0)["why"], decide(none, 6, 3, 6, 2.0)["verdict"]), ("max pairs", "no difference shown"))

    GROUPS = {"anim_p99_ms": [[36.1, 40.0], [35.7, 40.1], [37.2, 36.0]], "anim_fps": [[58.7, 58.6], [58.5, 58.4], [58.5, 58.3]],
              "over50_per_min": [[10.7, 17.4], [8.0, 12.0], [13.4, 16.0]], "mem_available_mb": [[1633, 1644], [1319, 1627], [1153, 802]]}

    def test_noise_floor_line(self):
        line = benchlib.noise_floor_line("2026-11-28", "d1ddd2bde", "native, bridge", "full-mode repeats", self.GROUPS, "lab-1 to lab-3")
        self.assertEqual(line, "- noise-floor | 2026-11-28 | d1ddd2b | native, bridge | full-mode repeats | n=6 in 3 groups (df 3) | "
                               "anim p99 sd 2.45 ms | fps sd 0.1 | over-50 sd 3.36 /min | MemAvailable sd 191 MB | p99 seen from 6.8 / 3.9 / 2.8 ms with 1 / 3 / 6 pairs | `lab-1 to lab-3`")
        self.assertIn("p99 floor n/a", benchlib.noise_floor_line("d", "b", "c", "s", {"anim_p99_ms": [[1.0]]}, "n"))

    def test_noise_lines_go_above_the_standard_rows(self):
        text = "# Bench results\n\nintro\n\n## Standard bench rows\n\n| a |\n|---|\n| row |\n"
        once = benchlib.insert_noise_line(text, "- noise-floor | one")
        twice = benchlib.insert_noise_line(once, "- noise-floor | two")
        self.assertTrue(twice.endswith("| row |\n"))
        self.assertLess(twice.index("- noise-floor | one"), twice.index("- noise-floor | two"))
        self.assertLess(twice.index("- noise-floor | two"), twice.index(benchlib.NOISE_MARK))
        self.assertLess(twice.index(benchlib.NOISE_MARK), twice.index("## Standard bench rows"))
        self.assertEqual(twice.count("## Noise floor"), 1)
        self.assertIn("- noise-floor | x", benchlib.insert_noise_line("no headings\n", "- noise-floor | x"))

    def test_rep_metrics_and_mode_schemas(self):
        stats = benchlib.frame_stats([16.0] * 100, 2000)
        rep = {"index": 1, "rep_in_launch": 1, "warmup": False, "frames": {"animation": stats}, "steps": [], "window_ms": [0, 1],
               "memory": {"MemAvailable_mb": 1500}}
        self.assertEqual(benchlib.rep_metrics(rep), {"anim_p99_ms": 16.0, "anim_fps": 50.0, "over50_per_min": 0.0, "mem_available_mb": 1500})
        phases = benchlib.PhaseLog(clock=lambda: 0.0).block()
        screen = {"mode": "screen", "phases": phases, "reps": [rep], "reuse": {}, "pushed_files": [], "identical": {},
                  "spread": {metric: benchlib.spread([1.0]) for metric in benchlib.CONFIRM_METRICS}}
        self.assertEqual(benchlib.validate_screen(screen), [])
        del screen["reps"][0]["window_ms"]
        del screen["spread"]["anim_fps"]
        self.assertEqual(benchlib.validate_screen(screen), ["reps[0].window_ms missing", "spread.anim_fps missing"])
        confirm = {"schema": 1, "mode": "confirm", "name": "n", "started_utc": "t", "a": {}, "b": {}, "pairs": 3, "values": {},
                   "intervals": {metric: None for metric in benchlib.CONFIRM_METRICS}, "decision": {}, "spread": {}, "seed": 1,
                   "share": {}, "ok": True, "notes": []}
        self.assertEqual(benchlib.validate_confirm(confirm), [])
        del confirm["intervals"]["anim_p99_ms"]
        self.assertEqual(benchlib.validate_confirm(confirm), ["intervals.anim_p99_ms missing"])


if __name__ == "__main__":
    unittest.main()
