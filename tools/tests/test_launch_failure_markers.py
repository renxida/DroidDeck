import re
import runpy
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / "tools/linuxfs/overlay/usr/local/bin"
SESSION = (BIN / "droiddeck-session").read_text()
KOTLIN = (ROOT / "app/src/main/java/com/droiddeck/launcher/session/SessionLaunchFailure.kt").read_text()


def constant(name):
    return re.search(r'const val %s = "([^"]+)"' % name, KOTLIN).group(1)


class LaunchFailureMarkerTest(unittest.TestCase):
    """The app reads these lines from session.log; the guest scripts must print them verbatim."""

    def test_the_session_says_once_gamescope_started_it(self):
        line = 'echo "%s"\n' % constant("COMPOSITOR_UP")
        self.assertEqual(SESSION.count(line), 1)
        outer = SESSION.index('  exec gamescope "${args[@]}" -- "$0" "$mode" "$@"\nfi\n')
        marker = SESSION.index(line)
        self.assertLess(outer, marker)
        self.assertLess(marker, SESSION.index("\nstart_agent\n", outer))
        self.assertLess(marker, SESSION.index("\ncase $mode in\n"))

    def test_proton_run_names_the_missing_steam_client_as_the_app_expects(self):
        run = runpy.run_path(str(BIN / "droiddeck-proton-run"))
        said = "droiddeck-proton-run: %s (no steamclient.so)" % run["STEAM_CLIENT_MISSING"]
        self.assertTrue(said.startswith(constant("STEAM_CLIENT_MISSING_MARKER")))


if __name__ == "__main__":
    unittest.main()
