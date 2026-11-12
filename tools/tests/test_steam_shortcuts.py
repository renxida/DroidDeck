import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name, str(BIN / name))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


shortcuts = load('droiddeck-steam-shortcuts')


class AccountTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.steam = Path(self.temp.name) / 'Steam'
        (self.steam / 'userdata/123/config').mkdir(parents=True)

    def users(self, *blocks):
        body = ''.join('\t"%d"\n\t{\n%s\t}\n' % (76561197960265728 + acct, ''.join('\t\t"%s"\t\t"%s"\n' % kv for kv in keys.items()))
                       for acct, keys in blocks)
        (self.steam / 'config').mkdir(exist_ok=True)
        (self.steam / 'config/loginusers.vdf').write_text('"users"\n{\n%s}\n' % body)
        (self.steam / 'userdata/456/config').mkdir(parents=True, exist_ok=True)

    def test_the_account_marked_most_recent_is_used(self):
        self.users((123, dict(MostRecent='1', AutoLogin='0', timestamp='100')), (456, dict(MostRecent='0', AutoLogin='1', timestamp='200')))
        self.assertEqual(str(self.steam / 'userdata/123'), shortcuts.account_dir(str(self.steam)))

    def test_without_most_recent_the_account_that_signs_in_is_used(self):
        self.users((123, dict(AutoLogin='1', timestamp='100')), (456, dict(AutoLogin='0', timestamp='200')))
        self.assertEqual(str(self.steam / 'userdata/123'), shortcuts.account_dir(str(self.steam)))

    def test_without_either_mark_the_account_used_last_is_used(self):
        self.users((456, dict(AutoLogin='0', timestamp='100')), (123, dict(AutoLogin='0', timestamp='200')))
        self.assertEqual(str(self.steam / 'userdata/123'), shortcuts.account_dir(str(self.steam)))

    def test_two_accounts_without_any_mark_name_no_account(self):
        self.users((123, dict(AccountName='a')), (456, dict(AccountName='b')))
        self.assertIsNone(shortcuts.account_dir(str(self.steam)))

    def test_shortcuts_survive_a_write_and_read(self):
        data = {'shortcuts': {'0': {'appid': -1561598272, 'AppName': 'Batman', 'Exe': '"C:\\Batman.exe"', 'tags': {}}}}
        self.assertEqual(data, shortcuts.parse(shortcuts.write(data)))


if __name__ == '__main__':
    unittest.main()
