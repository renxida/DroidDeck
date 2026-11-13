import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import subprocess
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import telemetry_live  # noqa: E402


def live(history, ended=False):
    state = "session ended" if ended else "session running"
    return (f"# Kestrel live session status\nbuild: b\nstate: {state}\n\n## frames\n"
            "## history (uptime fps_30s p95_ms headroom cap battery_c)\n" + "".join(h + "\n" for h in history))


class FakeGh:
    def __init__(self, paths, files):
        self.paths = paths
        self.files = files
        self.calls = []

    def __call__(self, cmd, **_):
        self.calls.append(cmd)
        target = cmd[-1]
        if target == "repos/o/r":
            out = json.dumps({"default_branch": "main"})
        elif target.startswith("repos/o/r/git/trees/main"):
            out = json.dumps({"tree": [{"path": p, "type": "blob"} for p in self.paths()]})
        else:
            path = target.split("/contents/", 1)[1]
            out = self.files[path]() if callable(self.files[path]) else self.files[path]
        return subprocess.CompletedProcess(cmd, 0, out, "")


class TelemetryLiveTest(unittest.TestCase):
    def test_live_paths_keep_only_live_files_under_runs(self):
        tree = {"tree": [
            {"path": "runs/b1/dev/2026-11-13T10-00-00Z.zip", "type": "blob"},
            {"path": "runs/b1/dev/2026-11-13T10-00-00Z.live.txt", "type": "blob"},
            {"path": "runs/b1/dev", "type": "tree"},
            {"path": "config/kestrel.json", "type": "blob"},
            {"path": "notes/x.live.txt", "type": "blob"},
        ]}
        self.assertEqual(telemetry_live.live_paths(tree), ["runs/b1/dev/2026-11-13T10-00-00Z.live.txt"])

    def test_newest_is_the_latest_session_start_across_builds(self):
        paths = [
            "runs/perf-a-1111111/Google-Pixel-10/2026-11-13T10-00-00Z.live.txt",
            "runs/perf-b-2222222/Google-Pixel-10/2026-11-13T11-30-00Z.live.txt",
            "runs/perf-c-3333333/Samsung-S24/2026-11-12T23-59-59Z.live.txt",
            "runs/bad.live.txt",
        ]
        self.assertEqual(telemetry_live.newest(paths), paths[1])
        self.assertEqual(telemetry_live.newest(paths, build="perf-a"), paths[0])
        self.assertEqual(telemetry_live.newest(paths, device="s24"), paths[2])
        self.assertIsNone(telemetry_live.newest(paths, build="nope"))
        self.assertIsNone(telemetry_live.newest([]))

    def test_history_lines_and_new_ones(self):
        text = live(["+0:00:30 - - 0.40 - 35.0", "+0:01:00 59.9 18.2 0.45 60 36.0"])
        self.assertEqual(telemetry_live.history_lines(text), ["+0:00:30 - - 0.40 - 35.0", "+0:01:00 59.9 18.2 0.45 60 36.0"])
        self.assertEqual(telemetry_live.history_lines("no history here\n"), [])
        seen = {"+0:00:30 - - 0.40 - 35.0"}
        self.assertEqual(telemetry_live.new_history(seen, text), ["+0:01:00 59.9 18.2 0.45 60 36.0"])

    def test_prints_the_newest_file(self):
        path = "runs/b/d/2026-11-13T10-00-00Z.live.txt"
        gh = FakeGh(lambda: ["runs/b/d/2026-11-12T10-00-00Z.live.txt", path], {path: live(["+0:00:30 a"])})
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(telemetry_live.main(["--repo", "o/r"], runner=gh), 0)
        self.assertIn(f"== {path}", out.getvalue())
        self.assertIn("+0:00:30 a", out.getvalue())
        self.assertIn("Accept: application/vnd.github.raw+json", gh.calls[-1])

    def test_follow_prints_only_new_history_and_stops_at_session_end(self):
        path = "runs/b/d/2026-11-13T10-00-00Z.live.txt"
        versions = iter([live(["+0:00:30 a"]), live(["+0:00:30 a", "+0:01:00 b"]),
                         live(["+0:00:30 a", "+0:01:00 b", "+0:01:30 c"], ended=True)])
        gh = FakeGh(lambda: [path], {path: lambda: next(versions)})
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(telemetry_live.main(["--repo", "o/r", "--follow", "--count", "5"], runner=gh, sleep=lambda s: None), 0)
        text = out.getvalue()
        followed = text.split("+0:00:30 a\n", 1)[1]
        self.assertEqual(followed, "+0:01:00 b\n+0:01:30 c\n(session ended)\n")

    def test_follow_switches_to_a_newer_session(self):
        old, new = "runs/b/d/2026-11-13T10-00-00Z.live.txt", "runs/b/d/2026-11-13T11-00-00Z.live.txt"
        listings = iter([[old], [old, new]])
        gh = FakeGh(lambda: next(listings), {old: live(["+0:00:30 a"]), new: live(["+0:00:30 z"])})
        out = io.StringIO()
        with redirect_stdout(out):
            telemetry_live.main(["--repo", "o/r", "--follow", "--count", "1"], runner=gh, sleep=lambda s: None)
        self.assertIn(f"== newer session: {new}", out.getvalue())
        self.assertIn("+0:00:30 z", out.getvalue())


if __name__ == "__main__":
    unittest.main()
