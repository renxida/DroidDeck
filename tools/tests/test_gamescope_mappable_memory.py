from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0936-mappable-memory-without-cached-or-coherent.patch"


def file_diffs():
    return {chunk.split(" b/", 1)[1].split("\n", 1)[0]: chunk for chunk in PATCH.read_text().split("diff --git a/")[1:]}


def added(diff):
    return [line[1:] for line in diff.splitlines() if line.startswith("+") and not line.startswith("+++")]


VULKAN_STUB = r'''
#pragma once
#include <cstdint>
typedef uint32_t VkFlags;
typedef VkFlags VkMemoryPropertyFlags;
enum : VkFlags {
    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 0x1,
    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 0x2,
    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x4,
    VK_MEMORY_PROPERTY_HOST_CACHED_BIT = 0x8,
};
struct VkMemoryType { VkMemoryPropertyFlags propertyFlags; uint32_t heapIndex; };
struct VkMemoryHeap { uint64_t size; VkFlags flags; };
struct VkPhysicalDeviceMemoryProperties {
    uint32_t memoryTypeCount; VkMemoryType memoryTypes[32];
    uint32_t memoryHeapCount; VkMemoryHeap memoryHeaps[16];
};
'''

MAIN = r'''
#include "mappable_memory_type.hpp"
#include <cassert>
#include <cstdio>

int main() {
    // Google Pixel 10, PowerVR D-Series DXT-48-1536: no type is both HOST_COHERENT and HOST_CACHED.
    VkPhysicalDeviceMemoryProperties pvr = {};
    pvr.memoryTypeCount = 5;
    const VkMemoryPropertyFlags flags[] = {0x1, 0x7, 0xb, 0x11, 0x21};
    for (uint32_t i = 0; i < 5; i++) pvr.memoryTypes[i].propertyFlags = flags[i];
    const VkMemoryPropertyFlags mappable = 0xe;
    VkMemoryPropertyFlags chosen = 0;
    assert(gamescope::MappableMemoryTypeFallback(pvr, mappable, 0x7, &chosen) == 1 && chosen == 0x6);
    assert(gamescope::MappableMemoryTypeFallback(pvr, mappable, 0x5, &chosen) == 2 && chosen == 0xa);
    assert(gamescope::MappableMemoryTypeFallback(pvr, mappable, 0x1, &chosen) == -1);
    // Only a request for HOST_CACHED is relaxed; HOST_COHERENT alone is a requirement.
    assert(gamescope::MappableMemoryTypeFallback(pvr, 0x6, 0x5, &chosen) == -1);
    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class MappableMemoryFallbackTest(unittest.TestCase):
    def test_a_mappable_texture_settles_for_coherent_then_cached_memory(self):
        header = "\n".join(added(file_diffs()["src/mappable_memory_type.hpp"]))
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "vulkan").mkdir()
            (Path(tmp) / "vulkan/vulkan_core.h").write_text(VULKAN_STUB)
            (Path(tmp) / "mappable_memory_type.hpp").write_text(header)
            source = Path(tmp) / "fallback.cpp"
            binary = Path(tmp) / "fallback"
            source.write_text(MAIN)
            subprocess.run(["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-UNDEBUG", "-I", tmp,
                            str(source), "-o", str(binary)], check=True)
            self.assertEqual(subprocess.run([str(binary)], capture_output=True, text=True, check=True).stdout.strip(), "ok")

    def test_mapped_reads_invalidate_first(self):
        diffs = file_diffs()
        for path, reader in (("src/steamcompmgr.cpp", "pScreenshotTexture"), ("src/pipewire.cpp", "tex")):
            lines = diffs[path].splitlines()
            call = next(i for i, line in enumerate(lines) if line.startswith("+") and "invalidateMappedData()" in line)
            self.assertIn(f"{reader}->invalidateMappedData();", lines[call])
            self.assertIn(f"{reader}->mappedData()", lines[call + 1])

    def test_non_coherent_memory_is_remembered_for_invalidation(self):
        lines = [line.strip() for line in added(file_diffs()["src/rendervulkan.cpp"])]
        self.assertIn("m_vkNonCoherentMemory = memoryHandle;", lines)
        self.assertIn("VkResult res = g_device.vk.InvalidateMappedMemoryRanges( g_device.device(), 1, &range );", lines)
        self.assertIn("VK_FUNC(InvalidateMappedMemoryRanges) \\", [l.strip() for l in added(file_diffs()["src/rendervulkan.hpp"])])


if __name__ == "__main__":
    unittest.main()
