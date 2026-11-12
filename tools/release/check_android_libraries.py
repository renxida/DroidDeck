"""Validate the Android ELF dependency closure in the actual APK."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile


SYSTEM = set('libc.so libm.so libdl.so liblog.so libandroid.so libz.so libvulkan.so libstdc++.so '
             'libGLESv2.so libGLESv3.so libEGL.so libnativewindow.so libjnigraphics.so libaaudio.so '
             'libOpenSLES.so libmediandk.so libcamera2ndk.so libsync.so libneuralnetworks.so'.split())


def check(apk, readelf):
    errors = []
    with tempfile.TemporaryDirectory() as temp, ZipFile(apk) as package:
        libraries = {}
        for name in package.namelist():
            if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
                path = Path(temp) / Path(name).name
                path.write_bytes(package.read(name))
                libraries[path.name] = path
        for name, path in libraries.items():
            dynamic = subprocess.check_output([readelf, '-d', str(path)], text=True)
            for dependency in re.findall(r'NEEDED.*\[(.*?)\]', dynamic):
                if dependency not in libraries and dependency not in SYSTEM:
                    errors.append(name + ' needs missing ' + dependency)
    return errors


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--readelf', default='readelf')
    args = parser.parse_args()
    errors = check(args.apk, args.readelf)
    if errors:
        parser.exit(1, '\n'.join(errors) + '\n')
    print('APK native dependencies verified')
