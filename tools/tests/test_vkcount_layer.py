import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


TOOLS = Path(__file__).resolve().parents[1]
LAYER = TOOLS / "linuxfs/vkcount-layer"
LAVAPIPE = next((p for p in (Path("/usr/share/vulkan/icd.d/lvp_icd.json"),
                             Path("/usr/share/vulkan/icd.d/lvp_icd.x86_64.json")) if p.is_file()), None)


class VkCountLayerTest(unittest.TestCase):
    def build(self, tmp):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        if not compiler:
            self.fail("A C compiler is required for the vkcount layer test")
        include = Path(os.environ.get("VULKAN_INCLUDE", "/usr/include"))
        if not (include / "vulkan/vk_layer.h").is_file():
            self.fail("Vulkan headers are required: install libvulkan-dev or set VULKAN_INCLUDE")
        layer_dir = Path(tmp) / "layer"
        layer_dir.mkdir()
        library = layer_dir / "libVkLayer_droiddeck_vkcount.so"
        built = subprocess.run([compiler, "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror",
                                "-Wno-unused-parameter", "-fvisibility=hidden", "-pthread", "-I", str(include),
                                str(LAYER / "vkcount_layer.c"), "-o", str(library)], capture_output=True, text=True)
        self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
        shutil.copy(LAYER / "VkLayer_droiddeck_vkcount.json", layer_dir)
        return compiler, include, layer_dir, library

    def test_exports_only_the_loader_entry_points(self):
        with tempfile.TemporaryDirectory(prefix="droiddeck-vkcount-") as tmp:
            _, _, _, library = self.build(tmp)
            symbols = subprocess.run(["nm", "-D", "--defined-only", str(library)], capture_output=True, text=True,
                                     check=True).stdout.split()
            exported = {s for s in symbols if s.startswith("vk")}
            self.assertEqual(exported, {"vkNegotiateLoaderLayerInterfaceVersion", "vkGetInstanceProcAddr",
                                        "vkGetDeviceProcAddr"})

    @unittest.skipUnless(LAVAPIPE and Path("/usr/include/vulkan/vulkan.h").is_file(),
                         "lavapipe (mesa-vulkan-drivers) not installed")
    def test_counts_and_times_frames_on_lavapipe(self):
        with tempfile.TemporaryDirectory(prefix="droiddeck-vkcount-") as tmp:
            compiler, include, layer_dir, _ = self.build(tmp)
            program = Path(tmp) / "vkcount-test"
            built = subprocess.run([compiler, "-O1", "-I", str(include), str(Path(__file__).with_name("vkcount_layer_test.c")),
                                    "-lvulkan", "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            log = Path(tmp) / "vkcount.log"
            env = dict(os.environ, VK_ICD_FILENAMES=str(LAVAPIPE), VK_LAYER_PATH=str(layer_dir),
                       VK_INSTANCE_LAYERS="VK_LAYER_DROIDDECK_vkcount", DROIDDECK_VKCOUNT_FILE=str(log),
                       DROIDDECK_VKCOUNT_SECONDS="3600", NODEVICE_SELECT="1")
            ran = subprocess.run([str(program)], env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("ok", ran.stdout)
            lines = log.read_text().splitlines()
            self.assertEqual(len(lines), 2, lines)
            self.assertRegex(lines[0], r"^droiddeck-vkcount: swapchain \d+x\d+ format \d+, present mode FIFO \(2\), "
                                       r"min images 3, got [3-9]\d*, result 0$")
            line = lines[1]
            self.assertRegex(line, r"\| present interval ms: p50 [0-9.]+ p95 [0-9.]+ p99 [0-9.]+ max [0-9.]+; "
                                   r"in present: p99 [0-9.]+ max [0-9.]+ \|")
            self.assertTrue(line.startswith("droiddeck-vkcount: final "), line)
            for part in ("60 frames", "1.0 submits", "1.0 cmdbufs", "1.0 barriers", "5.0 copies", "allocations 2",
                         "memory 8.0 MB live", "peak 12.0 MB", "in 1 allocations,", "bound 1.0 MB in 1 images and 0.0 MB in 2 buffers",
                         "BC textures 1.0 MB -> 4.0 MB decoded (1 images)", "2.0 passes",
                         "| passes by area, load/store per frame:", "64x64 clear/store 1.0", "32x16 load/store 1.0"):
                self.assertIn(part, line)
            self.assertNotIn("blocks capped", line)
            self.assertIn(line, ran.stderr)

    @unittest.skipUnless(LAVAPIPE and Path("/usr/include/vulkan/vulkan.h").is_file(),
                         "lavapipe (mesa-vulkan-drivers) not installed")
    def test_block_cap_refuses_plain_power_of_two_blocks_above_it(self):
        with tempfile.TemporaryDirectory(prefix="droiddeck-vkcount-") as tmp:
            compiler, include, layer_dir, _ = self.build(tmp)
            program = Path(tmp) / "vkcount-test"
            built = subprocess.run([compiler, "-O1", "-I", str(include), str(Path(__file__).with_name("vkcount_layer_test.c")),
                                    "-lvulkan", "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            log = Path(tmp) / "vkcount.log"
            env = dict(os.environ, VK_ICD_FILENAMES=str(LAVAPIPE), VK_LAYER_PATH=str(layer_dir),
                       VK_INSTANCE_LAYERS="VK_LAYER_DROIDDECK_vkcount", DROIDDECK_VKCOUNT_FILE=str(log),
                       DROIDDECK_VKCOUNT_SECONDS="3600", NODEVICE_SELECT="1", DROIDDECK_VK_BLOCK_MB="8",
                       VKCOUNT_TEST_BLOCKS="1")
            ran = subprocess.run([str(program)], env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("blocks ok", ran.stdout)
            line = log.read_text().splitlines()[-1]
            for part in ("memory 8.0 MB live", "peak 20.0 MB", "blocks capped at 8 MB (1 bigger refused)"):
                self.assertIn(part, line)

    @unittest.skipUnless(LAVAPIPE and Path("/usr/include/vulkan/vulkan.h").is_file(),
                         "lavapipe (mesa-vulkan-drivers) not installed")
    def test_paces_presents_to_the_rate_in_force(self):
        with tempfile.TemporaryDirectory(prefix="droiddeck-vkcount-") as tmp:
            compiler, include, layer_dir, _ = self.build(tmp)
            program = Path(tmp) / "vkcount-test"
            built = subprocess.run([compiler, "-O1", "-I", str(include), str(Path(__file__).with_name("vkcount_layer_test.c")),
                                    "-lvulkan", "-o", str(program)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            pace_file = Path(tmp) / "pace"

            def run(**extra):
                log = Path(tmp) / "vkcount.log"
                log.unlink(missing_ok=True)
                env = dict(os.environ, VK_ICD_FILENAMES=str(LAVAPIPE), VK_LAYER_PATH=str(layer_dir),
                           VK_INSTANCE_LAYERS="VK_LAYER_DROIDDECK_vkcount", DROIDDECK_VKCOUNT_FILE=str(log),
                           DROIDDECK_VKCOUNT_SECONDS="3600", NODEVICE_SELECT="1")
                env.update(extra)
                start = time.monotonic()
                ran = subprocess.run([str(program)], env=env, capture_output=True, text=True, timeout=60)
                took = time.monotonic() - start
                self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
                return took, log.read_text() if log.exists() else ""

            free, _ = run()
            paced, line = run(DROIDDECK_PACE_FPS="30")
            # 60 presents at 30 fps: 59 intervals of 33.3 ms after the first.
            self.assertGreater(paced - free, 1.6)
            self.assertRegex(line, r"pace (2\d|3\d)\.\d\d ")
            pace_file.write_text("120\n")
            fast, _ = run(DROIDDECK_PACE_FPS="30", DROIDDECK_PACE_FILE=str(pace_file))
            self.assertLess(fast - free, 1.0)
            self.assertGreater(fast - free, 0.3)
            pace_file.write_text("0")
            off, _ = run(DROIDDECK_PACE_FPS="30", DROIDDECK_PACE_FILE=str(pace_file))
            self.assertLess(off - free, 0.4)
            quiet, text = run(DROIDDECK_PACE_FPS="30", DROIDDECK_VKCOUNT_SECONDS="0")
            self.assertEqual(text, "")
            self.assertGreater(quiet - free, 1.6)


if __name__ == "__main__":
    unittest.main()
