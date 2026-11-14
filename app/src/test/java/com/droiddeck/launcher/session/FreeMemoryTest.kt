package com.droiddeck.launcher.session

import com.droiddeck.launcher.telemetry.AppExits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeMemoryTest {
    private fun device(availableMb: Long, swapFreeMb: Long, totalMb: Long = 11_500, swapTotalMb: Long = 5_900) =
        MemoryReadout.Device(totalMb * 1024, availableMb * 1024, swapTotalMb * 1024, swapFreeMb * 1024)

    private fun sample(availableMb: Long, swapFreeMb: Long, fullStallUs: Long? = null) =
        FreeMemory.Sample(availableMb * 1024, swapFreeMb * 1024, fullStallUs)

    @Test fun everyOtherPackageIsTriedButThisOneTheLauncherAndTheKeyboard() {
        val installed = listOf("com.b", "dev.renxida.kestrel", "com.a", "com.launcher", "com.ime", "com.a", "")
        assertEquals(listOf("com.a", "com.b"),
            FreeMemory.candidates(installed, "dev.renxida.kestrel", setOf("com.launcher", "com.ime")))
        assertEquals(listOf("com.a", "com.b", "com.ime", "com.launcher"), FreeMemory.candidates(installed, "dev.renxida.kestrel", emptySet()))
    }

    @Test fun theKeyboardPackageComesFromTheDefaultInputMethodSetting() {
        assertEquals("com.google.android.inputmethod.latin",
            FreeMemory.imePackage("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"))
        assertEquals("com.ime", FreeMemory.imePackage("com.ime"))
        assertNull(FreeMemory.imePackage(null))
        assertNull(FreeMemory.imePackage("/.Ime"))
    }

    @Test fun itRunsOnlyWhenLessThanTheNeedIsAvailable() {
        assertTrue(FreeMemory.wanted(device(2_600, 1_700), FreeMemory.SESSION_NEED_MB))
        assertFalse(FreeMemory.wanted(device(5_000, 1_700), FreeMemory.SESSION_NEED_MB))
        assertFalse(FreeMemory.wanted(device(3_500, 1_700), FreeMemory.GAME_NEED_MB))
        assertFalse(FreeMemory.wanted(null, FreeMemory.SESSION_NEED_MB))
    }

    @Test fun theBalloonFollowsOnlyWhenKillingBackgroundProcessesFreedLittle() {
        assertTrue(FreeMemory.needsBalloon(0, device(2_600, 1_700), FreeMemory.SESSION_NEED_MB))
        assertTrue(FreeMemory.needsBalloon(null, device(2_600, 1_700), FreeMemory.SESSION_NEED_MB))
        assertTrue(FreeMemory.needsBalloon(499, device(2_600, 1_700), FreeMemory.SESSION_NEED_MB))
        assertFalse(FreeMemory.needsBalloon(500, device(2_600, 1_700), FreeMemory.SESSION_NEED_MB))
        assertFalse(FreeMemory.needsBalloon(0, device(4_500, 1_700), FreeMemory.SESSION_NEED_MB))
        assertFalse(FreeMemory.needsBalloon(0, null, FreeMemory.SESSION_NEED_MB))
    }

    @Test fun theBalloonMustBeMoreKillableThanTheApp() {
        assertTrue(FreeMemory.ordered(0, 250))
        assertTrue(FreeMemory.ordered(200, 250))
        assertFalse(FreeMemory.ordered(0, 0))
        assertFalse(FreeMemory.ordered(250, 250))
        assertTrue(FreeMemory.ordered(0, null))
        assertTrue(FreeMemory.ordered(200, null))
        assertFalse(FreeMemory.ordered(250, null))
        assertFalse(FreeMemory.ordered(null, 250))
        assertEquals(250, FreeMemory.parseAdj("250\n"))
        assertNull(FreeMemory.parseAdj("x"))
    }

    @Test fun theCapIsAThirdOfRamUpTo4Gb() {
        assertEquals(4096, FreeMemory.capMb(12L * 1024 * 1024))
        assertEquals(2048, FreeMemory.capMb(6L * 1024 * 1024))
    }

    @Test fun withoutKillsItGrowsToTheCap() {
        val rule = FreeMemory.StopRule(capMb = 1024)
        var size = 0
        var available = 3_000L
        var reason: String? = null
        while (reason == null) {
            val before = sample(available, 1_000)
            size += 256
            available -= 256
            reason = rule.after(before, sample(available, 1_000), 256, size, elapsedMs = size.toLong(), stepMs = 200)
        }
        assertEquals("cap", reason)
        assertEquals(1024, size)
        assertEquals(0, rule.killSteps)
    }

    @Test fun itStopsWhenAvailableStopsRisingAfterKills() {
        val rule = FreeMemory.StopRule(capMb = 4096)
        assertNull(rule.after(sample(1_000, 800), sample(744, 800), 256, 256, 300, 300))
        // lmkd killed a cached app holding 400 MB resident and 300 MB of swap.
        assertNull(rule.after(sample(744, 800), sample(888, 1_100), 256, 512, 600, 300))
        assertEquals(1, rule.killSteps)
        assertNull(rule.after(sample(888, 1_100), sample(632, 1_100), 256, 768, 900, 300))
        assertEquals("reclaimed", rule.after(sample(632, 1_100), sample(380, 1_090), 256, 1024, 1200, 300))
    }

    @Test fun killsThatKeepComingKeepItGrowing() {
        val rule = FreeMemory.StopRule(capMb = 4096)
        assertNull(rule.after(sample(1_000, 800), sample(1_100, 800), 256, 256, 300, 300))
        assertNull(rule.after(sample(1_100, 800), sample(900, 800), 256, 512, 600, 300))
        assertNull(rule.after(sample(900, 800), sample(1_000, 900), 256, 768, 900, 300))
        assertNull(rule.after(sample(1_000, 900), sample(800, 900), 256, 1024, 1200, 300))
        assertEquals(2, rule.killSteps)
    }

    @Test fun itStopsAtTheFloorOnAStallOrOutOfTime() {
        assertEquals("floor", FreeMemory.StopRule(4096).after(sample(500, 800), sample(250, 790), 256, 256, 300, 300))
        assertEquals("stall", FreeMemory.StopRule(4096).after(sample(2_000, 800, 1_000_000), sample(1_744, 800, 1_100_000), 256, 256, 300, 300))
        assertNull(FreeMemory.StopRule(4096).after(sample(2_000, 800, 1_000_000), sample(1_744, 800, 1_050_000), 256, 256, 300, 300))
        assertEquals("time", FreeMemory.StopRule(4096).after(sample(2_000, 800), sample(1_744, 800), 256, 256, FreeMemory.MAX_MS, 300))
    }

    @Test fun theFullStallTotalIsReadFromPsi() {
        val psi = "some avg10=1.00 avg60=0.50 avg300=0.10 total=123456\nfull avg10=0.50 avg60=0.20 avg300=0.05 total=6789\n"
        assertEquals(6789L, FreeMemory.parseFullStallUs(psi))
        assertNull(FreeMemory.parseFullStallUs("some avg10=1.00 avg60=0.50 avg300=0.10 total=123456\n"))
    }

    @Test fun theEventNamesMethodMemoryBeforeAndAfterAndTheBalloon() {
        val result = FreeMemory.Result(
            reason = FreeMemory.SESSION_START, method = "kill_background+balloon", packages = 412,
            before = device(2_600, 1_700), after = device(4_100, 2_900), killFreedMb = 3,
            balloonPeakMb = 2_304, balloonMs = 4_200, balloonStop = "reclaimed", appAdj = 0, balloonAdj = 250, ms = 5_100,
        )
        val fields = FreeMemory.fields(result)
        assertEquals(listOf("reason", "method", "freedMb", "availableBeforeMb", "availableAfterMb", "swapUsedBeforeMb", "swapUsedAfterMb",
            "balloonPeakMb", "balloonMs", "balloonStop", "packages", "killFreedMb", "appAdj", "balloonAdj", "ms"), fields.keys.toList())
        assertEquals(2_700L, fields["freedMb"])
        assertEquals(2_600L, fields["availableBeforeMb"])
        assertEquals(4_100L, fields["availableAfterMb"])
        assertEquals(4_200L, fields["swapUsedBeforeMb"])
        assertEquals(3_000L, fields["swapUsedAfterMb"])
        assertEquals(2_304, fields["balloonPeakMb"])
        assertEquals("reclaimed", fields["balloonStop"])
        assertEquals(412, fields["packages"])
        assertTrue(FreeMemory.line(result).startsWith("DROIDDECK_FREE_MEMORY reason=session_start method=kill_background+balloon freedMb=2700 "))
        val skipped = FreeMemory.Result(FreeMemory.GAME_EXEC, "none", 0, device(5_000, 1_000), device(5_000, 1_000), null)
        assertEquals(0L, FreeMemory.fields(skipped)["freedMb"])
        assertTrue(FreeMemory.line(skipped).contains(" balloonStop=- packages=0 killFreedMb=- "))
    }

    @Test fun aKilledBalloonIsNotAnAbnormalExit() {
        assertFalse(AppExits.abnormal("low_memory", "dev.renxida.kestrel:balloon"))
        assertTrue(AppExits.abnormal("low_memory", "dev.renxida.kestrel"))
        assertFalse(AppExits.abnormal("exit_self", "dev.renxida.kestrel"))
    }
}
