import contextlib
import io
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
BIN = TOOLS / "linuxfs/overlay/usr/local/bin"
GAME_ENV = BIN / "droiddeck-game-env"
MODULE = runpy.run_path(str(GAME_ENV))
sys.path.insert(0, str(TOOLS))
import dotnet_heap_limit  # noqa: E402

CSV = "t_wall_ms,used_mb,available_mb,swap_used_mb,game_mb,droiddeck_mb,app_mb,gpu_mb\n"


class DotnetHeapTest(unittest.TestCase):
    heap = staticmethod(MODULE["dotnet_heap"])

    def test_slay_the_spire_2_conserves_memory_by_default(self):
        env, line = self.heap({"A": "B"}, "2868840")
        self.assertEqual(env, {"A": "B", "DOTNET_GCConserveMemory": "5"})
        self.assertEqual(line, "DROIDDECK_DOTNET_HEAP app=2868840 conserve=5 limit_mb=- source=default")

    def test_other_games_are_untouched_and_silent(self):
        self.assertEqual(self.heap({"A": "B"}, "42"), ({"A": "B"}, None))

    def test_the_setting_chooses_the_level_or_turns_it_off(self):
        env, line = self.heap({"DROIDDECK_DOTNET_CONSERVE": "7"}, "42")
        self.assertEqual(env["DOTNET_GCConserveMemory"], "7")
        self.assertIn("conserve=7 limit_mb=- source=setting", line)
        env, line = self.heap({"DROIDDECK_DOTNET_CONSERVE": "0"}, "2868840")
        self.assertNotIn("DOTNET_GCConserveMemory", env)
        self.assertIsNone(line)
        env, _ = self.heap({"DROIDDECK_DOTNET_CONSERVE": "high"}, "2868840")
        self.assertEqual(env["DOTNET_GCConserveMemory"], "5")

    def test_a_clr_variable_set_by_hand_wins(self):
        for name in ("DOTNET_GCConserveMemory", "COMPlus_GCConserveMemory"):
            env, line = self.heap({name: "2"}, "2868840")
            self.assertEqual(env, {name: "2"})
            self.assertIn("conserve=2 limit_mb=- source=env", line)

    def test_the_heap_limit_is_written_in_hex_bytes(self):
        env, line = self.heap({"DROIDDECK_DOTNET_HEAP_LIMIT_MB": "3072"}, "2868840")
        self.assertEqual(env["DOTNET_GCHeapHardLimit"], "c0000000")
        self.assertEqual(int(env["DOTNET_GCHeapHardLimit"], 16), 3072 << 20)
        self.assertIn("limit_mb=3072", line)

    def test_bad_limits_and_hand_set_limits_are_not_overwritten(self):
        for value in ("100", "99999", "0x400", "-1", "2 GB"):
            env, line = self.heap({"DROIDDECK_DOTNET_HEAP_LIMIT_MB": value}, "2868840")
            self.assertNotIn("DOTNET_GCHeapHardLimit", env)
            self.assertIn("limit_mb=ignored", line)
        env, line = self.heap({"DROIDDECK_DOTNET_HEAP_LIMIT_MB": "2048", "DOTNET_GCHeapHardLimitPercent": "1e"}, "42")
        self.assertNotIn("DOTNET_GCHeapHardLimit", env)
        self.assertEqual(line, "DROIDDECK_DOTNET_HEAP app=42 conserve=0 limit_mb=env source=default")

    def test_a_game_launch_prints_the_line_and_passes_the_variable(self):
        with tempfile.TemporaryDirectory() as directory:
            probe = Path(directory) / "probe"
            probe.write_text("#!" + sys.executable + "\nimport os\nprint(os.environ.get('DOTNET_GCConserveMemory'))\n")
            probe.chmod(0o755)
            base = dict(os.environ, HOME=directory, PATH=os.defpath, DROIDDECK_GAME_EXIT_WATCH="0")
            for name in list(base):
                if name.startswith(("DOTNET_", "COMPlus_", "DROIDDECK_DOTNET_")):
                    base.pop(name)
            def launch():
                return subprocess.run([sys.executable, str(GAME_ENV), str(probe), "waitforexitandrun"],
                                      env={**base, "STEAM_COMPAT_DATA_PATH": "/compatdata/2868840"},
                                      capture_output=True, text=True, timeout=15)
            off = launch()
            self.assertEqual((off.returncode, off.stdout.strip()), (0, "None"), off.stderr)
            self.assertNotIn("DROIDDECK_DOTNET_HEAP", off.stderr)
            config = Path(directory) / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text('{"version": 1, "shared": {"DROIDDECK_EXP_MEMORY": "1"}}')
            sts2 = launch()
            self.assertEqual(sts2.returncode, 0, sts2.stderr)
            self.assertEqual(sts2.stdout.strip(), "5")
            self.assertIn("DROIDDECK_DOTNET_HEAP app=2868840 conserve=5 limit_mb=- source=default\n", sts2.stderr)
            tool = subprocess.run([sys.executable, str(GAME_ENV), str(probe), "run"],
                                  env={**base, "STEAM_COMPAT_DATA_PATH": "/compatdata/2868840"},
                                  capture_output=True, text=True, timeout=15)
            self.assertEqual(tool.stdout.strip(), "None")
            self.assertNotIn("DROIDDECK_DOTNET_HEAP", tool.stderr)


class HeapLimitSuggestionTest(unittest.TestCase):
    def test_the_bound_adds_swap_growth_since_the_game_appeared(self):
        samples = dotnet_heap_limit.rows(CSV + "1,4000,8000,900,0,800,150,\n2,5000,7000,600,1200,800,150,\n"
                                               "3,6000,6000,1100,1500,800,150,\n4,6000,6000,700,1700,800,150,\n")
        self.assertEqual(dotnet_heap_limit.game_bound(samples), (1700, 2000, 12000))

    def test_no_game_means_no_bound(self):
        self.assertIsNone(dotnet_heap_limit.game_bound(dotnet_heap_limit.rows(CSV + "1,4000,8000,0,0,800,150,\n")))

    def test_the_suggestion_has_headroom_and_steps_and_never_reaches_ram(self):
        self.assertEqual(dotnet_heap_limit.suggest(2000, 12000), 2816)
        self.assertEqual(dotnet_heap_limit.suggest(10, 12000), 512)
        self.assertIsNone(dotnet_heap_limit.suggest(9500, 12000))

    def test_the_tool_reads_session_folders(self):
        with tempfile.TemporaryDirectory() as directory:
            session = Path(directory) / "session-1"
            session.mkdir()
            (session / "memory.csv").write_text(CSV + "1,5000,7000,600,1200,800,150,\n2,6000,6000,600,2000,800,150,\n")
            (session / "events.jsonl").write_text('{"t":1,"event":"session.created","sessionId":"s1"}\n')
            with contextlib.redirect_stdout(io.StringIO()) as out:
                status = dotnet_heap_limit.main([directory])
            self.assertEqual(status, 0)
            self.assertIn("DROIDDECK_DOTNET_HEAP_LIMIT_MB=2816", out.getvalue())


if __name__ == "__main__":
    unittest.main()
