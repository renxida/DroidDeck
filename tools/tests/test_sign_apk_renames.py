import re
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = subprocess.run([str(ROOT / 'tools/app-id')], capture_output=True, text=True, check=True).stdout.strip()


class SignApkRenamesTest(unittest.TestCase):
    def test_variants_rename_every_authority_and_task_named_after_the_package(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        names = set()
        for attribute in ('authorities', 'taskAffinity'):
            for value in re.findall(r'android:%s="([^"]*)"' % attribute, manifest):
                self.assertNotIn(';', value, 'sign-apk.sh renames whole values, so give each authority its own provider')
                name = value.strip().replace('${applicationId}', PACKAGE)
                if name.startswith(PACKAGE + '.'):
                    names.add(name[len(PACKAGE):])
        self.assertIn('.agent', names)
        script = (ROOT / 'tools/release/sign-apk.sh').read_text()
        renamed = set(re.findall(r'"\$P(\.[A-Za-z0-9_.-]+)=\$pkg\1"', script))
        self.assertEqual(set(), names - renamed, 'add these to the axml_rename call in tools/release/sign-apk.sh')

    def test_the_standard_variant_is_the_built_application_id(self):
        self.assertEqual('dev.renxida.kestrel', PACKAGE)
        rows = [line.split() for line in (ROOT / 'tools/release/variants.txt').read_text().splitlines()
                if line.strip() and not line.startswith('#')]
        self.assertIn(['standard', PACKAGE, '-'], rows)


if __name__ == '__main__':
    unittest.main()
