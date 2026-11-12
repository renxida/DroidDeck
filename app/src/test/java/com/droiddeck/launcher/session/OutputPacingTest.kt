package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPacingTest {
    @Test fun phoneOutputIsUnpaced() {
        assertEquals(SessionPrefs.FPS_UNPACED, SessionPrefs.compositorFpsLimit())
    }

    @Test fun sessionLimitPacesThePhone() {
        assertEquals(45, SessionPrefs.compositorFpsLimit(45))
    }

    @Test fun pictureInPictureHoldsTheSessionToThirty() {
        assertEquals(SessionPrefs.PIP_FPS, SessionPrefs.compositorFpsLimit(pip = true))
        assertEquals(SessionPrefs.PIP_FPS, SessionPrefs.compositorFpsLimit(60, pip = true))
        assertEquals(20, SessionPrefs.compositorFpsLimit(20, pip = true))
    }
}
