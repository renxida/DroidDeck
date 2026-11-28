package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.BridgeDefault.Outcome
import com.droiddeck.launcher.session.BridgeDefault.Stored
import com.droiddeck.launcher.ui.bridgeAutoFailedText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BridgeDefaultTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val session get() = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val mali0 = BridgeDefault.mali
    private val usable0 = BridgeDefault.bridgeUsable
    private val pair0 = BridgeDefault.pairInstalled
    private val memory0 = Experiments.totalMemory

    @Before fun aMaliThatCanUseTheBridge() {
        session.edit().clear().commit()
        app.getSharedPreferences("experiments", Context.MODE_PRIVATE).edit().clear().commit()
        Experiments.totalMemory = { 7752904L * 1024 }
        BridgeDefault.mali = { true }
        BridgeDefault.bridgeUsable = { true }
        BridgeDefault.pairInstalled = { true }
        ShadowLog.clear()
    }

    @After fun restore() {
        BridgeDefault.mali = mali0
        BridgeDefault.bridgeUsable = usable0
        BridgeDefault.pairInstalled = pair0
        Experiments.totalMemory = memory0
    }

    private fun gate(on: Boolean) = Experiments.setOne(app, Experiments.BRIDGE_DEFAULT, on)
    private fun routeLines() = ShadowLog.getLogs().filter { it.tag == "BridgeDefault" }.map { it.msg }
    private fun stored(bridge: Boolean? = null, by: String = "", venus: Boolean = false, standIn: String = "",
                       driversAuto: Boolean = true, failed: String = "") = Stored(bridge, by, venus, standIn, driversAuto, failed)

    /** The Pixel 10a as the Nov 27 campaign found it: Auto installed the PanVK pair and switched the default-on bridge off. */
    private fun asAutoLeftAFreshInstall() {
        session.edit().putBoolean("vkBridge", false).putString("maliFallbackByAuto", "")
            .putString("gpuDriverMode", SessionPrefs.GPU_DRIVERS_AUTO).commit()
    }

    @Test fun theSwitchIsOffInAFreshInstallAndInTurnEverythingOn() {
        assertFalse(Experiments.BRIDGE_DEFAULT in Experiments.defaults)
        assertFalse(Experiments.BRIDGE_DEFAULT in Experiments.fresh(app))
        assertFalse(BridgeDefault.on(app))
        assertEquals(Outcome.NOT_HERE, BridgeDefault.decide(false, true, stored(bridge = false)).outcome)
    }

    @Test fun offNothingMoves() {
        asAutoLeftAFreshInstall()
        assertFalse(BridgeDefault.apply(app, "test"))
        assertFalse(SessionPrefs.vkBridge(app))
        assertEquals("", SessionPrefs.maliRouteBy(app))
        assertEquals(emptyList<String>(), routeLines())
    }

    @Test fun anAutomaticChoiceMovesToTheBridge() {
        asAutoLeftAFreshInstall()
        gate(true)
        assertTrue(BridgeDefault.apply(app, "session start"))
        assertTrue(SessionPrefs.vkBridge(app))
        assertEquals(BridgeDefault.AUTO, SessionPrefs.maliRouteBy(app))
        assertEquals(1, routeLines().size)
        assertTrue(routeLines()[0], routeLines()[0].startsWith("route (session start): the Mali bridge is this phone's route now (was bridge false, venus false, drivers Auto, not set by hand)"))
        assertFalse("the second start changes nothing", BridgeDefault.apply(app, "session start"))
        assertEquals(1, routeLines().size)
    }

    @Test fun aRouteSetByHandStays() {
        gate(true)
        SessionPrefs.setVkBridge(app, false)
        SessionPrefs.maliRouteSetByHand(app, "Mali bridge", false)
        assertEquals(BridgeDefault.USER, SessionPrefs.maliRouteBy(app))
        assertFalse(BridgeDefault.apply(app, "test"))
        assertFalse(SessionPrefs.vkBridge(app))

        SessionPrefs.setVenus(app, true)
        SessionPrefs.maliRouteSetByHand(app, "Mali (Venus)", true)
        assertFalse(BridgeDefault.apply(app, "test"))
        assertTrue(SessionPrefs.venus(app))
    }

    @Test fun whatOlderBuildsStoredIsReadAsExplicitOnlyWhereItCanBe() {
        assertTrue("Venus on without Auto's mark: its button", BridgeDefault.explicit(stored(venus = true)))
        assertFalse(BridgeDefault.explicit(stored(venus = true, standIn = "venus")))
        assertTrue("bridge on without Auto's mark: its button", BridgeDefault.explicit(stored(bridge = true)))
        assertFalse(BridgeDefault.explicit(stored(bridge = true, standIn = "bridge")))
        assertFalse("never stored: the source default", BridgeDefault.explicit(stored()))
        assertTrue("bridge off under drivers set by hand", BridgeDefault.explicit(stored(bridge = false, driversAuto = false)))
        assertFalse("bridge off under Auto: what endMaliFallback writes", BridgeDefault.explicit(stored(bridge = false)))
        assertTrue(BridgeDefault.explicit(stored(bridge = false, by = BridgeDefault.USER)))
        assertFalse(BridgeDefault.explicit(stored(bridge = true, by = BridgeDefault.AUTO)))
    }

    @Test fun decisions() {
        assertEquals(Outcome.NOT_HERE, BridgeDefault.decide(true, false, stored(bridge = false)).outcome)
        assertEquals(Outcome.SWITCH, BridgeDefault.decide(true, true, stored(bridge = false)).outcome)
        assertEquals(Outcome.ALREADY, BridgeDefault.decide(true, true, stored()).outcome)
        assertEquals(Outcome.ALREADY, BridgeDefault.decide(true, true, stored(bridge = true, standIn = "bridge")).outcome)
        assertEquals(Outcome.USER, BridgeDefault.decide(true, true, stored(bridge = false, driversAuto = false)).outcome)
        assertEquals(Outcome.FAILED_HERE, BridgeDefault.decide(true, true, stored(bridge = false, by = BridgeDefault.AUTO, failed = "draw")).outcome)
        val venusStandIn = BridgeDefault.decide(true, true, stored(bridge = false, venus = true, standIn = "venus"))
        assertEquals(BridgeDefault.Decision(Outcome.SWITCH, venusOff = true), venusStandIn)
    }

    @Test fun autosVenusStandInGivesWayToo() {
        gate(true)
        session.edit().putBoolean("vkBridge", false).putBoolean("venus", true).putString("maliFallbackByAuto", "venus").commit()
        assertTrue(BridgeDefault.apply(app, "test"))
        assertFalse(SessionPrefs.venus(app))
        assertTrue(SessionPrefs.vkBridge(app))
        assertEquals("", SessionPrefs.maliFallbackByAuto(app))
    }

    @Test fun switchingTheExperimentOffPutsTheRouteBack() {
        asAutoLeftAFreshInstall()
        gate(true)
        BridgeDefault.apply(app, "test")
        gate(false)
        assertTrue(BridgeDefault.apply(app, "launcher"))
        assertEquals(false, SessionPrefs.vkBridgeStored(app))
        assertEquals("", SessionPrefs.maliRouteBy(app))
        assertTrue(routeLines().last(), routeLines().last().startsWith("route (launcher): bridge-default is off; back to bridge false"))
        assertFalse(BridgeDefault.apply(app, "launcher"))
    }

    @Test fun offDoesNotUndoARouteTheUserHasSetSince() {
        asAutoLeftAFreshInstall()
        gate(true)
        BridgeDefault.apply(app, "test")
        SessionPrefs.setVkBridge(app, true)
        SessionPrefs.maliRouteSetByHand(app, "Mali bridge", true)
        gate(false)
        assertFalse(BridgeDefault.apply(app, "launcher"))
        assertTrue(SessionPrefs.vkBridge(app))
    }

    @Test fun aFailedSelfTestMovesAnAutomaticChoiceToThePairAndSaysSo() {
        asAutoLeftAFreshInstall()
        gate(true)
        BridgeDefault.apply(app, "test")
        assertTrue(BridgeDefault.bridgeFailed(app, BridgeDefault.FAILED_DRAW))
        assertFalse(SessionPrefs.vkBridge(app))
        assertEquals(BridgeDefault.FAILED_DRAW, SessionPrefs.bridgeAutoFailed(app))
        assertTrue(routeLines().last(), routeLines().last().startsWith(
            "route: the Mali bridge failed its self-test (images or draws); sessions use the open driver pair from now on"))
        assertFalse("and it is not switched back on at the next start", BridgeDefault.apply(app, "session start"))
        assertFalse(SessionPrefs.vkBridge(app))
        assertEquals(R.string.setup_gpu_bridge_auto_failed_draw, bridgeAutoFailedText(false, false, false, SessionPrefs.bridgeAutoFailed(app)))
    }

    @Test fun aBridgeSessionThatDrewNothingDoesTheSame() {
        asAutoLeftAFreshInstall()
        gate(true)
        BridgeDefault.apply(app, "test")
        assertEquals(BridgeDefault.AUTO, SessionPrefs.maliRouteBy(app))
        assertTrue(BridgeDefault.bridgeFailed(app, BridgeDefault.FAILED_NO_FRAME))
        assertFalse(SessionPrefs.vkBridge(app))
        assertTrue(routeLines().last(), routeLines().last().startsWith("route: the Mali bridge drew no frame in 60 s on screen; sessions use the open driver pair"))
        assertEquals(R.string.setup_gpu_bridge_auto_failed_no_frame, bridgeAutoFailedText(false, false, false, BridgeDefault.FAILED_NO_FRAME))
    }

    @Test fun whatCountsAsDrawingNothing() {
        assertTrue(BridgeDefault.drewNothing(firstFrame = false, shownMs = 60_000, clientInstalled = false))
        assertFalse(BridgeDefault.drewNothing(firstFrame = true, shownMs = 600_000, clientInstalled = false))
        assertFalse("stopped early", BridgeDefault.drewNothing(firstFrame = false, shownMs = 20_000, clientInstalled = false))
        assertFalse("Steam's client was installing", BridgeDefault.drewNothing(firstFrame = false, shownMs = 600_000, clientInstalled = true))
    }

    @Test fun aFailureLeavesAChoiceMadeByHandAndAPhoneWithoutAPairAlone() {
        gate(true)
        SessionPrefs.setVkBridge(app, true)
        SessionPrefs.maliRouteSetByHand(app, "Mali bridge", true)
        assertFalse(BridgeDefault.bridgeFailed(app, BridgeDefault.FAILED_DRAW))
        assertTrue(SessionPrefs.vkBridge(app))

        session.edit().clear().commit()
        asAutoLeftAFreshInstall()
        BridgeDefault.pairInstalled = { false }
        BridgeDefault.apply(app, "test")
        assertFalse(BridgeDefault.bridgeFailed(app, BridgeDefault.FAILED_MEMORY))
        assertTrue(SessionPrefs.vkBridge(app))
        assertTrue(routeLines().last(), routeLines().last().contains("no open driver pair is installed to fall back on"))
    }

    @Test fun theBridgeButtonClearsTheFallbackAndItsRowText() {
        asAutoLeftAFreshInstall()
        gate(true)
        BridgeDefault.apply(app, "test")
        BridgeDefault.bridgeFailed(app, BridgeDefault.FAILED_MEMORY)
        assertEquals(R.string.setup_gpu_bridge_auto_failed_memory, bridgeAutoFailedText(false, false, false, SessionPrefs.bridgeAutoFailed(app)))
        SessionPrefs.setVkBridge(app, true)
        SessionPrefs.maliRouteSetByHand(app, "Mali bridge", true)
        assertEquals("", SessionPrefs.bridgeAutoFailed(app))
        assertNull(bridgeAutoFailedText(false, true, false, SessionPrefs.bridgeAutoFailed(app)))
        assertNull(bridgeAutoFailedText(true, false, false, "draw"))
    }

    @Test fun autoKeepsTheBridgeOnlyWhileTheExperimentIsOnAndTheBridgeHasNotFailed() {
        assertFalse(BridgeDefault.keepsBridge(app))
        gate(true)
        assertTrue(BridgeDefault.keepsBridge(app))
        SessionPrefs.setBridgeAutoFailed(app, BridgeDefault.FAILED_DRAW)
        assertFalse(BridgeDefault.keepsBridge(app))
        SessionPrefs.setBridgeAutoFailed(app, "")
        BridgeDefault.bridgeUsable = { false }
        assertFalse(BridgeDefault.keepsBridge(app))
    }

    @Test fun otherGpusAreNotTouched() {
        BridgeDefault.mali = { false }
        gate(true)
        session.edit().putBoolean("vkBridge", false).commit()
        assertFalse(BridgeDefault.apply(app, "test"))
        assertFalse(BridgeDefault.bridgeFailed(app, BridgeDefault.FAILED_DRAW))
        assertEquals(setOf("vkBridge"), session.all.keys)
    }

    @Test fun theRouteButtonsCarryTagsInTheSettingsStyle() {
        assertEquals("setting-gpu-bridge", com.droiddeck.launcher.ui.TAG_ROUTE_BRIDGE)
        assertEquals("setting-gpu-venus", com.droiddeck.launcher.ui.TAG_ROUTE_VENUS)
    }
}
