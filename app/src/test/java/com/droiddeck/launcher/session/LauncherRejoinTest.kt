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
        inPip: Boolean = false,
        pipChosen: Boolean = false,
        lastShown: Screen = Screen.SESSION,
    ) = LauncherRejoin.shouldRejoin(action, categories, running, phase, stopRequested, inPip, pipChosen, lastShown)

    private fun expand(
        running: Boolean = true,
        phase: SessionPhase = SessionPhase.READY,
        stopRequested: Boolean = false,
        inPip: Boolean = true,
        pipChosen: Boolean = false,
        lastShown: Screen = Screen.SESSION,
    ) = LauncherRejoin.shouldExpandPip(running, phase, stopRequested, inPip, pipChosen, lastShown)

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

    // Off-phone rig, stand-in-background: Home during a game floated the session, and the icon showed the library.
    @Test
    fun launcherIconBringsASessionFloatedByLeavingTheAppBackFullScreen() {
        assertTrue(rejoin(running = true, phase = SessionPhase.READY, inPip = true))
        assertTrue(rejoin(running = true, phase = SessionPhase.SUSPENDED, inPip = true))
        assertTrue(expand())
        assertTrue(expand(phase = SessionPhase.SUSPENDED))
    }

    @Test
    fun aSessionFloatedWithItsOwnButtonStaysFloatingOverTheLibrary() {
        assertFalse(rejoin(running = true, phase = SessionPhase.READY, inPip = true, pipChosen = true))
        assertFalse(expand(pipChosen = true))
        // A stale choice from an earlier float does not hold back a full-screen session.
        assertTrue(rejoin(running = true, phase = SessionPhase.READY, inPip = false, pipChosen = true))
    }

    @Test
    fun theFrontScreenOnlyExpandsAFloatingLiveSessionThePlayerLeft() {
        assertFalse(expand(inPip = false))
        assertFalse(expand(lastShown = Screen.FRONT))
        assertFalse(expand(lastShown = Screen.NONE))
        assertFalse(expand(running = false, phase = SessionPhase.IDLE))
        assertFalse(expand(phase = SessionPhase.STOPPING))
        assertFalse(expand(stopRequested = true))
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
