package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.LauncherRejoin.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherRejoinTest {
    private val main = "android.intent.action.MAIN"
    private val launcher = setOf("android.intent.category.LAUNCHER")

    private fun rejoin(
        action: String? = main,
        categories: Set<String>? = launcher,
        running: Boolean = false,
        phase: SessionPhase = SessionPhase.STARTING_STEAM,
        stopRequested: Boolean = false,
        pipActive: Boolean = false,
        lastShown: Screen = Screen.SESSION,
    ) = LauncherRejoin.shouldRejoin(action, categories, running, phase, stopRequested, pipActive, lastShown)

    @Test
    fun launcherIconReturnsToASessionThatIsStillStarting() {
        for (phase in listOf(SessionPhase.PREPARING, SessionPhase.INSTALLING_RUNTIME, SessionPhase.STARTING_COMPOSITOR,
                SessionPhase.STARTING_GUEST, SessionPhase.STARTING_STEAM, SessionPhase.READY)) {
            assertTrue("$phase", rejoin(phase = phase))
        }
    }

    @Test
    fun launcherIconReturnsToARunningOrSuspendedSession() {
        assertTrue(rejoin(running = true, phase = SessionPhase.READY))
        assertTrue(rejoin(running = true, phase = SessionPhase.SUSPENDED))
        assertTrue(rejoin(running = false, phase = SessionPhase.SUSPENDED))
    }

    @Test
    fun noSessionToReturnTo() {
        assertFalse(rejoin(phase = SessionPhase.IDLE))
        assertFalse(rejoin(phase = SessionPhase.FAILED))
        assertFalse(rejoin(phase = SessionPhase.STOPPING))
        assertFalse(rejoin(running = true, phase = SessionPhase.STOPPING))
        assertFalse(rejoin(running = true, phase = SessionPhase.READY, stopRequested = true))
        assertFalse(rejoin(phase = SessionPhase.STARTING_STEAM, stopRequested = true))
    }

    @Test
    fun theLibraryStaysWhenThePlayerWentThereThemselves() {
        assertFalse(rejoin(lastShown = Screen.FRONT))
        assertFalse(rejoin(running = true, phase = SessionPhase.READY, lastShown = Screen.FRONT))
        assertFalse(rejoin(lastShown = Screen.NONE))
    }

    @Test
    fun aFloatingSessionIsLeftInPictureInPicture() {
        assertFalse(rejoin(running = true, phase = SessionPhase.READY, pipActive = true))
    }

    @Test
    fun onlyTheLauncherIconRejoins() {
        assertTrue(rejoin(categories = setOf("android.intent.category.CAR_LAUNCHER")))
        assertTrue(rejoin(categories = setOf("android.intent.category.DEFAULT", "android.intent.category.LAUNCHER")))
        // `am start -n`, an update or agent prompt, a game link, a shortcut picker and Steam integration all open the
        // front screen for their own reason.
        assertFalse(rejoin(action = null, categories = null))
        assertFalse(rejoin(action = main, categories = null))
        assertFalse(rejoin(action = "android.intent.action.VIEW", categories = setOf("android.intent.category.BROWSABLE")))
        assertFalse(rejoin(action = "dev.renxida.kestrel.LAUNCH_GAME", categories = setOf("android.intent.category.DEFAULT")))
        assertFalse(rejoin(action = "android.intent.action.CREATE_SHORTCUT", categories = setOf("android.intent.category.DEFAULT")))
    }

    @Test
    fun liveMatchesTheSessionPhases() {
        assertEquals(false, LauncherRejoin.live(false, SessionPhase.IDLE, false))
        assertEquals(true, LauncherRejoin.live(true, SessionPhase.IDLE, false))
        assertEquals(true, LauncherRejoin.live(false, SessionPhase.STARTING_GUEST, false))
        assertEquals(false, LauncherRejoin.live(false, SessionPhase.STARTING_GUEST, true))
    }
}
