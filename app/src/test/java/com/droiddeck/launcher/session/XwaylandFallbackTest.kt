package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.session.XwaylandFallback.Decision
import com.droiddeck.launcher.session.XwaylandFallback.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XwaylandFallbackTest {
    @After fun reset() { XwaylandFallback.onFirstFrame = null }

    // Session 2026-11-27-32-steam on a Pixel 10a: the app killed the bridge server at stop, then Xwayland fell over.
    private val crashAtStop = listOf(
        "== STEP 06:27:24.814 zRAM: done (9 processes, resident 1407 MB, in zram 5 MB)",
        XwaylandFallback.stopLine(0),
        "vkbridge error [Xwayland 2557]: vkGetSemaphoreFdKHR: lost the bridge server while waiting for the reply",
        "MESA: error: zink: DEVICE LOST!",
        "(EE) glamor0: GL error: GL_OUT_OF_MEMORY in glTexSubImage",
        "(EE) 9: Xwayland (0x5555000000+0x12448c) [0x555512448c]",
        "(EE) Segmentation fault at address 0x98",
        "(EE) Caught signal 11 (Segmentation fault). Server aborting",
    )

    @Test fun aCrashAfterTheStopBeganIsNotACrashOfTheSession() {
        assertFalse(XwaylandFallback.crashedBeforeStop(crashAtStop.asSequence()))
    }

    @Test fun aCrashWhileTheSessionRanCounts() {
        val running = crashAtStop.filterNot { it.startsWith(XwaylandFallback.STOP_MARK) } + XwaylandFallback.stopLine(134)
        assertTrue(XwaylandFallback.crashedBeforeStop(running.asSequence()))
        assertTrue(XwaylandFallback.crashedBeforeStop(sequenceOf("(EE) Segmentation fault at address 0x0", "X11 I/O error")))
    }

    @Test fun aSegmentationFaultOfAnotherProgramDoesNotCount() {
        assertFalse(XwaylandFallback.crashedBeforeStop(sequenceOf("(EE) Segmentation fault at address 0x0", "steam exited")))
        assertFalse(XwaylandFallback.crashedBeforeStop(sequenceOf("Xwayland started", "all fine")))
    }

    @Test fun theFallbackIsNotAppliedWhereSteamCannotStartWithoutGlamor() {
        assertEquals(Decision.NOT_ON_ROUTE, XwaylandFallback.decide(false, Route.OPEN_PAIR, crashed = true, undrawn = false))
        assertEquals(Decision.NOT_ON_ROUTE, XwaylandFallback.decide(false, Route.BRIDGE, crashed = true, undrawn = false))
        assertEquals(Decision.APPLY, XwaylandFallback.decide(false, Route.VENUS, crashed = true, undrawn = false))
        assertEquals(Decision.APPLY, XwaylandFallback.decide(false, Route.OTHER, crashed = true, undrawn = false))
    }

    @Test fun nothingHappensWithoutACrashOrOnAnAdreno() {
        for (route in Route.values()) {
            assertEquals(Decision.NONE, XwaylandFallback.decide(false, route, crashed = false, undrawn = false))
            assertEquals(Decision.NONE, XwaylandFallback.decide(true, route, crashed = true, undrawn = true))
        }
    }

    @Test fun aFallbackSessionThatDrewNothingIsNotFollowedByAnother() {
        for (route in Route.values()) for (crashed in listOf(true, false))
            assertEquals(Decision.UNDRAWN, XwaylandFallback.decide(false, route, crashed, undrawn = true))
        val line = XwaylandFallback.line(Decision.UNDRAWN, Route.OTHER)!!
        assertTrue(line, line.contains("drew no frame") && line.contains("glamor is back on"))
    }

    @Test fun onlyTheAppliedFallbackNamesTheVariableAsSet() {
        assertNull(XwaylandFallback.line(Decision.NONE, Route.OTHER))
        assertTrue(XwaylandFallback.line(Decision.APPLY, Route.OTHER)!!.contains("glamor off"))
        val skipped = XwaylandFallback.line(Decision.NOT_ON_ROUTE, Route.OPEN_PAIR)!!
        assertTrue(skipped, skipped.contains("glamor stays on") && skipped.contains("an open driver pair"))
    }

    @Test fun theFirstFrameClearsTheMarkOnce() {
        var cleared = 0
        XwaylandFallback.onFirstFrame = { cleared++ }
        XwaylandFallback.firstFrame()
        XwaylandFallback.firstFrame()
        assertEquals(1, cleared)
    }

    private class Part(val name: String, val afterGuest: Boolean = false) : SessionPart() {
        override fun start() {}
        override fun stop() {}
        override fun stopsAfterGuest() = afterGuest
    }

    @Test fun theGuestsVulkanServerStopsAfterTheGuest() {
        val started = listOf(Part("audio"), Part("bridge", afterGuest = true), Part("battery"), Part("rumble"))
        val (now, afterGuest) = SessionStopOrder.split(started)
        assertEquals(listOf("rumble", "battery", "audio"), now.map { (it as Part).name })
        assertEquals(listOf("bridge"), afterGuest.map { (it as Part).name })
    }

    @Test fun theBridgeServerIsTheOneThatWaits() {
        val bridge = com.droiddeck.launcher.gpu.VkBridgeComponent(java.io.File("server.sock"), java.io.File("server.log"))
        assertTrue(bridge.stopsAfterGuest())
        assertFalse(Part("audio").stopsAfterGuest())
    }
}
