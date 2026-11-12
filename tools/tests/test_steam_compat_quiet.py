import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))
GLOBALS = LIVE["Helper"].serve.__globals__


class Stop(BaseException):
    pass


class DeferDuringGameTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = tmp.name
        self.running = [True]
        self.clock = [1000.0]
        self.said = []
        self.helper = LIVE["Helper"]("/steam", 1)
        self.helper.reconcile = mock.Mock()
        for patcher in (
            mock.patch.dict(GLOBALS["COMPAT"], {"game_running": lambda: self.running[0],
                                                "state_path": lambda name: str(Path(self.dir, name))}),
            mock.patch.dict(GLOBALS, {"say": self.said.append}),
            mock.patch.object(GLOBALS["time"], "monotonic", lambda: self.clock[0]),
            mock.patch.object(GLOBALS["time"], "sleep", lambda seconds: None),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def serve(self, changes, ticks=None):
        """Run the serve loop with the watcher reporting `changes` one tick at a time; `ticks` runs before each tick."""
        helper = self.helper
        page = mock.Mock(events=[])
        page.ws.pending.return_value = False
        page.drain.return_value = []
        helper.page = page
        helper.sync_controls = mock.Mock()
        helper.sync_frame_limit = mock.Mock()
        helper.sync_windows = mock.Mock()
        steps = iter(changes)

        def tick():
            if ticks:
                ticks()
        helper.ensure_tools = mock.Mock(side_effect=lambda: tick())
        watcher = mock.Mock()
        watcher.fileno.return_value = -1

        def changed():
            try:
                return next(steps)
            except StopIteration:
                raise Stop()
        watcher.changed.side_effect = changed
        with mock.patch.object(GLOBALS["select"], "select", return_value=([], [], [])):
            with self.assertRaises(Stop):
                helper.serve(watcher)

    def test_steam_file_writes_wait_for_the_game_and_run_once_after_it(self):
        def exits_after_two_ticks():
            if len(self.helper.reconcile.call_args_list) == 1 and self.helper.deferred == 2:
                self.running[0] = False
                self.clock[0] += LIVE["GAME_EXIT_CHECK_SECONDS"]
        self.serve([{"appinfo.vdf"}, set(), {"appmanifest_2868840.acf", "libraryfolders.vdf"}, set(), set(), set()],
                   exits_after_two_ticks)
        self.assertEqual(self.helper.reconcile.call_count, 2)
        self.assertEqual(self.helper.deferred, 0)
        self.assertIn("reconcile deferred while a game runs (appinfo.vdf; 1 deferred so far)", self.said)
        self.assertIn("reconcile deferred while a game runs (appmanifest_2868840.acf, libraryfolders.vdf; 2 deferred so far)",
                      self.said)
        self.assertIn("game exited; one reconcile for 2 deferred during the game", self.said)

    def test_requests_from_the_launcher_run_at_once_during_a_game(self):
        self.serve([{"appinfo.vdf"}, {"request.json"}, {"games.json"}, set()])
        self.assertEqual(self.helper.reconcile.call_count, 3)
        self.assertEqual(self.helper.deferred, 0)
        self.assertIn("reconcile for request.json also covers 1 deferred during the game", self.said)

    def test_a_change_made_in_the_steam_menu_runs_at_once_during_a_game(self):
        self.helper.reconcile_or_defer("Steam menu change", True)
        self.assertEqual(self.helper.reconcile.call_count, 1)

    def test_nothing_waits_without_a_game(self):
        self.running[0] = False
        self.serve([{"appinfo.vdf"}, set()])
        self.assertEqual(self.helper.reconcile.call_count, 2)
        self.assertEqual(self.helper.deferred, 0)

    def test_the_game_is_checked_at_most_every_few_seconds_while_changes_wait(self):
        checks = []
        GLOBALS["COMPAT"]["game_running"] = lambda: checks.append(1) or True
        self.helper.reconcile_or_defer("appinfo.vdf", False)
        for _ in range(10):
            self.helper.reconcile_after_game()
        self.assertEqual(len(checks), 1)
        self.clock[0] += LIVE["GAME_EXIT_CHECK_SECONDS"]
        self.helper.reconcile_after_game()
        self.assertEqual(len(checks), 2)
        self.helper.reconcile.assert_not_called()


class WatcherNamesTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "state").mkdir()
        (root / "appcache").mkdir()
        self.request = root / "state/request.json"
        self.appinfo = root / "appcache/appinfo.vdf"
        self.appinfo.write_bytes(b"v1")

    def watcher(self, polling):
        watcher = LIVE["Watcher"]([str(self.request), str(self.appinfo)])
        if polling and watcher.fd >= 0:
            os.close(watcher.fd)
            watcher.fd = -1
        elif watcher.fd >= 0:
            self.addCleanup(os.close, watcher.fd)
        return watcher

    def bump(self, path, data):
        path.write_bytes(data)
        stamp = path.stat().st_mtime_ns + 10**9
        os.utime(path, ns=(stamp, stamp))

    def test_the_watcher_names_what_changed(self):
        for polling in (False, True):
            with self.subTest(polling=polling):
                watcher = self.watcher(polling)
                self.assertEqual(watcher.changed(), set())
                self.bump(self.appinfo, b"v2")
                self.assertEqual(watcher.changed(), {"appinfo.vdf"})
                self.bump(self.request, b"{}")
                self.assertEqual(watcher.changed(), {"request.json"})


class TimestampTest(unittest.TestCase):
    def test_lines_carry_the_time_of_day(self):
        with mock.patch("builtins.print") as printed:
            LIVE["say"]("attached")
        line = printed.call_args[0][0]
        self.assertRegex(line, r"^droiddeck-steam-compat: \d\d:\d\d:\d\d\.\d{3} attached$")


if __name__ == "__main__":
    unittest.main()
