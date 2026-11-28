package com.droiddeck.launcher.session

import android.content.Intent
import android.os.Looper
import com.droiddeck.launcher.agent.AgentBridgeProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class StopScreenCloseTest {
    @Before fun idle() = reset()
    @After fun reset() {
        SessionState.running = false
        SessionState.stopRequested = false
        SessionState.closeScreenForStop = false
        SessionState.screenShown = false
        SessionState.phase = SessionPhase.IDLE
    }

    /** The service's own handling of Stop, up to where it tears the guest down (native code from there on). */
    private fun stopFromTheNotification(service: SessionService) {
        service.onStartCommand(Intent().setAction(SessionService.ACTION_STOP), 0, 1)
    }

    private fun finishTheStop(service: SessionService) {
        val finish = SessionService::class.java.getDeclaredMethod(
            "finishSessionStop", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java)
        finish.isAccessible = true
        finish.invoke(service, 0, ReflectionHelpers.getField<Int>(service, "sessionGen"), { })
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Pixel 10a, Nov 27: three starts in a row closed their own screen and sat in SUSPENDED. */
    @Test fun aStopWithNoScreenOpenDoesNotCloseTheNextStartsScreen() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionState.phase = SessionPhase.STOPPING
        stopFromTheNotification(service)
        assertTrue("the stop asks an open screen to close", SessionState.closeScreenForStop)
        finishTheStop(service)
        assertEquals(SessionPhase.IDLE, SessionState.phase)
        assertFalse("no screen took the request, and the stop is over", SessionState.closeScreenForStop)
        assertFalse(SessionState.takeScreenClose())
    }

    @Test fun aScreenOpenedWhileTheStopStillRunsIsTheNextStartsToo() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        SessionState.phase = SessionPhase.STOPPING
        stopFromTheNotification(service)
        assertTrue(SessionState.closeScreenForStop)
        SessionState.screenCreated()
        assertFalse(SessionState.takeScreenClose())
    }

    @Test fun theOpenScreenStillClosesOnceForItsStop() {
        SessionState.running = true
        SessionState.closeScreenForStop = true
        SessionState.screenCreated()
        assertTrue(SessionState.takeScreenClose())
        assertFalse(SessionState.takeScreenClose())
    }

    @Test fun resumeCoversARunningSessionWithoutAScreen() {
        assertTrue(AgentBridgeProvider.resumable(running = true, suspended = true, screenShown = true))
        assertTrue(AgentBridgeProvider.resumable(running = true, suspended = true, screenShown = false))
        assertTrue(AgentBridgeProvider.resumable(running = true, suspended = false, screenShown = false))
        assertFalse(AgentBridgeProvider.resumable(running = true, suspended = false, screenShown = true))
        assertFalse(AgentBridgeProvider.resumable(running = false, suspended = true, screenShown = false))
    }

    @Test fun theSessionScreenIntentIsTheNotificationsResume() {
        val intent = AgentBridgeProvider.sessionScreen(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals(SessionService.ACTION_RESUME, intent.action)
        assertEquals(com.droiddeck.launcher.SessionActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun theAgentsStartActivityFinishingDoesNotEndTheSession() {
        assertFalse(SessionService.taskCarriesTheSession(com.droiddeck.launcher.agent.AgentStartActivity::class.java.name))
        assertFalse(SessionService.taskCarriesTheSession(com.droiddeck.launcher.ui.DualScreenActivity::class.java.name))
        assertTrue(SessionService.taskCarriesTheSession(com.droiddeck.launcher.SessionActivity::class.java.name))
        assertTrue(SessionService.taskCarriesTheSession(null))
    }
}
