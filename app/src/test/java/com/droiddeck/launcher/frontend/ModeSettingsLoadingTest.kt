package com.droiddeck.launcher.frontend

import android.os.Looper
import com.droiddeck.launcher.FrontEndActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ModeSettingsLoadingTest {
    @Test fun steamPublishesItsShaderChoiceThroughTheMainQueue() {
        val activity = Robolectric.buildActivity(FrontEndActivity::class.java).get()
        SessionPrefs.setShaderPrecache(activity, false)
        load(activity, SessionService.MODE_STEAM)
        awaitRead()
        assertNull(value(activity))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, value(activity))
    }

    private fun load(activity: FrontEndActivity, mode: String) {
        FrontEndActivity::class.java.getDeclaredMethod("loadShaderPrecache", String::class.java)
            .apply { isAccessible = true }.invoke(activity, mode)
    }

    private fun value(activity: FrontEndActivity): Boolean? =
        FrontEndActivity::class.java.getDeclaredMethod("getShaderPrecache")
            .apply { isAccessible = true }.invoke(activity) as Boolean?

    private fun awaitRead() {
        Thread.getAllStackTraces().keys.filter { it.name == "shader-precache" }.forEach {
            it.join(5_000)
            assertFalse("Shader settings read did not finish", it.isAlive)
        }
    }
}
