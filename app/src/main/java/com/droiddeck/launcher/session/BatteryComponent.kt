package com.droiddeck.launcher.session

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.telemetry.GamePhase
import com.droiddeck.launcher.telemetry.HeadroomCache
import com.droiddeck.launcher.telemetry.PowerCsv
import com.droiddeck.launcher.telemetry.PowerMath
import com.droiddeck.launcher.telemetry.PowerRow
import java.io.File
import kotlin.math.abs

/**
 * The guest's battery: a fake /sys/class/power_supply/BAT1 (and the vpower files) rewritten every
 * [PERIOD_MS] from Android's battery readings. With [powerLog] it also appends the same readings,
 * plus thermal status, screen brightness and whether a game is in front, to the session's
 * power.csv ([PowerRow]): the power log rides on this thread's existing tick and adds no wakeups.
 */
class BatteryComponent(val dir: File, private val vpowerDir: File, private val powerLog: File? = null) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var csv: PowerCsv? = null

    override fun start() {
        File(dir, "BAT0").deleteRecursively()
        running = true
        val log = powerLog?.let { runCatching { PowerCsv.open(it) }.onFailure { e -> Log.w(TAG, "power log: $e") }.getOrNull() }
        synchronized(this) { csv = log }
        write()
        thread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (running) write()
            }
            synchronized(this) { log?.close(); if (csv === log) csv = null }
        }, "battery-sysfs").apply { isDaemon = true; start() }
        Log.i(TAG, "battery: BAT1 written to ${dir.path} every ${PERIOD_MS / 1000} s" + (powerLog?.let { ", logged to ${it.name}" } ?: ""))
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        for (name in VPOWER_FILES) File(vpowerDir, name).delete()
    }

    private fun write() {
        val ctx = app() ?: return
        try {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val sticky: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
            val pct = if (level >= 0) level * 100 / scale else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 50)
            val voltageUv = ((sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).toLong() * 1000L).takeIf { it > 0 } ?: 3_800_000L
            val tempDeci = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() } ?: "Li-ion"
            val chargeNowUah = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 && it != Long.MIN_VALUE } ?: 0L
            val currentNowUa = toMicroamps(bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Long.MIN_VALUE } ?: 0L)
            val currentAvgUa = toMicroamps(bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.takeIf { it != Long.MIN_VALUE && it != 0L } ?: 0L)
                .takeIf { it != 0L } ?: currentNowUa
            val discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
            val chargeFullUah = if (chargeNowUah > 0 && pct > 0) chargeNowUah * 100 / pct else 0L
            val energyNowUwh = chargeNowUah * voltageUv / 1_000_000L
            val energyFullUwh = chargeFullUah * voltageUv / 1_000_000L
            val powerNowUw = abs(currentNowUa) * voltageUv / 1_000_000L
            val timeToEmptyS = if (discharging && chargeNowUah > 0 && abs(currentAvgUa) > 0) chargeNowUah * 3600L / abs(currentAvgUa) else 0L
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            val timeToFullS = if (charging && chargeFullUah > chargeNowUah && abs(currentAvgUa) > 0) (chargeFullUah - chargeNowUah) * 3600L / abs(currentAvgUa) else 0L
            val statusText = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> if (pct >= 100) "Full" else "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
            val capacityLevel = when {
                status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100 -> "Full"
                pct <= 5 -> "Critical"
                pct <= 15 -> "Low"
                else -> "Normal"
            }
            for (name in listOf("BAT1")) {
                val bat = File(dir, name).apply { mkdirs() }
                val attrs = linkedMapOf(
                    "type" to "Battery",
                    "present" to "1",
                    "status" to statusText,
                    "capacity" to pct.toString(),
                    "capacity_level" to capacityLevel,
                    "technology" to tech,
                    "voltage_now" to voltageUv.toString(),
                    "voltage_min_design" to voltageUv.toString(),
                    "current_now" to currentNowUa.toString(),
                    "current_avg" to currentAvgUa.toString(),
                    "charge_now" to chargeNowUah.toString(),
                    "charge_full" to chargeFullUah.toString(),
                    "charge_full_design" to chargeFullUah.toString(),
                    "energy_now" to energyNowUwh.toString(),
                    "energy_full" to energyFullUwh.toString(),
                    "energy_full_design" to energyFullUwh.toString(),
                    "power_now" to powerNowUw.toString(),
                    "time_to_empty_now" to timeToEmptyS.toString(),
                    "time_to_empty_avg" to timeToEmptyS.toString(),
                    "time_to_full_now" to timeToFullS.toString(),
                    "time_to_full_avg" to timeToFullS.toString(),
                    "temp" to tempDeci.toString(),
                    "model_name" to android.os.Build.MODEL.replace(' ', '_'),
                    "manufacturer" to android.os.Build.MANUFACTURER.replace(' ', '_'),
                    "scope" to "System",
                )
                for ((k, v) in attrs) put(File(bat, k), v + "\n")
                val uevent = StringBuilder("POWER_SUPPLY_NAME=$name\n")
                for ((k, v) in attrs) uevent.append("POWER_SUPPLY_").append(k.uppercase()).append('=').append(v).append('\n')
                put(File(bat, "uevent"), uevent.toString())
            }
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            vpowerDir.mkdirs()
            val vpower = linkedMapOf(
                "ac_status" to if (plugged) "Connected" else "Disconnected",
                "battery_percent" to "%.2f".format(java.util.Locale.ROOT, pct.toDouble()),
                "battery_status" to statusText,
                "secs_until_battery_full" to (if (timeToFullS > 0) timeToFullS else -1L).toString(),
                "secs_until_shutdown_request" to (if (timeToEmptyS > 0) timeToEmptyS else -1L).toString(),
            )
            for ((k, v) in vpower) put(File(vpowerDir, k), v + "\n")
            if (powerLog != null) logPower(ctx, currentNowUa, chargeNowUah, sticky, pct, status, tempDeci)
        } catch (t: Throwable) {
            Log.w(TAG, "battery: could not write", t)
        }
    }

    /** One power.csv row from the readings [write] already made, plus the cheap extras it does not need. */
    private fun logPower(ctx: android.content.Context, currentUa: Long, chargeUah: Long, sticky: Intent?, pct: Int, status: Int, tempDeci: Int) {
        if (synchronized(this) { csv } == null) return
        val pluggedBits = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }
        val current = currentUa.takeIf { it != 0L }
        val power = runCatching { ctx.getSystemService(PowerManager::class.java) }.getOrNull()
        val resolver = ctx.contentResolver
        val row = PowerRow(
            tWallMs = System.currentTimeMillis(),
            currentUa = current,
            chargeUah = chargeUah.takeIf { it > 0 },
            voltageMv = voltageMv,
            powerMw = PowerMath.drawMw(current, voltageMv, pluggedBits != 0),
            levelPct = pct.takeIf { it in 0..100 },
            status = PowerMath.statusName(status),
            plugged = PowerMath.pluggedName(pluggedBits),
            tempC = sticky?.let { tempDeci / 10.0 },
            thermalStatus = if (power != null && Build.VERSION.SDK_INT >= 29) runCatching { power.currentThermalStatus }.getOrNull() else null,
            headroom = if (power != null && Build.VERSION.SDK_INT >= 30) HeadroomCache.shared.get { power.getThermalHeadroom(10) } else null,
            brightness = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull(),
            autoBrightness = runCatching {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }.getOrNull(),
            screenOn = power?.let { runCatching { it.isInteractive }.getOrNull() },
            game = GamePhase.inGame,
        )
        synchronized(this) { csv?.add(row) }
    }

    private fun put(target: File, text: String) {
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
    }

    companion object {
        private const val TAG = "SessionService"

        internal fun toMicroamps(raw: Long): Long = PowerMath.toMicroamps(raw)
        private const val PERIOD_MS = 5_000L
        private val VPOWER_FILES = listOf(
            "ac_status", "battery_percent", "battery_status",
            "secs_until_battery_full", "secs_until_shutdown_request",
        )
    }
}
