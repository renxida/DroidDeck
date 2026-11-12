package com.droiddeck.launcher.session

import com.droiddeck.launcher.SessionActivity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TsoLitmusTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clean() {
        SessionState.running = false
    }

    @Test fun theTestRunsAsAProgramSessionOfItsOwn() {
        val intent = TsoLitmus.intent(context)
        assertEquals(SessionActivity::class.java.name, intent.component?.className)
        assertEquals(SessionService.MODE_RUN, intent.getStringExtra(SessionService.EXTRA_MODE))
        assertEquals("/usr/local/bin/droiddeck-tso-litmus", intent.getStringExtra(SessionService.EXTRA_PROGRAM))
        assertArrayEquals(emptyArray<String>(), intent.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS))
    }

    @Test fun itStartsOnlyWhenNoSessionIsRunning() {
        SessionState.running = true
        assertFalse(TsoLitmus.start(context))
        assertNull(shadowOf(context).nextStartedActivity)
        SessionState.running = false
        assertTrue(TsoLitmus.start(context))
        assertEquals(TsoLitmus.PROGRAM, shadowOf(context).nextStartedActivity.getStringExtra(SessionService.EXTRA_PROGRAM))
    }
}
