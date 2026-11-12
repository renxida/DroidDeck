import os
from pathlib import Path
import subprocess
import unittest


SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"


class GamescopeBackendTest(unittest.TestCase):
    def select(self, backend=None):
        source = SESSION.read_text()
        start = source.index('  backend=${BL_GAMESCOPE_BACKEND:-wayland}')
        end = source.index('\n', source.index('  args=(--backend ', start))
        script = 'width=1280; height=720; nested=1280\n' + source[start:end]
        script += '\nprintf "%s\\n" "${args[0]}" "${args[1]}" "${SDL_VIDEODRIVER:-unset}"\n'
        env = dict(os.environ)
        env.pop("BL_GAMESCOPE_BACKEND", None)
        env.pop("SDL_VIDEODRIVER", None)
        if backend is not None:
            env["BL_GAMESCOPE_BACKEND"] = backend
        return subprocess.run(["bash", "-c", script], env=env, text=True,
                              capture_output=True, timeout=5)

    def test_default_retains_the_wayland_backend(self):
        result = self.select()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "wayland", "unset"], result.stdout.splitlines())

    def test_sdl_uses_the_host_wayland_vulkan_swapchain(self):
        result = self.select("sdl")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["--backend", "sdl", "wayland"], result.stdout.splitlines())

    def test_unknown_backend_fails_before_gamescope(self):
        result = self.select("drm")
        self.assertEqual(64, result.returncode)
        self.assertIn("unsupported gamescope backend: drm", result.stdout)
