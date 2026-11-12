import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
from unittest import mock
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))

TENSOR_G5_CAPACITY = {0: 260, 1: 260, 2: 620, 3: 620, 4: 620, 5: 620, 6: 620, 7: 1024}
TENSOR_G5_CLOCKS = {0: 2253000, 1: 2253000, 2: 3053000, 3: 3053000, 4: 3053000, 5: 3053000, 6: 3053000, 7: 3782000}
ELITE_CLOCKS = {0: 3532800, 1: 3532800, 2: 3532800, 3: 3532800, 4: 3532800, 5: 3532800, 6: 4320000, 7: 4320000}


def budget(value, speeds, cores=(7, 2, 3, 4, 5, 6, 0, 1)):
    with mock.patch.dict(MODULE["thread_budget"].__globals__, {"core_speeds": lambda cores: speeds}), \
            mock.patch.object(sys, "stderr", new_callable=lambda: __import__("io").StringIO()) as said:
        kept = MODULE["thread_budget"](value, list(cores))
    return kept, said.getvalue()


class ThreadBudgetTest(unittest.TestCase):
    def test_big_leaves_out_the_little_cores_by_capacity_or_clock(self):
        for speeds in (TENSOR_G5_CAPACITY, TENSOR_G5_CLOCKS):
            kept, said = budget("big", speeds)
            self.assertEqual(kept, [7, 2, 3, 4, 5, 6])
            self.assertIn("thread budget big: game threads on 6 of 8 cores (7,2,3,4,5,6), left out 0,1", said)

    def test_big_keeps_performance_cores_that_are_only_clocked_lower(self):
        kept, said = budget("big", ELITE_CLOCKS, cores=(6, 7, 0, 1, 2, 3, 4, 5))
        self.assertEqual(kept, [6, 7, 0, 1, 2, 3, 4, 5])
        self.assertIn("all 8 cores kept", said)

    def test_a_count_keeps_that_many_of_the_fastest(self):
        self.assertEqual(budget("4", TENSOR_G5_CAPACITY)[0], [7, 2, 3, 4])
        self.assertEqual(budget("1", TENSOR_G5_CAPACITY)[0], [7, 2])

    def test_unknown_values_and_unreadable_speeds_keep_every_core(self):
        every = [7, 2, 3, 4, 5, 6, 0, 1]
        kept, said = budget("half", TENSOR_G5_CAPACITY)
        self.assertEqual(kept, every)
        self.assertIn("not understood", said)
        kept, said = budget("big", None)
        self.assertEqual(kept, every)
        self.assertIn("core speeds unreadable", said)

    def test_windows_sees_and_runs_on_only_the_budgeted_cores(self):
        os_mod = MODULE["os"]
        cases = [
            ({"DROIDDECK_GAME_CPU_BUDGET": "big"}, "6:7,2,3,4,5,6"),
            ({"DROIDDECK_GAME_CPU_BUDGET": "big", "DROIDDECK_FASTEST_CORES": "0"}, "6:7,2,3,4,5,6"),
            ({"DROIDDECK_GAME_CPU_BUDGET": "3"}, "3:7,2,3"),
            ({"DROIDDECK_GAME_CPU_BUDGET": "all"}, "8:7,2,3,4,5,6,0,1"),
            ({"DROIDDECK_GAME_CPU_BUDGET": "all", "DROIDDECK_FASTEST_CORES": "0"}, None),
            ({"DROIDDECK_GAME_CPU_BUDGET": "big", "WINE_CPU_TOPOLOGY": "2:0,1"}, "2:0,1"),
            ({"DROIDDECK_GAME_CPU_BUDGET": "big", "BL_GAME_CPUS": "0,1,7"}, "2:7,0"),
        ]
        every = set(range(8))
        for env, expected in cases:
            env["STEAM_COMPAT_DATA_PATH"] = "/steam/compatdata/2868840"
            affinity = MODULE["parse_cores"](env.get("BL_GAME_CPUS", "0-7"))
            with mock.patch.dict(MODULE["cpu_topology"].__globals__, {"online_cores": lambda: every}), \
                    mock.patch.dict(MODULE["fastest_first"].__globals__, {"core_speeds": lambda cores: TENSOR_G5_CAPACITY}), \
                    mock.patch.object(os_mod, "sched_getaffinity", return_value=affinity, create=True), \
                    mock.patch.object(sys, "stderr"):
                MODULE["cpu_topology"](env, ["/missing/proton", "waitforexitandrun", "/game/game.exe"])
            self.assertEqual(env.get("WINE_CPU_TOPOLOGY"), expected, env)


class FrameCapTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        folder = Path(self.tmp.name) / "SlayTheSpire2"
        folder.mkdir()
        self.godot = folder / "SlayTheSpire2.exe"
        self.godot.write_bytes(b"MZ")
        (folder / "SlayTheSpire2.pck").write_bytes(b"GDPC")
        self.other = Path(self.tmp.name) / "other.exe"
        self.other.write_bytes(b"MZ")

    def tearDown(self):
        self.tmp.cleanup()

    def cap(self, args, env):
        return MODULE["frame_cap"](["/proton", "waitforexitandrun", *args], env)

    def test_godot_gets_max_fps_ahead_of_its_user_arguments(self):
        env = {"DROIDDECK_FPS_CAP": "60"}
        command, note = self.cap([str(self.godot), "--rendering-driver", "vulkan", "--", "--mod"], env)
        self.assertEqual(command[3:], ["--rendering-driver", "vulkan", "--max-fps", "60", "--", "--mod"])
        self.assertEqual(note, "frame cap 60 fps (Godot --max-fps, DXVK, VKD3D)")
        self.assertEqual(env["DXVK_CONFIG"], "dxvk.maxFrameRate = 60")
        self.assertEqual(env["VKD3D_FRAME_RATE"], "60")

    def test_settings_the_player_made_are_kept(self):
        env = {"DROIDDECK_FPS_CAP": "30", "DXVK_CONFIG": "dxgi.maxDeviceMemory = 2048;", "VKD3D_FRAME_RATE": "45"}
        command, note = self.cap([str(self.godot), "--max-fps", "90"], env)
        self.assertEqual(command[3:], ["--max-fps", "90"])
        self.assertEqual(env["DXVK_CONFIG"], "dxgi.maxDeviceMemory = 2048; dxvk.maxFrameRate = 30")
        self.assertEqual(env["VKD3D_FRAME_RATE"], "45")
        self.assertEqual(note, "frame cap 30 fps (Godot --max-fps from the launch options kept, DXVK)")
        env = {"DROIDDECK_FPS_CAP": "30", "DXVK_CONFIG": "dxvk.maxFrameRate = 120", "VKD3D_FRAME_RATE": "45"}
        self.assertEqual(self.cap([str(self.other)], env)[1], "frame cap 30 fps (already set)")

    def test_other_games_are_capped_through_dxvk_and_vkd3d_only(self):
        env = {"DROIDDECK_FPS_CAP": "40"}
        command, _ = self.cap([str(self.other), "-windowed"], env)
        self.assertEqual(command[3:], ["-windowed"])
        self.assertEqual(env["VKD3D_FRAME_RATE"], "40")

    def test_no_cap_or_a_bad_value_leaves_the_launch_alone(self):
        for value in (None, "", "0"):
            env = {} if value is None else {"DROIDDECK_FPS_CAP": value}
            self.assertEqual(self.cap([str(self.godot)], env), (["/proton", "waitforexitandrun", str(self.godot)], None))
            self.assertNotIn("DXVK_CONFIG", env)
        env = {"DROIDDECK_FPS_CAP": "sixty"}
        command, note = self.cap([str(self.godot)], env)
        self.assertEqual(command[3:], [])
        self.assertIn("not understood", note)
        self.assertEqual(env, {"DROIDDECK_FPS_CAP": "sixty"})

    def test_slay_the_spire_2_gets_the_budget_and_cap_by_default(self):
        game = mock.Mock(stem="slaythespire2", files={"slaythespire2.pck", "godotsharp.dll"})
        env, extra, note = MODULE["slay_the_spire_2_pacing"](game)
        self.assertEqual(env, {"DROIDDECK_GAME_CPU_BUDGET": "big", "DROIDDECK_FPS_CAP": "60"})
        self.assertEqual(extra, [])
        self.assertTrue(note.startswith("Slay the Spire 2:"))
        self.assertIsNone(MODULE["slay_the_spire_2_pacing"](mock.Mock(stem="slaythespire2", files=set())))
        self.assertIsNone(MODULE["slay_the_spire_2_pacing"](mock.Mock(stem="brotato", files={"brotato.pck"})))

    def test_launch_applies_the_cap_and_a_game_profile_can_undo_it(self):
        home = Path(self.tmp.name)
        proton = home / "fake-proton"
        proton.write_text("#!" + sys.executable + "\nimport json, os, sys\n"
                          "print(json.dumps([sys.argv[1:], os.environ.get('DROIDDECK_FPS_CAP'), os.environ.get('VKD3D_FRAME_RATE')]))\n")
        proton.chmod(0o755)
        config = home / ".config/droiddeck/game-environment.json"
        config.parent.mkdir(parents=True)
        env = dict(os.environ, HOME=str(home), STEAM_COMPAT_DATA_PATH="/compatdata/2868840", WINE_CPU_TOPOLOGY="")
        for name in ("DROIDDECK_FPS_CAP", "DROIDDECK_GAME_CPU_BUDGET", "VKD3D_FRAME_RATE", "DXVK_CONFIG"):
            env.pop(name, None)
        for profile, args, cap in (({}, ["--max-fps", "60"], "60"), ({"DROIDDECK_FPS_CAP": "0"}, [], "0"),
                                   ({"DROIDDECK_FPS_CAP": "30"}, ["--max-fps", "30"], "30")):
            with self.subTest(profile=profile):
                config.write_text(json.dumps({"version": 1, "games": {"2868840": profile}}))
                result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"),
                                         str(proton), "waitforexitandrun", str(self.godot)],
                                        env=env, text=True, capture_output=True, check=True)
                argv, seen_cap, vkd3d = json.loads(result.stdout.strip().splitlines()[-1])
                self.assertEqual(argv, ["waitforexitandrun", str(self.godot), "--rendering-driver", "vulkan", *args])
                self.assertEqual(seen_cap, cap)
                self.assertEqual(vkd3d, None if cap == "0" else cap)
                self.assertIn("Slay the Spire 2: game threads kept off the little cores", result.stderr)
                self.assertEqual("droiddeck-game-env: frame cap %s fps (Godot --max-fps, DXVK, VKD3D)" % cap in result.stderr,
                                 cap != "0", result.stderr)


class ThreadPlacementTest(unittest.TestCase):
    def test_reports_where_the_game_threads_may_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp)
            stat_tail = " ".join(["S"] + ["0"] * 10 + ["{u}", "{s}"] + ["0"] * 23 + ["{cpu}"] + ["0"] * 5)
            for pid, comm, tasks in (("100", "SlayTheSpire2.e", (("100", "2-7", 7, 50, 10), ("101", "2-7", 3, 5, 5),
                                                               ("102", "0-7", 0, 1, 0))),
                                     ("200", "steam", (("200", "0-7", 1, 9, 9),))):
                (proc / pid).mkdir()
                (proc / pid / "comm").write_text(comm + "\n")
                for tid, allowed, cpu, user, system in tasks:
                    task = proc / pid / "task" / tid
                    task.mkdir(parents=True)
                    (task / "status").write_text("Name:\t%s\nCpus_allowed_list:\t%s\n" % (comm, allowed))
                    (task / "stat").write_text("%s (%s) %s\n" % (tid, comm, stat_tail.format(u=user, s=system, cpu=cpu)))
            (proc / "self").mkdir()
            line = MODULE["thread_placement"]("SlayTheSpire2.exe", proc)
            self.assertEqual(line, "thread placement: SlayTheSpire2.exe pid 100, 3 threads, allowed 2-7 (2) 0-7 (1), "
                                   "cpu ticks by last core 0:1 3:10 7:60")
            self.assertEqual(MODULE["thread_placement"]("Other.exe", proc), "thread placement: Other.exe not running")

    def test_the_check_reports_twice_and_stops_when_the_launch_ends(self):
        slept, parents = [], iter([10] + [10] * 89 + [1])
        with mock.patch.dict(MODULE["thread_check"].__globals__, {"thread_placement": lambda name: "thread placement: x"}), \
                mock.patch.object(sys, "stderr", new_callable=lambda: __import__("io").StringIO()) as said:
            self.assertEqual(MODULE["thread_check"]("x.exe", sleep=slept.append, parent=lambda: 10), 0)
            self.assertEqual(said.getvalue(), "droiddeck-game-env: thread placement: x after 60 s\n"
                                              "droiddeck-game-env: thread placement: x after 180 s\n")
            self.assertEqual(sum(slept), 180)
            slept.clear()
            self.assertEqual(MODULE["thread_check"]("x.exe", sleep=slept.append, parent=lambda: next(parents)), 0)
            self.assertEqual(sum(slept), 180)
            self.assertEqual(said.getvalue().count("after 60 s"), 2)
            self.assertEqual(said.getvalue().count("after 180 s"), 1)


if __name__ == "__main__":
    unittest.main()
