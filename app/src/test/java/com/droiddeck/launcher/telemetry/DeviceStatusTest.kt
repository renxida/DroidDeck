package com.droiddeck.launcher.telemetry

import android.app.Application
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class DeviceStatusTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun statusNamesTheBuildAndReadsTheSwitchesWithoutChangingThem() {
        val status = DeviceStatus.json(context)
        assertEquals(BuildInfo.shortCommit(), status.getString("short_commit"))
        assertFalse(status.getBoolean("remote_control"))
        assertFalse(status.getBoolean("debugging_tools"))
        assertTrue(status.has("kadb_paired"))
        assertTrue(status.has("wireless_debugging"))
        assertTrue(status.has("device_idle"))
        assertFalse(status.getBoolean("kadb_install"))
        assertEquals("unknown", status.getString("kadb_reachable"))
        assertFalse(com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(context))
        assertFalse(com.droiddeck.launcher.agent.AgentRemote.enabled(context))
    }

    @Test fun statusCarriesNoSecrets() {
        val text = DeviceStatus.lines(context).lowercase()
        listOf("token", "password", "key", "secret").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test fun thermalStatusHasNames() {
        assertEquals("none", DeviceStatus.thermalName(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("severe", DeviceStatus.thermalName(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals("42", DeviceStatus.thermalName(42))
    }
}
