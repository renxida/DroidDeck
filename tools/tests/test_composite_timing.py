import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class CompositeTimingTest(unittest.TestCase):
    """droiddeck-session's composite_timing: asks gamescope for its composite GPU timestamps."""

    def run_timing(self, bin_dir, **env):
        source = SESSION.read_text()
        start = source.index("composite_timing() {")
        end = source.index("\n}\n", start) + 3
        script = "set -u\n" + source[start:end] + "composite_timing\nwait\n"
        full = {k: v for k, v in os.environ.items() if k != "GAMESCOPE_WAYLAND_DISPLAY"}
        full.update(PATH=str(bin_dir) + ":/usr/bin:/bin", **env)
        result = subprocess.run(["bash", "-c", script], env=full, text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.splitlines()

    def test_sets_the_log_convar_on_gamescope_display(self):
        with tempfile.TemporaryDirectory() as tmp:
            bin_dir = Path(tmp)
            calls = bin_dir / "calls"
            self.assertEqual(self.run_timing(bin_dir), ["== composite-timing: gamescope has no Wayland display"])
            self.assertEqual(self.run_timing(bin_dir, GAMESCOPE_WAYLAND_DISPLAY="gamescope-0"),
                             ["== composite-timing: gamescopectl missing"])
            ctl = bin_dir / "gamescopectl"
            ctl.write_text('#!/bin/sh\necho "$WAYLAND_DISPLAY $*" >> "%s"\n' % calls)
            ctl.chmod(0o755)
            self.assertEqual(self.run_timing(bin_dir, GAMESCOPE_WAYLAND_DISPLAY="gamescope-0"),
                             ["== composite-timing: gamescope composite GPU timestamps on"])
            self.assertEqual(calls.read_text(), "gamescope-0 log_composite_timing debug\n")


if __name__ == "__main__":
    unittest.main()
