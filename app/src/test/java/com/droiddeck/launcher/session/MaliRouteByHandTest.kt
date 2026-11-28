package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
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
class MaliRouteByHandTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
    }

    private fun routeLines() = ShadowLog.getLogs().map { it.msg }.filter { it.startsWith("route: ") }

    @Test fun aRouteButtonTakesTheRouteFromAutoAndSaysSo() {
        SessionPrefs.setMaliFallbackByAuto(app, "bridge")
        SessionPrefs.maliRouteSetByHand(app, "Mali (Venus)", true)
        assertEquals("", SessionPrefs.maliFallbackByAuto(app))
        assertTrue(SessionPrefs.maliFallbackKnown(app))
        assertEquals(listOf("route: Mali (Venus) switched on by hand; Auto leaves the Mali route alone from now on " +
            "(cleared its stand-in mark, which was \"bridge\")"), routeLines())
    }

    @Test fun withNoStandInTheLineIsShort() {
        SessionPrefs.maliRouteSetByHand(app, "Mali bridge", false)
        assertEquals(listOf("route: Mali bridge switched off by hand; Auto leaves the Mali route alone from now on"), routeLines())
    }
}
