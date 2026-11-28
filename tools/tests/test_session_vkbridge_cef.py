import os
import re
import subprocess
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"
GB = 1024 * 1024


def function():
    match = re.search(r"^bl_vkbridge_cef_gpu\(\) \{\n.*?^\}\n", SESSION.read_text(), re.M | re.S)
    return match.group(0) if match else None


class VkBridgeCefTest(unittest.TestCase):
    def gpu(self, mem_kb, setting=None):
        self.assertIsNotNone(function(), "droiddeck-session lacks bl_vkbridge_cef_gpu()")
        env = {k: v for k, v in os.environ.items() if k != "BL_VKBRIDGE_CEF_GPU"}
        if setting is not None:
            env["BL_VKBRIDGE_CEF_GPU"] = setting
        return subprocess.run(["bash", "-c", "set -u\n" + function() + 'bl_vkbridge_cef_gpu "$1"\n', "_", str(mem_kb)],
                              env=env, capture_output=True, text=True, check=True).stdout.strip()

    def test_eight_gigabytes_or_less_draws_the_browser_in_software(self):
        self.assertEqual("0", self.gpu(2863404))          # the 3 GB Mali-G76 the rule was written for
        self.assertEqual("0", self.gpu(7752904))          # Pixel 10a
        self.assertEqual("0", self.gpu(8 * GB))

    def test_more_memory_keeps_the_browser_on_the_gpu(self):
        self.assertEqual("1", self.gpu(8 * GB + 1))
        self.assertEqual("1", self.gpu(11591512))         # a "12 GB" phone
        self.assertEqual("1", self.gpu(15 * GB))

    def test_an_unreadable_size_changes_nothing(self):
        self.assertEqual("1", self.gpu(0))
        self.assertEqual("1", self.gpu(""))

    def test_the_setting_wins_in_both_directions(self):
        self.assertEqual("1", self.gpu(7752904, "1"))
        self.assertEqual("0", self.gpu(15 * GB, "0"))
        self.assertEqual("1", self.gpu(2863404, "1"))

    def test_the_steam_block_uses_it_inside_the_bridge_branch(self):
        text = SESSION.read_text()
        block = text[text.index('if [ "${BL_VKBRIDGE:-0}" = 1 ]; then\n      # On the GPU the Steam UI'):]
        block = block[:block.index("BL_WEBHELPER_ARGS=$(bl_webhelper_layout)")]
        self.assertIn('BL_VKBRIDGE_CEF_GPU=$(bl_vkbridge_cef_gpu "${_bl_mem_kb:-0}")', block)
        self.assertIn('_bl_cef=(-cef-disable-gpu -cef-disable-gpu-compositing)', block)
        self.assertNotIn("4194304", text)


if __name__ == "__main__":
    unittest.main()
