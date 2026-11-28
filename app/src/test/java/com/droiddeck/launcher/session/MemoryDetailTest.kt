package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDetailTest {
    /** A 16 GB phone shortly after a session: half of swap in use, file cache low. */
    private val meminfo = """
        MemTotal:       15612844 kB
        MemFree:          586112 kB
        MemAvailable:    3822416 kB
        Buffers:            4096 kB
        Cached:          2810240 kB
        SwapCached:        40960 kB
        Active:          5120000 kB
        Inactive:        3010000 kB
        Active(anon):    3900000 kB
        Inactive(anon):  1300000 kB
        Active(file):    1220000 kB
        Inactive(file):  1710000 kB
        Unevictable:      180000 kB
        Mlocked:          180000 kB
        SwapTotal:       8388604 kB
        SwapFree:        5268156 kB
        Dirty:              2048 kB
        Writeback:             0 kB
        AnonPages:       5242880 kB
        Mapped:          1048576 kB
        Shmem:            215040 kB
        KReclaimable:     409600 kB
        Slab:             839680 kB
        SReclaimable:     409600 kB
        SUnreclaim:       430080 kB
        KernelStack:       61440 kB
        ShadowCallStack:   15360 kB
        PageTables:       215040 kB
        VmallocTotal:   262930368 kB
        VmallocUsed:      122880 kB
        CmaTotal:         204800 kB
        HugePages_Total:       0
        Hugepagesize:       2048 kB
        ION_heap:         102400 kB
        ION_heap_pool:     51200 kB
    """.trimIndent()

    private val zram0 = MemoryDetail.parseZram("zram0",
        "3195256832 832061440 851443712        0 943718400     1024    20480     512\n",
        "8589934592\n", "lzo lzo-rle [lz4] zstd\n")!!

    private fun proc(pid: Int, ppid: Int, name: String, anonMb: Long, swapMb: Long, group: String, fileMb: Long = 10) =
        MemoryDetail.Proc(pid, ppid, 10234, name, (anonMb + fileMb) * 1024, anonMb * 1024, fileMb * 1024, 0, swapMb * 1024, group)

    private fun snapshot(kind: String = "sample", at: Long = 1_763_100_000_000L, vmstat: Map<String, Long> = emptyMap(),
                         procs: List<MemoryDetail.Proc> = listOf(
                             proc(100, 1, "kestrel", 300, 20, MemoryDetail.APP),
                             proc(200, 100, "libproot.so", 8, 0, MemoryDetail.PROOT),
                             proc(300, 200, "steamwebhelper", 600, 200, MemoryDetail.RUNTIME),
                             proc(400, 300, "SlayTheSpire2.exe", 3200, 2000, MemoryDetail.GAME),
                         )) =
        MemoryDetail.Snapshot(at, kind, MemoryDetail.meminfoFields(meminfo), listOf(zram0), null,
            mapOf("/sys/class/kgsl/kgsl/page_alloc" to 268435456L), 262144L, vmstat,
            MemoryDetail.parsePsi("some avg10=4.20 avg60=1.50 avg300=0.40 total=123456\nfull avg10=1.10 avg60=0.30 avg300=0.10 total=45678\n"),
            MemoryDetail.AndroidMem(3822416, 15612844, 221184, false, listOf(MemoryDetail.OwnProc(100, "chat.comb.lanner", 100, 290000, 20000, 12000))),
            procs)

    @Test fun meminfoKeepsEveryFieldInOrder() {
        val m = MemoryDetail.meminfoFields(meminfo)
        assertEquals(15612844L, m["MemTotal"])
        assertEquals(3900000L, m["Active(anon)"])
        assertEquals(0L, m["HugePages_Total"])
        assertEquals("MemTotal", m.keys.first())
        assertEquals(mapOf("ION_heap" to 102400L), MemoryDetail.meminfoDriverLines(m))
        assertTrue(MemoryDetail.meminfoFields("garbage\n\nMemTotal: x kB\n").isEmpty())
    }

    @Test fun theSystemEstimateLeavesOutLannerAndTheKernelPartAddsUp() {
        val m = MemoryDetail.meminfoFields(meminfo)
        val zramKb = zram0.usedBytes!! / 1024
        val e = MemoryDetail.estimate(m, lannerKb = 4_000_000, zramKb = zramKb, dmabuf = null, gpuKb = 262144)!!
        assertEquals(15612844L - 3822416L, e.usedKb)
        assertEquals(15612844L - 3822416L - 4_000_000L, e.systemKb)
        assertEquals(430080L, e.slabUnreclaimKb)
        assertEquals(102400L, e.driverKb)
        assertEquals("meminfo", e.driverSource)
        val accounted = 586112L + 4096 + 2810240 + 40960 + 5242880 + 839680 + 61440 + 215040 + 122880 + 102400 + 262144 + zramKb
        assertEquals(15612844L - accounted, e.unaccountedKb)
        assertEquals(430080L + 61440 + 215040 + 122880 + 102400 + 262144 + zramKb + e.unaccountedKb, e.kernelTotalKb)

        val sysfs = MemoryDetail.estimate(m, 0, 0, MemoryDetail.Dmabuf("/sys/kernel/dmabuf/buffers", 500_000, 12), null)!!
        assertEquals(500_000L, sysfs.driverKb)
        assertEquals("/sys/kernel/dmabuf/buffers", sysfs.driverSource)
        assertEquals(15612844L - 3822416L, sysfs.systemKb)

        assertNull(MemoryDetail.estimate(mapOf("MemTotal" to 100L), 0, 0, null, null))
        val bare = MemoryDetail.estimate(mapOf("MemTotal" to 100L, "MemAvailable" to 40L), lannerKb = 500, zramKb = 0, dmabuf = null, gpuKb = null)!!
        assertEquals(0L, bare.systemKb)
        assertEquals(100L, bare.unaccountedKb)
    }

    @Test fun zramMmStatAndTheSelectedAlgorithm() {
        assertEquals(3195256832L, zram0.origBytes)
        assertEquals(832061440L, zram0.comprBytes)
        assertEquals(851443712L, zram0.usedBytes)
        assertEquals(0L, zram0.limitBytes)
        assertEquals(943718400L, zram0.usedMaxBytes)
        assertEquals(512L, zram0.hugePages)
        assertEquals(8589934592L, zram0.disksizeBytes)
        assertEquals("lz4", zram0.algorithm)
        assertEquals(3.84, zram0.ratio!!, 0.01)
        assertEquals("zstd", MemoryDetail.selectedAlgorithm("zstd\n"))
        assertNull(MemoryDetail.selectedAlgorithm("lzo lz4"))
        val partial = MemoryDetail.parseZram("zram1", null, "4096", null)!!
        assertNull(partial.origBytes)
        assertEquals(4096L, partial.disksizeBytes)
        assertNull(MemoryDetail.parseZram("zram2", null, null, null))
    }

    @Test fun processStatusGivesResidentAndSwap() {
        val status = """
            Name:	SlayTheSpire2.e
            Umask:	0077
            State:	S (sleeping)
            Tgid:	4242
            Pid:	4242
            PPid:	4100
            Uid:	10234	10234	10234	10234
            Gid:	10234	10234	10234	10234
            VmPeak:	12000000 kB
            VmRSS:	 3400000 kB
            RssAnon:	 3100000 kB
            RssFile:	  250000 kB
            RssShmem:	   50000 kB
            VmSwap:	 2100000 kB
            Threads:	64
        """.trimIndent()
        val p = MemoryDetail.parseStatus(4242, status)!!
        assertEquals("SlayTheSpire2.e", p.name)
        assertEquals(4100, p.ppid)
        assertEquals(10234, p.uid)
        assertEquals(3400000L, p.rssKb)
        assertEquals(3100000L, p.anonKb)
        assertEquals(250000L, p.fileKb)
        assertEquals(50000L, p.shmemKb)
        assertEquals(2100000L, p.swapKb)
        assertEquals(3150000L, p.residentKb)
        val kthread = MemoryDetail.parseStatus(2, "Name:\tkthreadd\nPPid:\t0\nUid:\t0\t0\t0\t0\n")!!
        assertEquals(0L, kthread.rssKb + kthread.swapKb)
        assertNull(MemoryDetail.parseStatus(3, "VmRSS:\t100 kB\n"))
        assertEquals("Diablo IV.exe", MemoryDetail.parseStatus(5, "Name:\tDiablo IV.exe\n")!!.name)
    }

    @Test fun processesSplitIntoTheAppProotTheRuntimeAndTheGameTree() {
        fun p(pid: Int, ppid: Int, name: String) = MemoryDetail.Proc(pid, ppid, 10234, name, 1024, 1024, 0, 0, 0)
        val procs = listOf(p(100, 1, "rasknird.kestrel"), p(150, 1, "d.kestrel:balloon"), p(200, 100, "libproot.so"),
            p(300, 200, "steam"), p(310, 300, "steamwebhelper"), p(400, 300, "wineserver"), p(410, 400, "SlayTheSpire2.e"),
            p(420, 410, "FEXInterpreter"), p(500, 200, "Xwayland"), p(600, 300, "reaper"), p(610, 600, "python3"))
        val info = mapOf(
            100 to MemoryDetail.ProcInfo("chat.comb.lanner", false),
            150 to MemoryDetail.ProcInfo("chat.comb.lanner:balloon", false),
            200 to MemoryDetail.ProcInfo("/data/app/x/lib/arm64/libproot.so", false),
            300 to MemoryDetail.ProcInfo("/root/.local/share/Steam/steam", false),
            610 to MemoryDetail.ProcInfo("python3", true),
        )
        val groups = MemoryDetail.classify(procs, info, "chat.comb.lanner").associate { it.pid to it.group }
        assertEquals(MemoryDetail.APP, groups[100])
        assertEquals(MemoryDetail.APP, groups[150])
        assertEquals(MemoryDetail.PROOT, groups[200])
        assertEquals(MemoryDetail.RUNTIME, groups[300])
        assertEquals(MemoryDetail.RUNTIME, groups[310])
        assertEquals(MemoryDetail.GAME, groups[400])
        assertEquals(MemoryDetail.GAME, groups[410])
        assertEquals(MemoryDetail.GAME, groups[420])
        assertEquals(MemoryDetail.RUNTIME, groups[500])
        assertEquals(MemoryDetail.RUNTIME, groups[600])
        assertEquals(MemoryDetail.GAME, groups[610])
        assertEquals("/usr/bin/steam", MemoryDetail.argv0("/usr/bin/steam\u0000-silent\u0000".toByteArray()))
        assertEquals("", MemoryDetail.argv0(ByteArray(0)))
    }

    @Test fun vmstatKeepsThePagingCountersAndRatesFollowThem() {
        val v = MemoryDetail.vmstatCounters("nr_free_pages 12345\npgpgin 100\npswpin 2560\npswpout 512\npgmajfault 9000\n" +
            "workingset_refault_anon 400\nworkingset_refault_file 600\nallocstall_normal 3\noom_kill 0\nbad line\n")
        assertFalse(v.containsKey("nr_free_pages"))
        assertEquals(2560L, v["pswpin"])
        assertEquals(3L, v["allocstall_normal"])
        val before = snapshot(at = 1_000_000, vmstat = v)
        val after = snapshot(at = 1_030_000, vmstat = v + mapOf("pswpin" to 2560L + 7680, "pgmajfault" to 9000L + 3000,
            "workingset_refault_anon" to 400L + 300))
        val r = MemoryDetail.rates(before, after)
        assertEquals(100.0, r["majflt_s"]!!, 1e-9)
        assertEquals(1.0, r["swapin_mb_s"]!!, 1e-9)
        assertEquals(0.0, r["swapout_mb_s"]!!, 1e-9)
        assertEquals(10.0, r["refault_s"]!!, 1e-9)
        assertTrue(MemoryDetail.rates(null, after).isEmpty())
    }

    @Test fun psiAveragesAndTotals() {
        val psi = MemoryDetail.parsePsi("some avg10=4.20 avg60=1.50 avg300=0.40 total=123456\nfull avg10=1.10 avg60=0.30 avg300=0.10 total=45678\n")
        assertEquals(4.2, psi["some_avg10"]!!, 1e-9)
        assertEquals(45678.0, psi["full_total"]!!, 1e-9)
        assertTrue(MemoryDetail.parsePsi("").isEmpty())
    }

    /** Android 14's `dumpsys meminfo -s`, trimmed. */
    private val dumpsys = """
        Applications Memory Usage (in Kilobytes):
        Uptime: 86412345 Realtime: 86412345

        Total RSS by process:
            612,344K: system (pid 1621)
            402,112K: com.android.systemui (pid 2310)

        Total PSS by process:
            356,201K: system (pid 1621)
            245,880K: com.android.systemui (pid 2310)
            198,004K: chat.comb.lanner (pid 12001 / activities)
            150,442K: surfaceflinger (pid 1102)
            120,553K: com.google.android.gms.persistent (pid 3011)
             98,112K: com.android.launcher3 (pid 2900 / activities)
             40,001K: vendor.qti.hardware.display.composer-service (pid 900)

        Total PSS by OOM adjustment:
            720,431K: Native
                150,442K: surfaceflinger (pid 1102)
                 40,001K: vendor.qti.hardware.display.composer-service (pid 900)
            356,201K: System
                356,201K: system (pid 1621)
            410,118K: Persistent
                245,880K: com.android.systemui (pid 2310)
            198,004K: Foreground
                198,004K: chat.comb.lanner (pid 12001 / activities)
            612,300K: Cached
                 98,112K: com.android.launcher3 (pid 2900 / activities)

        Total PSS by category:
            592,110K: .so mmap
            401,223K: Native

        Total RAM: 15,612,844K (status normal)
         Free RAM: 3,822,416K (  612,300K cached pss + 2,610,116K cached kernel +   600,000K free)
        DMA-BUF:   498,212K (  302,120K mapped +   196,092K unmapped)
        DMA-BUF Heaps:   498,212K
        DMA-BUF Heaps pool:   122,880K
        GPU:   212,004K (  180,000K dmabuf +    32,004K private)
         Used RAM: 9,012,244K (2,412,118K used pss + 6,600,126K kernel)
         Lost RAM: 1,402,060K
             ZRAM:   812,004K physical used for 3,120,448K in swap (8,388,604K total swap)
           Tuning: 512 (large 512), oom   322,560K, restore limit   107,520K (high-end-gfx)
    """.trimIndent()

    @Test fun dumpsysSummaryAndTopProcesses() {
        val d = MemoryDetail.parseDumpsys(dumpsys, top = 5)!!
        assertEquals(15612844L, d.totalRamKb)
        assertEquals(3822416L, d.freeRamKb)
        assertEquals(mapOf("cached pss" to 612300L, "cached kernel" to 2610116L, "free" to 600000L), d.freeParts)
        assertEquals(9012244L, d.usedRamKb)
        assertEquals(6600126L, d.usedParts["kernel"])
        assertEquals(2412118L, d.usedParts["used pss"])
        assertEquals(1402060L, d.lostRamKb)
        assertEquals(812004L, d.zramPhysicalKb)
        assertEquals(3120448L, d.zramSwapKb)
        assertEquals(8388604L, d.zramTotalSwapKb)
        assertEquals(498212L, d.extra["DMA-BUF"])
        assertEquals(122880L, d.extra["DMA-BUF Heaps pool"])
        assertEquals(212004L, d.extra["GPU"])
        assertEquals("pss", d.metric)
        assertEquals(5, d.processes.size)
        assertEquals(MemoryDetail.DumpsysProc("system", 1621, 356201), d.processes[0])
        assertEquals(MemoryDetail.DumpsysProc("chat.comb.lanner", 12001, 198004), d.processes[2])
        assertEquals(listOf("Native" to 720431L, "System" to 356201L, "Persistent" to 410118L, "Foreground" to 198004L,
            "Cached" to 612300L), d.oom)

        val rssOnly = MemoryDetail.parseDumpsys("Total RSS by process:\n    612,344K: system (pid 1621)\n\n")!!
        assertEquals("rss", rssOnly.metric)
        assertNull(rssOnly.totalRamKb)
        assertEquals(1, rssOnly.processes.size)
        assertNull(MemoryDetail.parseDumpsys("Permission Denial: can't dump meminfo\n"))
        assertEquals(-2048L, MemoryDetail.parseDumpsys("Total RAM: 1,000K\n Lost RAM: -2,048K\n")!!.lostRamKb)
    }

    @Test fun rowsAreOneLineOfJsonWithEverySource() {
        val line = MemoryDetail.jsonLine(snapshot(kind = "prelaunch"))
        assertFalse(line.contains('\n'))
        assertTrue(line.startsWith("{\"t\":1763100000000,\"kind\":\"prelaunch\",\"est\":{"))
        listOf("\"meminfo\":{\"MemTotal\":15612844", "\"Active(anon)\":3900000", "\"zram\":[{\"dev\":\"zram0\"", "\"comp_algorithm\":\"lz4\"",
            "\"kernel_files\":{\"/sys/class/kgsl/kgsl/page_alloc\":268435456}", "\"gpu_kb\":262144", "\"psi\":{\"some_avg10\":4.20",
            "\"android\":{\"avail_kb\":3822416", "\"low_memory\":false", "\"groups\":{\"app\":{\"n\":1", "\"game\":{\"n\":1,\"rss_kb\":3287040",
            "\"top\":[{\"pid\":400", "\"note\":\"estimates:").forEach { assertTrue(it, line.contains(it)) }
        assertFalse(MemoryDetail.jsonLine(snapshot()).contains("\"note\""))
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", MemoryDetail.quote("a\"b\\c\nd\u0001"))

        val d = MemoryDetail.parseDumpsys(dumpsys)!!
        val row = MemoryDetail.dumpsysJsonLine(5, "prelaunch", d)
        assertTrue(row, row.contains("\"kind\":\"dumpsys\",\"reason\":\"prelaunch\",\"total_ram_kb\":15612844"))
        assertTrue(row, row.contains("\"used_parts_kb\":{\"used pss\":2412118,\"kernel\":6600126}"))
        assertTrue(row, row.contains("\"oom_kb\":{\"Native\":720431"))
        assertTrue(MemoryDetail.skipJsonLine(5, "prelaunch", "Wireless debugging off").contains("\"why\":\"Wireless debugging off\""))
    }

    @Test fun theLiveSectionFitsAndExplainsTheSystemFigure() {
        val now = 1_763_100_030_000L
        val s = snapshot()
        val text = MemoryDetail.liveText(s, null, (now - 180_000) to MemoryDetail.parseDumpsys(dumpsys)!!, null, now)
        assertTrue(text.length.toString(), text.length < 1500)
        assertTrue(text, text.contains("system (estimate)"))
        assertTrue(text, text.contains("zram0 3047 MB stored in 812 MB (3.84x) lz4"))
        assertTrue(text, text.contains("split: app 310/20, proot 18/0, runtime 610/200, game 3210/2000 MB (rss/swap)"))
        assertTrue(text, text.contains("top (rss+swap MB): SlayTheSpire2.e"))
        assertTrue(text, text.contains("dumpsys 3 min ago: Total 15246"))
        val skipped = MemoryDetail.liveText(s, null, null, "Wireless debugging off", now)
        assertTrue(skipped, skipped.contains("dumpsys: Wireless debugging off"))
        assertTrue(MemoryDetail.prelaunchText(s).contains("processes of this app's user, biggest first:"))
        assertTrue(MemoryDetail.dumpsysText(now, "prelaunch", MemoryDetail.parseDumpsys(dumpsys)!!).contains("Lost 1369"))
    }

    @Test fun headlineForThePing() {
        val h = MemoryDetail.headline(snapshot(), null)
        assertEquals(15246L, h["mem_total_mb"])
        assertEquals(3732L, h["mem_available_mb"])
        assertEquals(572L, h["mem_free_mb"])
        assertEquals(8191L, h["swap_total_mb"])
        assertEquals((8388604L - 5268156L) / 1024, h["swap_used_mb"])
        assertNotNull(h["mem_system_est_mb"])
        assertNotNull(h["mem_kernel_est_mb"])
        assertEquals("SlayTheSpire2.e 3210+2000 MB; steamwebhelper 610+200 MB; kestrel 310+20 MB", h["mem_top"])
        assertFalse(h.containsKey("mem_dumpsys"))
        assertTrue(MemoryDetail.headline(snapshot(), 1L to MemoryDetail.parseDumpsys(dumpsys)!!)["mem_dumpsys"].toString().contains("Lost 1369"))
        val text = h.entries.joinToString("\n") { "${it.key}: ${it.value}" }.lowercase()
        listOf("token", "password", "key", "secret").forEach { assertFalse(it, text.contains(it)) }
    }
}
