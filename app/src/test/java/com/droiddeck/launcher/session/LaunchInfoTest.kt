package com.droiddeck.launcher.session

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchInfoTest {
    @After fun tearDown() { LaunchInfo.reset(); Experiments.endSession() }

    @Test
    fun theModeNamesTheEngineAndTheFexBuild() {
        assertEquals("native engine (Proton if it can't start)", LaunchInfo.mode(setOf(Experiments.NATIVE_ENGINE, Experiments.FEX_KESTREL)))
        assertEquals("Proton on Kestrel FEX", LaunchInfo.mode(setOf(Experiments.FEX_KESTREL)))
        assertEquals("Proton on stock FEX", LaunchInfo.mode(emptySet()))
    }

    @Test
    fun theLinesCarryTheModeBuildSwitchesAndFexLine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Experiments.set(context, setOf(Experiments.NATIVE_ENGINE))
        Experiments.beginSession(context)
        LaunchInfo.fex = "fex: stock 2508"
        val lines = LaunchInfo.lines(context)
        assertTrue(lines.first(), lines.first().startsWith("mode   native engine"))
        assertTrue(lines.any { it.startsWith("build  ") })
        assertTrue(lines.any { it.startsWith("on     ") && Experiments.NATIVE_ENGINE in it })
        assertTrue(lines.contains("fex    stock 2508"))
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
}
