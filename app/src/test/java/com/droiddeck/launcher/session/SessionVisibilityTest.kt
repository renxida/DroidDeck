package com.droiddeck.launcher.session

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVisibilityTest {
    @Test fun normalPanelStillRequiresAnInteractiveScreen() {
        assertTrue(sessionHasVisibleSurface(true, true))
        assertFalse(sessionHasVisibleSurface(false, true))
        assertFalse(sessionHasVisibleSurface(true, false))
        assertFalse(sessionHasVisibleSurface(false, false))
    }

    @Test fun phoneDisplayFollowsTheInteractiveState() {
        assertTrue(sessionScreenOn(true, true, null))
        assertFalse(sessionScreenOn(true, false, null))
        assertFalse(sessionScreenOn(true, false, Display.STATE_ON))
    }

    @Test fun externalDisplayKeepsTheSessionAwakeWhileThePhoneScreenIsOff() {
        assertTrue(sessionScreenOn(false, false, Display.STATE_ON))
        assertTrue(sessionScreenOn(false, false, Display.STATE_ON_SUSPEND))
        assertTrue(sessionScreenOn(false, false, Display.STATE_VR))
    }

    @Test fun externalDisplayTurnedOffSuspendsEvenWithThePhoneScreenOn() {
        assertFalse(sessionScreenOn(false, true, Display.STATE_OFF))
        assertFalse(sessionScreenOn(false, true, Display.STATE_DOZE))
        assertFalse(sessionScreenOn(false, true, Display.STATE_DOZE_SUSPEND))
    }

    @Test fun missingExternalDisplayFallsBackToThePhone() {
        assertTrue(sessionScreenOn(false, true, null))
        assertFalse(sessionScreenOn(false, false, null))
    }

    @Test fun unknownExternalDisplayStateFallsBackToThePhone() {
        assertTrue(sessionScreenOn(false, true, Display.STATE_UNKNOWN))
        assertFalse(sessionScreenOn(false, false, Display.STATE_UNKNOWN))
    }
}
