from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release"))
import apk_size_report


class ApkSizeReportTest(unittest.TestCase):
    def test_groups_and_ranks_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "a.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", b"d" * 1000, zipfile.ZIP_DEFLATED)
                z.writestr("lib/arm64-v8a/libbig.so", bytes(range(256)) * 400, zipfile.ZIP_STORED)
                z.writestr("assets/packs/proot.tar.zst", b"p" * 50, zipfile.ZIP_STORED)
                z.writestr("assets/top.txt", b"t", zipfile.ZIP_STORED)
                z.writestr("res/raw/x", b"r" * 10, zipfile.ZIP_STORED)
            rows = apk_size_report.entries(apk)
            text = apk_size_report.report(rows, top=2)

        self.assertEqual(apk_size_report.group_of("lib/arm64-v8a/libbig.so"), "lib/arm64-v8a/")
        self.assertEqual(apk_size_report.group_of("assets/packs/proot.tar.zst"), "assets/packs/")
        self.assertEqual(apk_size_report.group_of("assets/top.txt"), "assets/")
        self.assertEqual(apk_size_report.group_of("classes2.dex"), "*.dex")
        self.assertEqual(apk_size_report.group_of("res/raw/x"), "res/")
        self.assertIn("entries 5", text)
        compressed = text.split("top 2 by compressed")[1].split("top 2 by uncompressed")[0]
        self.assertIn("libbig.so", compressed.splitlines()[1])
        self.assertEqual(len([l for l in compressed.splitlines()[1:] if l.strip()]), 2)
        uncompressed = text.split("top 2 by uncompressed")[1]
        self.assertIn("libbig.so", uncompressed.splitlines()[1])
        self.assertIn("classes.dex", uncompressed.splitlines()[2])


if __name__ == "__main__":
    unittest.main()
