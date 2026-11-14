package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSuspendPolicyTest {
    @Test fun aRunningGameKeepsAHiddenSteamSessionAwakeUnderEveryPolicy() {
        for (policy in PAUSING) {
            assertFalse(policy, suspends(policy, gameRunning = true))
            assertFalse(policy, suspends(policy, gameRunning = true, pauseRequested = true))
            assertTrue(policy, SessionSuspendPolicy.gameKeepsAwake(true, false, false, true) { true })
        }
        for (policy in listOf(SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE)) {
            assertFalse(policy, SessionSuspendPolicy.shouldSuspend(policy, true, false, false, true, false, true, true, false))
        }
    }

    @Test fun aHiddenSteamSessionWithoutAGameSuspends() {
        for (policy in PAUSING) assertTrue(policy, suspends(policy, gameRunning = false))
    }

    @Test fun manualHoldSuspendsEvenWithAGameRunning() {
        for (policy in PAUSING + SessionPrefs.SUSPEND_NEVER) {
            assertTrue(policy, suspends(policy, gameRunning = true, manualHold = true))
            assertFalse(policy, SessionSuspendPolicy.gameKeepsAwake(true, true, false, true) { true })
        }
    }

    @Test fun aSuspendedSessionStaysSuspendedWhenAGameIsRunning() {
        assertTrue(suspends(SessionPrefs.SUSPEND_AUTO, gameRunning = true, suspended = true))
        assertTrue(suspends(SessionPrefs.SUSPEND_NATIVE, gameRunning = true, suspended = true))
        assertTrue(suspends(SessionPrefs.SUSPEND_MANUAL, gameRunning = true, suspended = true, pauseRequested = true))
    }

    @Test fun aVisibleSessionDoesNotSuspendOrLookForAGame() {
        for (policy in PAUSING) {
            assertFalse(policy, suspends(policy, hidden = false, gameRunning = true))
            var looked = false
            assertFalse(SessionSuspendPolicy.gameKeepsAwake(false, false, false, true) { looked = true; true })
            assertFalse(looked)
        }
    }

    @Test fun downloadsKeepTheSessionAwakeAsBefore() {
        for (policy in PAUSING) assertFalse(policy, suspends(policy, gameRunning = false, downloads = true))
    }

    @Test fun otherModesSuspendWhateverRuns() {
        var looked = false
        assertFalse(SessionSuspendPolicy.gameKeepsAwake(true, false, false, false) { looked = true; true })
        assertFalse(looked)
        assertTrue(suspends(SessionPrefs.SUSPEND_AUTO, steamMode = false, gameRunning = true))
        assertTrue(suspends(SessionPrefs.SUSPEND_MANUAL, steamMode = false, gameRunning = true))
    }

    @Test fun neverPolicyOnlyFollowsSteamsOwnSleepRequest() {
        assertFalse(suspends(SessionPrefs.SUSPEND_NEVER, gameRunning = false))
        assertTrue(suspends(SessionPrefs.SUSPEND_NEVER, gameRunning = false, steamSleep = true))
        assertTrue(suspends(SessionPrefs.SUSPEND_AUTO, gameRunning = true, steamSleep = true))
    }

    @Test fun manualPauseRequestFollowsVisibility() {
        val manual = SessionPrefs.SUSPEND_MANUAL
        assertTrue(SessionSuspendPolicy.manualPauseRequested(manual, true, false, false, false, false))
        assertFalse(SessionSuspendPolicy.manualPauseRequested(manual, true, false, false, true, false))
        assertTrue(SessionSuspendPolicy.manualPauseRequested(manual, true, false, false, true, true))
        assertFalse(SessionSuspendPolicy.manualPauseRequested(manual, false, false, false, false, true))
        assertTrue(SessionSuspendPolicy.manualPauseRequested(manual, false, true, false, false, true))
        assertEquals(false, SessionSuspendPolicy.manualPauseRequested(SessionPrefs.SUSPEND_AUTO, true, false, false, false, false))
    }

    /** Runs the decisions in the order SessionService.updateSuspendPolicy does, with native sleep already granted. */
    private fun suspends(
        policy: String,
        gameRunning: Boolean,
        hidden: Boolean = true,
        steamMode: Boolean = true,
        manualHold: Boolean = false,
        suspended: Boolean = false,
        downloads: Boolean = false,
        pauseRequested: Boolean = false,
        steamSleep: Boolean = false,
    ): Boolean {
        val awake = SessionSuspendPolicy.gameKeepsAwake(hidden, manualHold, suspended, steamMode) { gameRunning }
        val requested = SessionSuspendPolicy.manualPauseRequested(policy, hidden, suspended, downloads, awake, pauseRequested)
        val pause = SessionSuspendPolicy.steamPause(policy, steamMode, hidden, manualHold, downloads, awake, requested)
        // SessionService ends native sleep whenever Steam need not pause, which clears nativeSleepReady.
        val nativeSleepReady = pause
        return SessionSuspendPolicy.shouldSuspend(
            policy, hidden, manualHold, downloads, awake, requested,
            prepared = !steamMode || nativeSleepReady, nativeSleepReady = nativeSleepReady, steamSleepRequested = steamSleep,
        )
    }

    private companion object {
        val PAUSING = listOf(SessionPrefs.SUSPEND_MANUAL, SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE)
    }
}
