package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PadFoldPrefsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun aGameStartsFoldedAndKeepsThePersonsChoiceForThatGameOnly() {
        assertTrue(SessionPrefs.padFolded(context, "2868840"))
        SessionPrefs.setPadFolded(context, "2868840", false)
        assertFalse(SessionPrefs.padFolded(context, "2868840"))
        assertTrue(SessionPrefs.padFolded(context, "1145360"))
        SessionPrefs.setPadFolded(context, "2868840", true)
        assertTrue(SessionPrefs.padFolded(context, "2868840"))
    }
}
