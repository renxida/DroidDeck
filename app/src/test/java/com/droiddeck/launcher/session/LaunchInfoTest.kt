package com.droiddeck.launcher.session

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.droiddeck.launcher.telemetry.BuildInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchInfoTest {
    // Another test's launch can leave a branch request behind, which would add " · beta" to the engine line.
    @org.junit.Before fun setUp() { SteamBranch.forget() }
    @After fun tearDown() { LaunchInfo.reset(); Experiments.endSession() }

    @Test
    fun theEngineLineNamesTheIntendedPathUntilTheGuestSaysWhatBooted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Native engine, Proton if it does not start",
            LaunchInfo.engineLine(context, setOf(Experiments.NATIVE_ENGINE, Experiments.FEX_KESTREL)))
        assertEquals("Proton + Kestrel FEX", LaunchInfo.engineLine(context, setOf(Experiments.FEX_KESTREL)))
        assertEquals("Proton + stock FEX", LaunchInfo.engineLine(context, emptySet()))
        LaunchInfo.engineStarted(BootLine.parse("droiddeck-game-env: engine: native native-engine-54")!!, 1_000L)
        assertEquals("Native engine (native-engine-54)", LaunchInfo.engineLine(context, setOf(Experiments.NATIVE_ENGINE)))
        LaunchInfo.engineStarted(BootLine.parse(
            "droiddeck-game-env: engine: proton FEX-Kestrel-2510-p9; native skipped: Steam library gap")!!, 2_000L)
        assertEquals("Native engine skipped (Steam library gap) → Proton + FEX (FEX-Kestrel-2510-p9)",
            LaunchInfo.engineLine(context, setOf(Experiments.NATIVE_ENGINE)))
    }

    @Test
    fun theLinesLeadWithTheEngineAndAppBuildThenSwitchesAndFexLine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Experiments.set(context, setOf(Experiments.NATIVE_ENGINE))
        Experiments.beginSession(context)
        LaunchInfo.fex = "fex: stock 2508"
        val lines = LaunchInfo.lines(context)
        assertEquals("Native engine, Proton if it does not start", lines[0])
        assertTrue(lines[1], lines[1].startsWith("Lanner ") && BuildInfo.shortCommit() in lines[1])
        assertTrue(lines.drop(LaunchInfo.BOOT_LINES).any { it.startsWith("on     ") && Experiments.NATIVE_ENGINE in it })
        assertTrue(lines.contains("fex    stock 2508"))
    }

    @Test
    fun aGameStartedLaterInTheSessionBringsTheCornerCardBack() {
        LaunchInfo.firstFrame(1_000L)
        assertTrue(LaunchInfo.cornerVisible(10_000L, gameRunning = true))
        assertFalse(LaunchInfo.cornerVisible(600_000L, gameRunning = true))
        LaunchInfo.engineStarted(BootLine.Engine(BootLine.Path.PROTON, "FEX-x"), 600_000L)
        assertTrue(LaunchInfo.cornerVisible(659_000L, gameRunning = true))
        assertFalse(LaunchInfo.cornerVisible(661_000L, gameRunning = true))
    }

    @Test
    fun theCornerCardShowsFromTheFirstFrameUntilShortlyAfterTheGameStarts() {
        assertFalse(LaunchInfo.cornerVisible(0L, gameRunning = false))
        LaunchInfo.firstFrame(1_000L)
        assertTrue(LaunchInfo.cornerVisible(100_000L, gameRunning = false))
        assertTrue(LaunchInfo.cornerVisible(120_000L, gameRunning = true))
        assertTrue(LaunchInfo.cornerVisible(164_000L, gameRunning = true))
        assertFalse(LaunchInfo.cornerVisible(166_000L, gameRunning = true))
        LaunchInfo.reset()
        LaunchInfo.firstFrame(1_000L)
        assertFalse(LaunchInfo.cornerVisible(182_000L, gameRunning = false))
    }

    @Test
    fun theNativeEngineHidesTheCornerCardOnTheGamesFirstFrame() {
        LaunchInfo.reset(0L)
        LaunchInfo.firstFrame(1_000L)
        LaunchInfo.engineStarted(BootLine.Engine(BootLine.Path.NATIVE, "native-engine-73"), 5_000L)
        assertTrue(LaunchInfo.cornerVisible(10_000L, gameRunning = false))
        LaunchInfo.gameFocused(11_000L)
        assertTrue(LaunchInfo.cornerVisible(12_000L, gameRunning = false))
        LaunchInfo.gameFrame(12_500L)
        assertFalse(LaunchInfo.cornerVisible(13_000L, gameRunning = false))
        assertFalse(LaunchInfo.cornerVisible(30_000L, gameRunning = false))
    }

    @Test
    fun aGameThatDrewWhileNoScreenWasShowingLeavesNoCardOnAttach() {
        LaunchInfo.reset(1_000L)
        LaunchInfo.engineStarted(BootLine.Engine(BootLine.Path.NATIVE, "native-engine-73"), 20_000L)
        LaunchInfo.gameFocused(40_000L)
        LaunchInfo.gameFrame(41_000L)
        assertTrue(LaunchInfo.sessionDrawn(firstFrameSeen = false))
        LaunchInfo.firstFrame(300_000L)
        assertFalse(LaunchInfo.cornerVisible(300_000L, gameRunning = false))
        assertFalse(LaunchInfo.cornerVisible(301_000L, gameRunning = false))
    }

    @Test
    fun framesFromBeforeThisSessionOrLaunchDoNotCount() {
        LaunchInfo.reset(100_000L)
        LaunchInfo.gameFrame(50_000L)
        assertFalse(LaunchInfo.sessionDrawn(firstFrameSeen = false))
        LaunchInfo.firstFrame(110_000L)
        assertTrue(LaunchInfo.cornerVisible(120_000L, gameRunning = false))
        LaunchInfo.gameFrame(121_000L)
        assertFalse(LaunchInfo.cornerVisible(122_000L, gameRunning = false))
        LaunchInfo.engineStarted(BootLine.Engine(BootLine.Path.PROTON, "FEX-x"), 600_000L)
        LaunchInfo.gameFrame(599_000L)
        assertTrue(LaunchInfo.cornerVisible(601_000L, gameRunning = true))
        LaunchInfo.gameFrame(605_000L)
        assertFalse(LaunchInfo.cornerVisible(606_000L, gameRunning = true))
    }

    @Test
    fun theCornerCardGoesABoundedTimeAfterTheGameWindowComesToTheFrontWithoutAFrame() {
        LaunchInfo.reset(0L)
        LaunchInfo.firstFrame(1_000L)
        LaunchInfo.engineStarted(BootLine.Engine(BootLine.Path.NATIVE, "native-engine-73"), 2_000L)
        LaunchInfo.gameFocused(10_000L)
        LaunchInfo.gameFocused(25_000L)
        assertTrue(LaunchInfo.cornerVisible(10_000L + LaunchInfo.GAME_FOCUS_SHOWN_MS - 1, gameRunning = false))
        assertFalse(LaunchInfo.cornerVisible(10_000L + LaunchInfo.GAME_FOCUS_SHOWN_MS, gameRunning = false))
        assertFalse(LaunchInfo.cornerVisible(50_000L, gameRunning = true))
    }

    @Test
    fun flavourIsStableForASeed() {
        assertEquals(LaunchInfo.flavor(42), LaunchInfo.flavor(42))
        assertTrue(LaunchInfo.FLAVOR.contains(LaunchInfo.flavor(-7)))
    }

    @Test fun memoryShowsFreeAndSwapAndHintsOnlyWhenLow() {
        val mb = 1024L
        val full = LaunchInfo.memory(MemoryReadout.Device(12_000 * mb, 2563 * mb, 5781 * mb, 293 * mb))
        assertEquals("memory 2563 MB free · swap 5488 of 5781 MB used", full[0])
        assertTrue(full.size == 2 && full[1].contains("closing other apps"))
        val roomy = LaunchInfo.memory(MemoryReadout.Device(12_000 * mb, 5000 * mb, 5781 * mb, 3000 * mb))
        assertEquals(1, roomy.size)
        assertEquals("memory 5000 MB free", LaunchInfo.memory(MemoryReadout.Device(12_000 * mb, 5000 * mb, 0, 0)).single())
    }

    @Test fun onlyMemoryLinesStayOnTheLoadingScreen() {
        val mb = 1024L
        val memory = LaunchInfo.memory(MemoryReadout.Device(12_000 * mb, 2563 * mb, 5781 * mb, 293 * mb))
        assertEquals(2, memory.size)
        assertTrue(memory.all(LaunchInfo::isMemoryLine))
        for (line in listOf("on     warmup,memory", "fex    no Proton follows Auto", "config fixed · remote", "remote ping"))
            assertFalse(line, LaunchInfo.isMemoryLine(line))
    }
}
