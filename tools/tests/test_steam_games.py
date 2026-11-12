import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

BIN = Path(__file__).resolve().parents[1] / 'linuxfs/overlay/usr/local/bin'


def load(name):
    loader = importlib.machinery.SourceFileLoader(name, str(BIN / name))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


probe = load('droiddeck-steam-games')
library = load('droiddeck-steam-library')


class LibraryRenameTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.steam = Path(temp.name)
        self.vdf = self.steam / 'steamapps/libraryfolders.vdf'
        self.vdf.parent.mkdir()

    def entry(self, index, path):
        return '\t"%d"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"contentid"\t\t"%d"\n\t}\n' % (index, path, 7 + index)

    def test_old_mount_keeps_its_entry_under_the_new_path(self):
        self.vdf.write_text('"libraryfolders"\n{\n' + self.entry(0, '/root') + self.entry(1, '/mnt/bannerlator-sd') + '}\n')
        with patch('builtins.print'):
            library.rename(str(self.steam), '/mnt/bannerlator-sd', '/mnt/droiddeck-sd')
        text = self.vdf.read_text()
        self.assertIn('"path"\t\t"/mnt/droiddeck-sd"\n\t\t"contentid"\t\t"8"', text)
        self.assertNotIn('bannerlator-sd', text)

    def test_old_mount_is_dropped_when_new_path_is_registered(self):
        self.vdf.write_text('"libraryfolders"\n{\n' + self.entry(0, '/mnt/droiddeck-sd') + self.entry(1, '/mnt/bannerlator-sd') + '}\n')
        with patch('builtins.print'):
            library.rename(str(self.steam), '/mnt/bannerlator-sd', '/mnt/droiddeck-sd')
        text = self.vdf.read_text()
        self.assertEqual(1, text.count('"path"'))
        self.assertIn('/mnt/droiddeck-sd', text)


class SteamProbeTest(unittest.TestCase):
    def test_probe_reads_account_and_licenses_and_releases_handles(self):
        import ctypes as C
        from unittest.mock import Mock
        objects, callbacks, calls = [], [], []

        def interface(slots):
            table = (C.c_void_p * 24)()
            for slot, result, args, callback in slots:
                fn = C.CFUNCTYPE(result, C.c_void_p, *args)(callback)
                callbacks.append(fn)
                table[slot] = C.cast(fn, C.c_void_p).value
            obj = C.pointer(C.cast(table, C.POINTER(C.c_void_p)))
            objects.extend([table, obj])
            return C.cast(obj, C.c_void_p).value

        user = interface([
            (1, C.c_bool, [], lambda _: True),
            (2, C.c_uint64, [], lambda _: probe.BASE_ID + 123),
        ])
        apps = interface([(6, C.c_bool, [C.c_uint32], lambda _, appid: appid == 42)])
        client = interface([
            (0, C.c_int, [], lambda _: 7),
            (1, C.c_bool, [C.c_int], lambda _, pipe: calls.append(('pipe', pipe)) or True),
            (2, C.c_int, [C.c_int], lambda _, pipe: 9),
            (4, None, [C.c_int, C.c_int], lambda _, pipe, handle: calls.append(('user', pipe, handle))),
            (5, C.c_void_p, [C.c_int, C.c_int, C.c_char_p], lambda _, handle, pipe, version: user if version == b'SteamUser021' else 0),
            (15, C.c_void_p, [C.c_int, C.c_int, C.c_char_p], lambda _, handle, pipe, version: apps if version == b'STEAMAPPS_INTERFACE_VERSION008' else 0),
        ])
        library = Mock()
        library.CreateInterface.return_value = client
        with patch.object(probe.C, 'CDLL', return_value=library):
            result = probe.probe('/Steam', [42, 43])
        self.assertEqual('123', result['account'])
        self.assertEqual({'42': True, '43': False}, result['owned'])
        self.assertEqual([('user', 7, 9), ('pipe', 7)], calls)
        library.CreateInterface.assert_called_once_with(b'SteamClient020', None)


if __name__ == '__main__':
    unittest.main()
