import errno
import io
import os
from pathlib import Path
import runpy
import sys
import tempfile
from unittest import mock
import unittest

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))["start"].__globals__

APP = "2868840"
TENSOR_G5_CAPACITY = {0: 260, 1: 260, 2: 620, 3: 620, 4: 620, 5: 620, 6: 620, 7: 1024}
BIG = {"cores": [7, 2, 3, 4, 5, 6], "budget": "big", "base": None}


class FakeProc:
    """A /proc with stat, comm and per-thread status files, and the sched_setaffinity that rewrites them."""

    def __init__(self, root):
        self.root = Path(root)
        self.calls = []
        self.fail = None

    def process(self, pid, ppid, comm, threads, started="1000"):
        d = self.root / str(pid)
        (d / "task").mkdir(parents=True, exist_ok=True)
        rest = ["S", str(ppid)] + ["0"] * 17 + [started, "0", "0"]
        (d / "stat").write_text("%d (%s) %s\n" % (pid, comm, " ".join(rest)))
        (d / "comm").write_text(comm[:15] + "\n")
        for tid, mask in threads.items():
            (d / "task" / str(tid)).mkdir(exist_ok=True)
            self.write(pid, tid, mask)

    def write(self, pid, tid, mask):
        text = "Name:\tx\nCpus_allowed:\tff\nCpus_allowed_list:\t%s\n" % mask
        (self.root / str(pid) / "task" / str(tid) / "status").write_text(text)
        if tid == pid:
            (self.root / str(pid) / "status").write_text(text)

    def owner(self, tid):
        for d in self.root.iterdir():
            if (d / "task" / str(tid)).is_dir():
                return int(d.name)
        raise OSError(errno.ESRCH, "no such process")

    def cpuset(self, mask):
        """The kernel moving the app to another cpuset: every task's affinity becomes the new cpuset."""
        for d in self.root.iterdir():
            for task in (d / "task").iterdir():
                self.write(int(d.name), int(task.name), mask)

    def mask(self, pid, tid=None):
        text = (self.root / str(pid) / "task" / str(tid or pid) / "status").read_text()
        return text.rsplit("\t", 1)[1].strip()

    def setaffinity(self, tid, cores):
        if self.fail:
            raise OSError(self.fail, os.strerror(self.fail))
        self.calls.append((tid, set(cores)))
        self.write(self.owner(tid), tid, MODULE["format_cores"](cores))


class CoreWatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.proc = FakeProc(self.tmp.name)
        # game-env 100: Proton's python 200 starts the game 300 (pinned by Wine) and wineserver 400 (never pinned);
        # 500 is the thread placement helper.
        self.proc.process(100, 1, "python3", {100: "0-7"})
        self.proc.process(200, 100, "python3", {200: "0-7"})
        self.proc.process(300, 200, "SlayTheSpire2.e", {300: "2-7", 301: "2-7", 302: "2-7"})
        self.proc.process(400, 300, "wineserver", {400: "0-7"})
        self.proc.process(500, 100, "python3", {500: "0-7"})
        self.proc.process(900, 1, "steam", {900: "0-7"})
        self.now = [0.0]

    def tearDown(self):
        self.tmp.cleanup()

    def watch(self, plan=BIG):
        return MODULE["CoreWatch"](plan, APP, root=100, proc=self.tmp.name, setaffinity=self.proc.setaffinity,
                                   online=lambda: set(range(8)), speeds=lambda cores: TENSOR_G5_CAPACITY,
                                   clock=lambda: self.now[0], wall=lambda: 1794560200.0)

    def poll(self, watch, seconds=5):
        self.now[0] += seconds
        with mock.patch.object(sys, "stderr", new_callable=io.StringIO) as said:
            watch.poll()
        return said.getvalue()

    def test_a_cpuset_change_puts_the_game_back_on_the_big_cores_left(self):
        watch = self.watch()
        self.assertEqual(self.poll(watch), "")
        self.assertEqual(self.proc.calls, [])
        self.proc.cpuset("0-6")
        said = self.poll(watch)
        self.assertEqual(sorted(tid for tid, _ in self.proc.calls), [300, 301, 302])
        self.assertTrue(all(cores == {2, 3, 4, 5, 6} for _, cores in self.proc.calls))
        self.assertEqual(said, "DROIDDECK_TIMELINE event=game_cores_reset app_id=2868840 from=0-6 to=2-6 action=repin "
                               "threads=3 next_s=5 wall_ms=1794560200000\n")
        # Steam, wineserver, Proton's python and the helper keep the kernel's mask.
        for pid in (100, 200, 400, 500, 900):
            self.assertEqual(self.proc.mask(pid), "0-6")
        self.proc.calls.clear()
        self.assertEqual(self.poll(watch), "")
        self.assertEqual(self.proc.calls, [])
        # The cpuset grows back: the prime core comes back too.
        self.proc.cpuset("0-7")
        said = self.poll(watch)
        self.assertIn("from=0-7 to=2-7 action=repin threads=3", said)
        self.assertEqual(self.proc.mask(300, 302), "2-7")
        self.assertEqual(self.proc.mask(400), "0-7")

    def test_threads_started_after_the_reset_and_the_same_cpuset_again_are_pinned_too(self):
        watch = self.watch()
        self.poll(watch)
        self.proc.cpuset("0-7")
        self.proc.process(300, 200, "SlayTheSpire2.e", {303: "0-7"})
        said = self.poll(watch)
        self.assertIn("from=0-7 to=2-7 action=repin threads=4", said)

    def test_no_change_does_nothing(self):
        watch = self.watch()
        for _ in range(20):
            self.assertEqual(self.poll(watch), "")
        self.assertEqual(self.proc.calls, [])
        self.assertEqual(watch.interval, 5)

    def test_only_little_cores_left_leaves_the_kernels_mask(self):
        watch = self.watch()
        self.poll(watch)
        self.proc.cpuset("0-1")
        said = self.poll(watch)
        self.assertEqual(self.proc.calls, [])
        self.assertIn("from=0-1 to=0-1 action=kept", said)
        self.assertEqual(self.poll(watch), "")
        self.assertEqual(self.proc.mask(300, 301), "0-1")
        # One big core with the littles is still too few.
        self.proc.cpuset("0-2")
        self.assertIn("from=0-2 to=0-2 action=kept", self.poll(watch))
        self.assertEqual(self.proc.calls, [])
        self.proc.cpuset("0-3")
        self.assertIn("from=0-3 to=2-3 action=repin threads=3", self.poll(watch))

    def test_a_count_budget_keeps_that_many_of_the_fastest_and_never_widens(self):
        self.proc.process(300, 200, "SlayTheSpire2.e", {300: "2-3,7", 301: "2-3,7"})
        watch = self.watch({"cores": [7, 2, 3], "budget": "3", "base": None})
        self.poll(watch)
        self.proc.cpuset("0-6")
        self.assertIn("from=0-6 to=2-4 action=repin", self.poll(watch))
        picked = self.watch({"cores": [7, 0], "budget": "", "base": {0, 1, 7}})
        self.proc.process(300, 200, "SlayTheSpire2.e", {300: "0,7", 301: "0,7"})
        self.poll(picked)
        self.proc.cpuset("0-6")
        self.assertIn("from=0-6 to=0-1 action=repin", self.poll(picked))
        self.assertEqual(self.proc.mask(300), "0-1")

    def test_a_failing_pin_backs_off_then_stops(self):
        watch = self.watch()
        self.poll(watch)
        self.proc.cpuset("0-6")
        self.proc.fail = errno.EPERM
        said = self.poll(watch)
        self.assertIn("from=0-6 to=2-6 action=failed error=EPERM threads=3 next_s=10", said)
        self.assertEqual(watch.cores, {2, 3, 4, 5, 6, 7})
        self.assertIn("action=failed error=EPERM threads=3 next_s=20", self.poll(watch, 10))
        self.assertFalse(watch.done.is_set())
        self.assertIn("action=stopped error=EPERM threads=3 next_s=40", self.poll(watch, 20))
        self.assertTrue(watch.done.is_set())
        self.assertEqual(self.proc.mask(300), "0-6")

    def test_threads_that_exit_mid_pin_are_not_failures(self):
        watch = self.watch()
        self.poll(watch)
        self.proc.cpuset("0-6")
        self.proc.fail = errno.ESRCH
        said = self.poll(watch)
        self.assertIn("action=repin threads=0 next_s=5", said)
        self.assertEqual(watch.failed_rounds, 0)

    def test_a_flapping_mask_polls_less_often_then_calms_down(self):
        watch = self.watch()
        self.poll(watch)
        for mask in ("0-6", "0-7", "0-6"):
            self.proc.cpuset(mask)
            self.poll(watch)
        self.assertEqual(watch.interval, 10)
        for _ in range(29):
            self.poll(watch, 10)
        self.assertEqual(watch.interval, 10)
        self.poll(watch, 10)
        self.assertEqual(watch.interval, 5)

    def test_a_reused_pid_is_dropped_not_pinned(self):
        watch = self.watch()
        self.poll(watch)
        self.proc.process(300, 200, "other", {300: "0-6", 301: "0-6", 302: "0-6"}, started="9999")
        self.assertEqual(self.poll(watch), "")
        self.assertEqual(self.proc.calls, [])
        self.assertNotIn(300, watch.budgeted)
        self.assertEqual(watch.cores, {2, 3, 4, 5, 6, 7})

    def test_the_thread_stops_with_the_launch(self):
        watch = self.watch().start()
        watch.stop()
        watch.thread.join(2)
        self.assertFalse(watch.thread.is_alive())

    def test_format_cores(self):
        fmt = MODULE["format_cores"]
        self.assertEqual(fmt({0, 1, 2, 3, 4, 5, 6}), "0-6")
        self.assertEqual(fmt([7, 2, 3, 5]), "2-3,5,7")
        self.assertEqual(fmt(set()), "-")


class CoreWatchLaunchTest(unittest.TestCase):
    def topology(self, env):
        os_mod = MODULE["os"]
        env = dict(env, STEAM_COMPAT_DATA_PATH="/steam/compatdata/2868840")
        with mock.patch.dict(MODULE["cpu_topology"].__globals__, {"online_cores": lambda: set(range(8))}), \
                mock.patch.dict(MODULE["fastest_first"].__globals__, {"core_speeds": lambda cores: TENSOR_G5_CAPACITY}), \
                mock.patch.object(os_mod, "sched_getaffinity", return_value=set(range(8)), create=True), \
                mock.patch.object(sys, "stderr"):
            return MODULE["cpu_topology"](env, ["/missing/proton", "waitforexitandrun", "/game/game.exe"])

    def test_the_launch_hands_its_budget_to_the_watch_unless_turned_off(self):
        self.assertEqual(self.topology({"DROIDDECK_GAME_CPU_BUDGET": "big"}),
                         {"cores": [7, 2, 3, 4, 5, 6], "budget": "big", "base": None})
        self.assertIsNone(self.topology({"DROIDDECK_GAME_CPU_BUDGET": "all"}))
        self.assertIsNone(self.topology({"DROIDDECK_GAME_CPU_BUDGET": "0"}))
        self.assertIsNone(self.topology({"DROIDDECK_GAME_CPU_BUDGET": "big", "DROIDDECK_GAME_REPIN": "0"}))
        self.assertIsNone(self.topology({"DROIDDECK_GAME_CPU_BUDGET": "big", "WINE_CPU_TOPOLOGY": "2:0,1"}))

    def test_a_waited_launch_runs_the_watch_only_with_a_plan(self):
        made = []

        class Fake:
            def __init__(self, plan, app_id):
                made.append([plan, app_id, "made"])

            def start(self):
                made[-1].append("started")
                return self

            def stop(self):
                made[-1].append("stopped")

        with mock.patch.dict(MODULE, {"CoreWatch": Fake, "record_final_env": lambda *args: None}), \
                mock.patch.object(sys, "stderr", new_callable=io.StringIO):
            code = MODULE["start"]([sys.executable, "-c", "pass"], dict(os.environ), None, None, None, "42", True, BIG)
            self.assertEqual(code, 0)
            self.assertEqual(made, [[BIG, "42", "made", "started", "stopped"]])
            MODULE["start"]([sys.executable, "-c", "pass"], dict(os.environ), None, None, None, "42", True, None)
        self.assertEqual(len(made), 1)


if __name__ == "__main__":
    unittest.main()
