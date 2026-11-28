import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class HybrisIndexSessionTest(unittest.TestCase):
    """droiddeck-session's hybris_index_session: the session-wide swap of the PowerVR compat layer."""

    def run_swap(self, layer_dir, **env):
        source = SESSION.read_text()
        start = source.index("hybris_index_session() {")
        end = source.index("\n}\n", start) + 3
        script = "set -u\n" + source[start:end] + (
            'if [ "${DROIDDECK_EXP_HYBRIS_INDEX_SESSION:-}" = 1 ]; then hybris_index_session; fi\n'
            'printf "%s\\n" "$VK_INSTANCE_LAYERS" "${DROIDDECK_HYBRIS_LAYERS:-unset}" "$VK_LAYER_PATH" '
            '"${DROIDDECK_HYBRIS_LAYER_PATH:-unset}"\n')
        full = {k: v for k, v in os.environ.items()
                if k not in ("DROIDDECK_HYBRIS_LAYERS", "DROIDDECK_HYBRIS_LAYER_PATH", "DROIDDECK_EXP_HYBRIS_INDEX_SESSION")}
        full.update(HYBRIS_COMPAT_DIR=str(layer_dir), VK_LAYER_PATH="/rt/lib", **env)
        result = subprocess.run(["bash", "-c", script], env=full, text=True, capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.splitlines()

    def test_swaps_the_session_and_game_layers_when_on_and_installed(self):
        with tempfile.TemporaryDirectory() as tmp:
            layer_dir = Path(tmp)
            base = dict(VK_INSTANCE_LAYERS="VK_LAYER_HYBRIS_compat", DROIDDECK_HYBRIS_LAYERS="VK_LAYER_HYBRIS_compat")
            self.assertEqual(self.run_swap(layer_dir, **base),
                             ["VK_LAYER_HYBRIS_compat", "VK_LAYER_HYBRIS_compat", "/rt/lib", "unset"])
            on = dict(base, DROIDDECK_EXP_HYBRIS_INDEX_SESSION="1")
            self.assertEqual(self.run_swap(layer_dir, **on)[:4],
                             ["== hybris-index-session: layer not installed at %s" % layer_dir,
                              "VK_LAYER_HYBRIS_compat", "VK_LAYER_HYBRIS_compat", "/rt/lib"])
            (layer_dir / "libVkLayer_droiddeck_hybris_compat.so").write_bytes(b"\x7fELF")
            lines = self.run_swap(layer_dir, **on)
            self.assertIn("indexed hybris compat layer", lines[0])
            self.assertEqual(lines[1:], ["VK_LAYER_DROIDDECK_hybris_compat", "VK_LAYER_DROIDDECK_hybris_compat",
                                         "%s:/rt/lib" % layer_dir, str(layer_dir)])

    def test_xclipse_sessions_are_left_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            layer_dir = Path(tmp)
            (layer_dir / "libVkLayer_droiddeck_hybris_compat.so").write_bytes(b"\x7fELF")
            lines = self.run_swap(layer_dir, DROIDDECK_EXP_HYBRIS_INDEX_SESSION="1",
                                  VK_INSTANCE_LAYERS="VK_LAYER_DROIDDECK_xclipse",
                                  DROIDDECK_HYBRIS_LAYERS="VK_LAYER_DROIDDECK_xclipse:VK_LAYER_HYBRIS_compat",
                                  DROIDDECK_HYBRIS_LAYER_PATH="/x")
            self.assertIn("no hybris compat layer", lines[0])
            self.assertEqual(lines[1:], ["VK_LAYER_DROIDDECK_xclipse", "VK_LAYER_DROIDDECK_xclipse:VK_LAYER_HYBRIS_compat",
                                         "/rt/lib", "/x"])


if __name__ == "__main__":
    unittest.main()
