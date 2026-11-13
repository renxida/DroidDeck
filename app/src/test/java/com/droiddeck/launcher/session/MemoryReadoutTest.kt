package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReadoutTest {
    private val meminfo = """
        MemTotal:       11379920 kB
        MemFree:          527864 kB
        MemAvailable:    3235768 kB
        Buffers:            1024 kB
        SwapTotal:      16777212 kB
        SwapFree:       10358080 kB
    """.trimIndent()

    @Test fun memoryAndSwapInUseComeFromMeminfo() {
        val device = MemoryReadout.parseMeminfo(meminfo)!!
        assertEquals(11379920L - 3235768L, device.usedKb)
        assertEquals(16777212L - 10358080L, device.swapUsedKb)
        assertEquals("7.8", MemoryReadout.gb(device.usedKb))
        assertEquals("10.9", MemoryReadout.gb(device.totalKb))
        assertEquals(0L, MemoryReadout.parseMeminfo("MemTotal: 100 kB\nMemAvailable: 40 kB\n")!!.swapUsedKb)
        assertNull(MemoryReadout.parseMeminfo("MemTotal: 100 kB\n"))
    }

    @Test fun aProcessCountsItsResidentAnonymousAndSharedMemory() {
        val status = """
            Name:	Diablo IV.exe
            VmRSS:	 2000000 kB
            RssAnon:	 1500000 kB
            RssFile:	  400000 kB
            RssShmem:	  100000 kB
            VmSwap:	  300000 kB
        """.trimIndent()
        assertEquals(1600000L, MemoryReadout.residentKb(status))
        assertEquals(0L, MemoryReadout.residentKb("Name:\tkthreadd\n"))
    }

    @Test fun theGameIsWhatRunsInAWinePrefix() {
        fun env(vararg vars: String) = vars.joinToString("\u0000", postfix = "\u0000").toByteArray()
        assertTrue(MemoryReadout.inWinePrefix(env("HOME=/root", "WINEPREFIX=/root/pfx", "DISPLAY=:0")))
        assertTrue(MemoryReadout.inWinePrefix("WINEPREFIX=/x".toByteArray()))
        assertFalse(MemoryReadout.inWinePrefix(env("HOME=/root", "STEAM_COMPAT_DATA_PATH=/x", "MY_WINEPREFIX=/y")))
        assertFalse(MemoryReadout.inWinePrefix(ByteArray(0)))
    }

    @Test fun androidIsWhatIsLeftOfTheMemoryInUse() {
        val device = MemoryReadout.Device(totalKb = 10_000, availableKb = 3_000, swapTotalKb = 0, swapFreeKb = 0)
        assertEquals(3_000L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 2_000, droiddeckKb = 1_000, gpuKb = 1_000)))
        assertEquals(4_000L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 2_000, droiddeckKb = 1_000, gpuKb = null)))
        assertEquals(0L, MemoryReadout.androidKb(device, MemoryReadout.Split(gameKb = 6_000, droiddeckKb = 2_000, gpuKb = 1_000)))
    }

    @Test fun theLogLineSaysEveryPartInMegabytes() {
        val device = MemoryReadout.Device(totalKb = 11_379_920, availableKb = 1_048_576, swapTotalKb = 16_777_212, swapFreeKb = 8_388_606)
        val split = MemoryReadout.Split(gameKb = 2_097_152, droiddeckKb = 524_288, gpuKb = 3_145_728)
        assertEquals(
            "used 10089/11113 MB, available 1024 MB, swap 8191/16383 MB | game 2048, droiddeck 512, android 4457, gpu 3072 MB",
            MemoryLog.line(device, split),
        )
        assertTrue(MemoryLog.line(device, split.copy(gpuKb = null)).endsWith("gpu ? MB"))
    }

    @Test fun swapAndNameComeFromProcStatus() {
        val status = "Name:\twine64-preloader\nVmRSS:\t  900 kB\nRssAnon:\t  800 kB\nRssShmem:\t  100 kB\nVmSwap:\t  2048 kB\n"
        assertEquals(2048L, MemoryReadout.swapKb(status))
        assertEquals("wine64-preloader", MemoryReadout.processName(status))
        assertEquals(0L, MemoryReadout.swapKb("Name:\tx\n"))
    }

    @Test fun theCsvLineMatchesItsHeader() {
        val device = MemoryReadout.Device(totalKb = 11_379_920, availableKb = 1_048_576, swapTotalKb = 16_777_212, swapFreeKb = 8_388_606)
        val split = MemoryReadout.Split(gameKb = 2_097_152, droiddeckKb = 524_288, gpuKb = null,
            gameSwapKb = 1_048_576, droiddeckSwapKb = 2_048, topName = "SlayTheSpire2.e", topKb = 3_145_728)
        val line = MemoryLog.csvLine(1L, device, split, 4_096)
        assertEquals(MemoryLog.CSV_HEADER.split(',').size, line.split(',').size)
        assertTrue(line.endsWith(",1024,2,SlayTheSpire2.e,3072,,,,"))
    }

    @Test fun pagingRatesFollowMajorFaultsAndSwapCounters() {
        val stat = "4242 (SlayTheSpire2.e) R 1 4242 4242 0 -1 4194560 100 0 37 0 1500 250 0 0 20 0 30 0 12345 0 0"
        assertEquals(37L, MemoryReadout.majorFaults(stat))
        assertNull(MemoryReadout.majorFaults("4242 (x"))
        val before = MemoryReadout.parseVmstat("pgpgin 1\npswpin 1000\npswpout 2000\npgmajfault 50\n")!!
        val after = MemoryReadout.parseVmstat("pswpin 3560\npswpout 2256\npgmajfault 90\n")!!
        assertNull(MemoryReadout.parseVmstat("pgpgin 1\n"))
        val a = MemoryReadout.Split(0, 0, null, gameMajflt = 100, droiddeckMajflt = 10)
        val b = MemoryReadout.Split(0, 0, null, gameMajflt = 600, droiddeckMajflt = 5)
        val rates = MemoryLog.Rates.between(a, before, b, after, 5_000L)
        assertEquals(100.0, rates.gameMajflt!!, 1e-9)
        assertNull(rates.droiddeckMajflt)
        assertEquals(2.0, rates.swapInMb!!, 1e-9)
        assertEquals(0.2, rates.swapOutMb!!, 1e-9)
        val device = MemoryReadout.Device(1024, 512, 0, 0)
        assertTrue(MemoryLog.csvLine(1L, device, b, null, rates).endsWith(",100.0,,2.0,0.2"))
        assertNull(MemoryLog.Rates.between(null, null, b, after, 5_000L).gameMajflt)
    }

    @Test fun cpuTicksAreUserPlusSystemTimeAfterTheCommandName() {
        val stat = "4242 (steam webhelper) S 1 4242 4242 0 -1 4194560 100 0 0 0 1500 250 0 0 20 0 30 0 12345 0 0"
        assertEquals(1750L, MemoryReadout.cpuTicks(stat))
        assertNull(MemoryReadout.cpuTicks("4242 (x"))
        assertNull(MemoryReadout.cpuTicks("4242 (x) S 1"))
    }

    @Test fun processRowsKeepLargeOrBusyProcessesWithCpuOverTheInterval() {
        val procs = listOf(
            MemoryReadout.Proc(10, "steamwebhelper", false, 300 * 1024, 200 * 1024, 1_100),
            MemoryReadout.Proc(11, "SlayTheSpire2.e", true, 1500 * 1024, 0, 7_000),
            MemoryReadout.Proc(12, "sleep", false, 1024, 0, 5),
            MemoryReadout.Proc(13, "xdg,busy", false, 2048, 0, 700),
        )
        val first = MemoryLog.processLines(1_000L, procs, emptyMap(), 0L, 100L)
        assertEquals(listOf("1000,10,steamwebhelper,droiddeck,300,200,", "1000,11,SlayTheSpire2.e,game,1500,0,"), first)
        val later = MemoryLog.processLines(61_000L, procs, mapOf(10 to 500L, 11 to 1_000L, 12 to 5L, 13 to 100L), 60_000L, 100L)
        assertEquals(listOf(
            "61000,10,steamwebhelper,droiddeck,300,200,10.0",
            "61000,11,SlayTheSpire2.e,game,1500,0,100.0",
            "61000,13,xdg_busy,droiddeck,2,0,10.0",
        ), later)
        assertEquals(MemoryLog.PROCESS_HEADER.split(',').size, later[0].split(',').size)
    }
}
