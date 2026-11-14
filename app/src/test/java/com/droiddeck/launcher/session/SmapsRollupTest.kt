package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmapsRollupTest {
    private fun vma(range: String, perms: String, path: String, rss: Long, swap: Long, anon: Long = rss) = """
        $range $perms 00000000 00:00 0          $path
        Size:               1024 kB
        Rss:                 $rss kB
        Pss:                 $rss kB
        Anonymous:           $anon kB
        Swap:                $swap kB
        SwapPss:             $swap kB
        VmFlags: rd wr mr mw me ac
    """.trimIndent()

    private val smaps = listOf(
        vma("7f0000000000-7f0000100000", "rwxp", "[anon:FEXMemJIT]", 800, 100),
        vma("7f0000100000-7f0000200000", "rw-p", "[anon:FEXMem_Lookup]", 300, 600),
        vma("7f0000200000-7f0000300000", "rw-p", "[anon:FEXMem_Lookup]", 200, 0),
        vma("7f0000300000-7f0000400000", "rw-p", "[anon:FEXMem_Lookup_L1]", 1024, 0),
        vma("7f0000400000-7f0000500000", "rw-p", "", 50, 900),
        vma("7f0000500000-7f0000600000", "r-xp", "/opt/proton/files/lib/wine/x86_64-windows/ntdll.dll", 40, 0, 0),
        vma("7f0000600000-7f0000700000", "r-xp", "/home/u/Games/SlayTheSpire2/data/coreclr.dll", 60, 0, 0),
        vma("7f0000700000-7f0000800000", "r-xp", "/home/u/Games/SlayTheSpire2/SlayTheSpire2.exe", 70, 0, 0),
        vma("7f0000800000-7f0000900000", "rw-s", "/dev/kgsl-3d0", 10, 0, 0),
        vma("7f0000900000-7f0000a00000", "rw-p", "[anon:win:gc]", 5, 7),
        vma("7f0000a00000-7f0000b00000", "r-xp", "/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so", 3, 0, 0),
        vma("7ffff0000000-7ffff0021000", "rw-p", "[stack]", 1, 0),
    ).joinToString("\n")

    @Test
    fun parsesEveryMappingWithItsCounters() {
        val v = SmapsRollup.parse(smaps)
        assertEquals(12, v.size)
        assertEquals("[anon:FEXMem_Lookup]", v[1].path)
        assertEquals(600L, v[1].swapKb)
        assertEquals("", v[4].path)
        assertEquals(1024L, v[0].sizeKb)
    }

    @Test
    fun namesOwners() {
        assertEquals("fex-code", SmapsRollup.classify("[anon:FEXMemJIT]").first)
        assertEquals("fex-lookup-l2", SmapsRollup.classify("[anon:FEXMem_Lookup]").first)
        assertEquals("fex-lookup-l1", SmapsRollup.classify("[anon:FEXMem_Lookup_L1]").first)
        assertEquals("fex-blocklinks", SmapsRollup.classify("[anon:FEXMem_BlockLinks]").first)
        assertEquals("fex-other", SmapsRollup.classify("[anon:FEXMem]").first)
        assertEquals("win-gc", SmapsRollup.classify("[anon:win:gc]").first)
        assertEquals("image-wine", SmapsRollup.classify("/p/files/lib/wine/x86_64-windows/ntdll.dll").first)
        assertEquals("fex-image", SmapsRollup.classify("/p/files/lib/wine/aarch64-windows/libarm64ecfex.dll").first)
        assertEquals("image-dotnet", SmapsRollup.classify("/g/System.Private.CoreLib.dll").first)
        assertEquals("image-game", SmapsRollup.classify("/g/steam_api64.dll").first)
        assertEquals("image-exe", SmapsRollup.classify("/g/SlayTheSpire2.exe").first)
        assertEquals("so-gpu", SmapsRollup.classify("/usr/lib/libvulkan_freedreno.so").first)
        assertEquals("so-wine", SmapsRollup.classify("/p/files/lib/wine/aarch64-unix/ntdll.so").first)
        assertEquals("gpu-device", SmapsRollup.classify("/dev/kgsl-3d0").first)
        assertEquals("gpu-dmabuf", SmapsRollup.classify("/dmabuf:").first)
        assertEquals("anon", SmapsRollup.classify("").first)
        assertEquals("linux-brk", SmapsRollup.classify("[heap]").first)
    }

    @Test
    fun linesTotalThenCategoriesBiggestFirstThenTopMappings() {
        val rows = SmapsRollup.lines(1000L, 42, "SlayTheSpire2.exe", smaps, 12L)
        assertEquals("1000,42,SlayTheSpire2.exe,total,total,all mappings,12,11.1,2.5,2.5,1.6,2.3", rows[0])
        val cats = rows.filter { it.split(',')[3] == "category" }.map { it.split(',')[4] }
        assertEquals(listOf("fex-lookup-l2", "fex-lookup-l1", "anon", "fex-code"), cats.take(4))
        assertTrue(rows.any { it.startsWith("1000,42,SlayTheSpire2.exe,category,fex-lookup-l2,") && it.endsWith(",2,2.0,0.5,0.5,0.6,0.5") })
        assertEquals(SmapsRollup.TOP.coerceAtMost(12), rows.count { it.split(',')[3] == "top" })
        assertEquals("1000,42,SlayTheSpire2.exe,meta,read_ms,12,12,,,,,", rows.last())
        assertTrue(rows.all { it.split(',').size == SmapsRollup.HEADER.split(',').size })
    }
}
