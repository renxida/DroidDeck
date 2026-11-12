import os
from pathlib import Path
import subprocess
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


class SteamLaunchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.session = self.root / 'session'
        self.session.mkdir()
        self.env = dict(os.environ, HOME=str(self.root), BL_LAUNCH_DIR=str(self.session))

    def launch(self, *args):
        return subprocess.run(['bash', str(BIN / 'droiddeck-steam-launch'), *args], env=self.env,
                              text=True, capture_output=True)

    def test_big_picture_requests_explicit_handoff(self):
        result = self.launch('bigpicture')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.session / 'steam-launch').read_text(), 'ui=bigpicture\nurl=\n')

    def test_the_session_request_keeps_its_ui_and_url(self):
        result = self.launch('desktop', 'steam://open/settings')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.session / 'steam-launch').read_text(),
                         'ui=desktop\nurl=steam://open/settings\n')

    def test_an_app_id_becomes_a_run_url(self):
        self.assertEqual(self.launch('bigpicture', '413150').returncode, 0)
        self.assertEqual((self.session / 'steam-launch').read_text(), 'ui=bigpicture\nurl=steam://rungameid/413150\n')

    def test_invalid_target_cannot_trigger_a_handoff(self):
        self.assertEqual(self.launch('desktop', 'bad').returncode, 64)
        self.assertFalse((self.session / 'steam-launch').exists())


if __name__ == '__main__':
    unittest.main()
