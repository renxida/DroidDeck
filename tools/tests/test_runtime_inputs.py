import importlib.util
import io
from pathlib import Path, PureWindowsPath
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from unittest import mock
from zipfile import ZipFile


spec = importlib.util.spec_from_file_location('runtime_inputs', Path(__file__).resolve().parents[1] / 'release/runtime_inputs.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Component archive validation has its own binary/archive fixtures.
        component_check = patch.object(runtime, 'verify_powervr')
        component_check.start()
        self.addCleanup(component_check.stop)
        paths = runtime.outputs(self.root)
        for name in runtime.required():
            path = self.root / paths.get(name, 'app/src/main/' + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'prepared runtime')
        self.source = self.root / 'tools/linuxfs/preload/test.c'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('current source')
        self.audio(['pactl', 'modules/arm64/module-aaudio-sink.so', 'modules/arm64/module-directaudio-native-sink.so'])
        self.packs()

    def pack(self, name, files):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w') as archive:
            for rel, content in files.items():
                entry = tarfile.TarInfo(rel)
                entry.size = len(content)
                archive.addfile(entry, io.BytesIO(content))
        path = self.root / runtime.PACKS / (name + '.tar.zst')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(subprocess.run(['zstd', '-q', '-c'], input=stream.getvalue(), capture_output=True, check=True).stdout)

    def packs(self):
        proot = {}
        for name, (pack, rel) in runtime.PACKED_FILES.items():
            proot[rel] = (self.root / runtime.outputs(self.root)[name]).read_bytes()
        self.pack('proot', proot)
        for prefix, name in runtime.PACKED.items():
            base = self.root / 'app/src/main' / prefix
            self.pack(name, {path.relative_to(base).as_posix(): path.read_bytes() for path in base.rglob('*') if path.is_file()})

    @staticmethod
    def in_apk(name):
        return name not in runtime.PACKED_FILES and not any(name.startswith(prefix) for prefix in runtime.PACKED)

    def audio(self, names):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w') as archive:
            for name in names:
                entry = tarfile.TarInfo('./' + name)
                entry.size = 1
                archive.addfile(entry, io.BytesIO(b'x'))
        path = self.root / 'app/build/prepared-assets/pulseaudio.tzst'
        path.write_bytes(subprocess.run(['zstd', '-q', '-c'], input=stream.getvalue(), capture_output=True, check=True).stdout)

    def test_an_apk_still_carrying_proot_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk', {'lib/arm64-v8a/libproot.so': b'prepared runtime'})
        with self.assertRaisesRegex(ValueError, 'proot pack provides'):
            runtime.check_apk(apk, self.root)

    def test_missing_proot_cannot_be_attested(self):
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').unlink()
        with self.assertRaisesRegex(ValueError, 'libproot.so'):
            runtime.record(self.root)

    def test_base_audio_without_compiled_sinks_is_rejected(self):
        self.audio(['pactl'])
        with self.assertRaisesRegex(ValueError, 'module-aaudio-sink'):
            runtime.record(self.root)

    def test_changed_native_source_rejects_old_outputs(self):
        runtime.record(self.root)
        self.source.write_text('new implementation')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_dev_loop_can_reuse_assets_from_other_sources(self):
        runtime.record(self.root)
        self.source.write_text('new implementation')
        with mock.patch('sys.stderr', new_callable=io.StringIO) as stderr:
            runtime.verify(self.root, stale_sources_ok=True)
        self.assertIn('tools/linuxfs/preload/test.c', stderr.getvalue())

    def test_dev_loop_still_rejects_changed_outputs(self):
        runtime.record(self.root)
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').write_bytes(b'old APK binary')
        with self.assertRaisesRegex(ValueError, 'changed runtime input'):
            runtime.verify(self.root, stale_sources_ok=True)

    def test_changed_quest_source_rejects_old_outputs(self):
        source = self.root / 'tools/quest/kgsl_compat.c'
        source.parent.mkdir(parents=True)
        source.write_text('old compatibility code')
        runtime.record(self.root)
        source.write_text('new compatibility code')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_missing_quest_driver_cannot_be_attested(self):
        (self.root / 'app/build/prepared-assets/graphics_driver/adrenotools-turnip25.1.0-quest1.tzst').unlink()
        with self.assertRaisesRegex(ValueError, 'quest1.tzst'):
            runtime.record(self.root)

    def test_changed_powervr_wrapper_rejects_old_outputs(self):
        wrapper = self.root / 'tools/hybris/run-proton.py'
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text('current wrapper')
        runtime.record(self.root)
        wrapper.write_text('changed wrapper')
        with self.assertRaisesRegex(ValueError, 'sources changed'):
            runtime.verify(self.root)

    def test_missing_powervr_bundle_cannot_be_attested(self):
        (self.root / 'app/src/main/assets/hybris/runtime.tzst').unlink()
        with self.assertRaisesRegex(ValueError, 'assets/hybris/runtime.tzst'):
            runtime.record(self.root)

    def test_modified_prebuilt_binary_is_rejected(self):
        runtime.record(self.root)
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libproot.so').write_bytes(b'old APK binary')
        with self.assertRaisesRegex(ValueError, 'changed runtime input'):
            runtime.verify(self.root)

    def test_an_old_apk_is_rejected_even_when_staging_is_current(self):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        apk = self.root / 'old.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if not self.in_apk(name):
                    continue
                content = b'stale' if name == 'assets/linuxfs/libssbs.so' else (self.root / output['path']).read_bytes()
                package.writestr(name, content)
        with self.assertRaisesRegex(ValueError, 'stale runtime input'):
            runtime.check_apk(apk, self.root)

    def test_complete_current_payload_passes(self):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        apk = self.root / 'current.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if self.in_apk(name):
                    package.writestr(name, (self.root / output['path']).read_bytes())
        runtime.check_apk(apk, self.root)

    def current_apk(self, path, extra=None):
        runtime.record(self.root)
        data = runtime.verify(self.root)
        with ZipFile(path, 'w') as package:
            for name, output in data['outputs'].items():
                if self.in_apk(name):
                    package.writestr(name, (self.root / output['path']).read_bytes())
            for name, content in (extra or {}).items():
                package.writestr(name, content)
        return path

    def test_an_apk_still_carrying_a_packed_tree_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk', {'assets/directaudio/linux-wine11/version.txt': b'prepared runtime'})
        with self.assertRaisesRegex(ValueError, 'directaudio pack provides'):
            runtime.check_apk(apk, self.root)

    def test_an_apk_still_carrying_the_powervr_runtime_is_rejected(self):
        apk = self.current_apk(self.root / 'bundled.apk', {'assets/hybris/runtime.tzst': b'runtime'})
        with self.assertRaisesRegex(ValueError, 'powervr pack provides'):
            runtime.check_apk(apk, self.root)

    def test_a_stale_pack_is_rejected(self):
        apk = self.current_apk(self.root / 'current.apk')
        self.pack('powervr', {'runtime.tzst': b'old build', 'version.txt': b'old'})
        with self.assertRaisesRegex(ValueError, 'powervr is missing or has a stale'):
            runtime.check_apk(apk, self.root)

    def test_a_missing_pack_is_rejected(self):
        apk = self.current_apk(self.root / 'current.apk')
        (self.root / runtime.PACKS / 'powervr.tar.zst').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing add-on pack powervr'):
            runtime.check_apk(apk, self.root)

    def test_windows_manifest_matches_portable_apk_paths(self):
        path_type = type(self.root)
        original_relative_to = path_type.relative_to

        def windows_relative_to(path, *args, **kwargs):
            return PureWindowsPath(original_relative_to(path, *args, **kwargs))

        with mock.patch.object(path_type, 'relative_to', windows_relative_to):
            runtime.record(self.root)
        data = runtime.verify(self.root)
        self.assertIn('tools/linuxfs/preload/test.c', data['sources'])
        nested_asset = 'assets/linuxfs/usr/local/bin/gamescope'
        self.assertEqual(data['outputs'][nested_asset]['path'], 'app/src/main/' + nested_asset)
        for name in data['sources']:
            self.assertNotIn('\\', name)
        apk = self.root / 'portable.apk'
        with ZipFile(apk, 'w') as package:
            for name, output in data['outputs'].items():
                if not self.in_apk(name):
                    continue
                self.assertNotIn('\\', name)
                self.assertNotIn('\\', output['path'])
                package.writestr(name, (self.root / output['path']).read_bytes())
        runtime.check_apk(apk, self.root)

