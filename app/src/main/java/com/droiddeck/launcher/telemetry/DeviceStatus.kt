package com.droiddeck.launcher.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.droiddeck.launcher.BuildConfig
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only state for the remote "ping" answer and the live status file: the installed build, the Remote control and
 * Debugging tools switches, wireless debugging and the saved Kadb pairing, power and thermals. Nothing here changes
 * a setting, and nothing carries a token, address or key.
 */
object DeviceStatus {
    private val registered = AtomicBoolean(false)

    fun json(context: Context): JSONObject {
        val app = context.applicationContext
        val out = JSONObject()
            .put("commit", BuildConfig.SOURCE_COMMIT.ifEmpty { BuildInfo.shortCommit() })
            .put("short_commit", BuildInfo.shortCommit())
            .put("branch", BuildInfo.branch())
            .put("build", BuildInfo.slug())
            .put("version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("remote_control", com.droiddeck.launcher.agent.AgentRemote.enabled(app))
            .put("debugging_tools", com.droiddeck.launcher.agent.AgentAccess.commandsEnabled(app))
            .put("wireless_debugging", flag { com.droiddeck.launcher.core.GlobalSettings.wirelessDebuggingOn(app) })
            .put("kadb_paired", flag { com.droiddeck.launcher.core.WirelessAdbFix.hasSavedPairing(app) })
        val sticky = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        if (sticky != null) {
            out.put("charging", sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) out.put("battery_pct", level * 100 / scale)
            val deci = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (deci != Int.MIN_VALUE) out.put("battery_c", deci / 10.0)
        } else {
            out.put("charging", "unknown")
        }
        val power = app.getSystemService(PowerManager::class.java)
        out.put("device_idle", flag { power!!.isDeviceIdleMode })
        if (Build.VERSION.SDK_INT >= 29) runCatching { power!!.currentThermalStatus }.getOrNull()?.let {
            out.put("thermal_status", thermalName(it))
        }
        out.put("self_update", runCatching { com.droiddeck.launcher.update.SelfUpdate.status(app) }.getOrDefault("unknown"))
        return out
    }

    /** The same fields as lines, for the live status file. */
    fun lines(context: Context): String {
        val json = json(context)
        return json.keys().asSequence().joinToString("\n") { "$it: ${json.opt(it)}" }
    }

    /** Adds the "device" section to every live status file, once per process. */
    fun registerLiveSection(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext
        LiveStatus.addSection("device") { lines(app) }
    }

    private fun flag(read: () -> Boolean): Any = runCatching { read() }.getOrNull() ?: "unknown"

    internal fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> status.toString()
    }
}
