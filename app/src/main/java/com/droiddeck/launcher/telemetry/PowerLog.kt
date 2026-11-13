package com.droiddeck.launcher.telemetry

import java.io.File
import java.io.Writer
import java.util.Locale
import kotlin.math.abs

/**
 * power.csv: one row every few seconds from the session's battery sampler (session/BatteryComponent),
 * which already reads the battery for the guest's fake sysfs, so the log costs one line write and a
 * few cheap system calls per tick. Columns are [PowerRow.HEADER].
 *
 * Units and signs: current_ua is BATTERY_PROPERTY_CURRENT_NOW in µA as the device reports it (values
 * under 10 000 are taken as mA and scaled, as some vendors report mA). Android's convention, which
 * Pixels follow, is positive into the battery and negative when discharging. power_mw is the power
 * drawn from the battery: |current| × voltage while unplugged (whatever the sign), and
 * −current × voltage while plugged in (negative while charging). Summaries leave every plugged-in
 * interval out, so charging never counts as play.
 */
internal data class PowerRow(
    val tWallMs: Long,
    val currentUa: Long?,
    val chargeUah: Long?,
    val voltageMv: Int?,
    val powerMw: Double?,
    val levelPct: Int?,
    val status: String,
    val plugged: String,
    val tempC: Double?,
    val thermalStatus: Int?,
    val headroom: Float?,
    val brightness: Int?,
    val autoBrightness: Boolean?,
    val screenOn: Boolean?,
    val game: Boolean,
) {
    val unplugged: Boolean get() = plugged == PLUGGED_NONE

    fun csv(): String = listOf(
        tWallMs.toString(), currentUa?.toString().orEmpty(), chargeUah?.toString().orEmpty(), voltageMv?.toString().orEmpty(),
        powerMw?.let { String.format(Locale.US, "%.0f", it) }.orEmpty(), levelPct?.toString().orEmpty(), status, plugged,
        tempC?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(), thermalStatus?.toString().orEmpty(),
        headroom?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
        brightness?.toString().orEmpty(), bit(autoBrightness), bit(screenOn), if (game) "1" else "0",
    ).joinToString(",")

    companion object {
        const val FILE = "power.csv"
        const val HEADER = "t_wall_ms,current_ua,charge_uah,voltage_mv,power_mw,level_pct,status,plugged,temp_c," +
            "thermal_status,headroom,brightness,auto_brightness,screen_on,game"
        const val PLUGGED_NONE = "none"

        private fun bit(value: Boolean?) = when (value) { null -> ""; true -> "1"; false -> "0" }

        /** A row by [header]'s column names, or null for the header, a comment or a row without a time. */
        fun parse(header: List<String>, line: String): PowerRow? {
            if (line.isEmpty() || !line[0].isDigit()) return null
            val cells = line.split(',')
            fun cell(name: String): String? = header.indexOf(name).takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim() }
                ?.takeIf { it.isNotEmpty() }
            val t = cell("t_wall_ms")?.toLongOrNull() ?: return null
            return PowerRow(
                tWallMs = t,
                currentUa = cell("current_ua")?.toLongOrNull(),
                chargeUah = cell("charge_uah")?.toLongOrNull(),
                voltageMv = cell("voltage_mv")?.toIntOrNull(),
                powerMw = cell("power_mw")?.toDoubleOrNull(),
                levelPct = cell("level_pct")?.toIntOrNull(),
                status = cell("status") ?: "unknown",
                plugged = cell("plugged") ?: PLUGGED_NONE,
                tempC = cell("temp_c")?.toDoubleOrNull(),
                thermalStatus = cell("thermal_status")?.toIntOrNull(),
                headroom = cell("headroom")?.toFloatOrNull(),
                brightness = cell("brightness")?.toIntOrNull(),
                autoBrightness = cell("auto_brightness")?.let { it == "1" },
                screenOn = cell("screen_on")?.let { it == "1" },
                game = cell("game") == "1",
            )
        }
    }
}

/** Unit conversions behind power.csv; pure, so unit tests cover them without a device. */
internal object PowerMath {
    private const val MILLIAMP_CEILING = 10_000L

    /** Some vendors report CURRENT_NOW in mA: anything under 10 A-as-mA is scaled to µA. */
    fun toMicroamps(raw: Long): Long = if (abs(raw) < MILLIAMP_CEILING) raw * 1000L else raw

    /** µA × mV = nW; / 1e6 gives mW. */
    fun milliwatts(currentUa: Long, voltageMv: Int): Double = currentUa.toDouble() * voltageMv / 1e6

    /**
     * Power drawn from the battery in mW. Unplugged, the battery is the only source, so it is
     * |current| × voltage whatever sign the vendor uses. Plugged in, Android's sign decides:
     * positive current is charge going in, so the draw is negative.
     */
    fun drawMw(currentUa: Long?, voltageMv: Int?, plugged: Boolean): Double? {
        if (currentUa == null || voltageMv == null || voltageMv <= 0) return null
        return if (plugged) -milliwatts(currentUa, voltageMv) else milliwatts(abs(currentUa), voltageMv)
    }

    /** Full capacity in mAh from the charge counter (µAh) and the level, or null when either is unknown. */
    fun fullMah(chargeUah: Long?, levelPct: Int?): Double? {
        if (chargeUah == null || chargeUah <= 0 || levelPct == null || levelPct <= 0) return null
        return chargeUah / 1000.0 * 100.0 / levelPct
    }

    /** EXTRA_PLUGGED's bits as a word: none, ac, usb, wireless or dock. */
    fun pluggedName(bits: Int): String = when {
        bits == 0 -> PowerRow.PLUGGED_NONE
        bits and 1 != 0 -> "ac"
        bits and 2 != 0 -> "usb"
        bits and 4 != 0 -> "wireless"
        bits and 8 != 0 -> "dock"
        else -> "other"
    }

    /** BatteryManager.BATTERY_STATUS_* as a word. */
    fun statusName(status: Int): String = when (status) {
        2 -> "charging"
        3 -> "discharging"
        4 -> "not_charging"
        5 -> "full"
        else -> "unknown"
    }
}

/** What a run of power.csv rows adds up to. Game figures cover unplugged intervals with a game in front. */
internal data class PowerStats(
    val rows: Int,
    val gameMs: Long,
    val gameEnergyMwh: Double,
    val gameMah: Double,
    val gameCounterMah: Double?,
    val gameLevelDrop: Int?,
    val gameChargingMs: Long,
    val unpluggedMs: Long,
    val unpluggedEnergyMwh: Double,
    val fullMah: Double?,
    val last: PowerRow?,
) {
    val gameMinutes: Double get() = gameMs / 60_000.0
    /** Average power drawn over the game phase. */
    val gameAvgMw: Double? get() = if (gameMs > 0) gameEnergyMwh * 3_600_000.0 / gameMs else null
    /** Battery percent per hour of play: from the integrated current over the full capacity, else from the level drop. */
    val gamePctPerHour: Double? get() {
        if (gameMs <= 0) return null
        val hours = gameMs / 3_600_000.0
        fullMah?.takeIf { it > 0 }?.let { return gameMah / it * 100.0 / hours }
        return gameLevelDrop?.let { it / hours }
    }
    /** Energy per minute of play, mWh: the ranking figure for battery cost. */
    val gameMwhPerMin: Double? get() = gameAvgMw?.let { it / 60.0 }
    val unpluggedAvgMw: Double? get() = if (unpluggedMs > 0) unpluggedEnergyMwh * 3_600_000.0 / unpluggedMs else null

    private fun num(value: Double?, format: String) = value?.let { String.format(Locale.US, format, it) } ?: "-"

    /** power-summary.txt in the bundle, "key: value" lines. */
    fun summaryText(): String = buildString {
        appendLine("# power over the session from power.csv; game = unplugged with a game in front, charging left out")
        appendLine("rows: $rows")
        appendLine("game_minutes: ${num(gameMinutes, "%.2f")}")
        appendLine("game_avg_mw: ${num(gameAvgMw, "%.0f")}")
        appendLine("game_mah: ${num(gameMah.takeIf { gameMs > 0 }, "%.1f")}")
        appendLine("game_counter_mah: ${num(gameCounterMah, "%.1f")}")
        appendLine("game_pct_per_hour: ${num(gamePctPerHour, "%.1f")}")
        appendLine("game_mwh_per_min: ${num(gameMwhPerMin, "%.1f")}")
        appendLine("game_charging_minutes_excluded: ${num(gameChargingMs / 60_000.0, "%.2f")}")
        appendLine("unplugged_minutes: ${num(unpluggedMs / 60_000.0, "%.2f")}")
        appendLine("unplugged_avg_mw: ${num(unpluggedAvgMw, "%.0f")}")
        appendLine("battery_full_mah: ${num(fullMah, "%.0f")}")
    }

    /** The live file's power line. */
    fun liveLine(): String {
        val now = last?.let { r ->
            val state = if (r.unplugged) r.status else "${r.status} on ${r.plugged}"
            "now ${num(r.powerMw, "%.0f")} mW ($state, ${r.levelPct ?: "-"}%" +
                (r.voltageMv?.let { String.format(Locale.US, ", %.2f V", it / 1000.0) } ?: "") +
                (r.brightness?.let { ", brightness $it${if (r.autoBrightness == true) " auto" else ""}" } ?: "") + ")"
        } ?: "no power.csv rows yet"
        if (gameMs <= 0) return "$now; no unplugged game time yet" +
            (if (gameChargingMs > 0) "; charging excluded ${num(gameChargingMs / 60_000.0, "%.1f")} min" else "")
        return "$now; game ${num(gameMinutes, "%.1f")} min avg ${num(gameAvgMw, "%.0f")} mW, ${num(gameMah, "%.1f")} mAh, " +
            "${num(gamePctPerHour, "%.1f")} %/h, ${num(gameMwhPerMin, "%.1f")} mWh/min" +
            (if (gameChargingMs > 0) "; charging excluded ${num(gameChargingMs / 60_000.0, "%.1f")} min" else "")
    }

    companion object {
        fun of(text: String): PowerStats {
            val acc = PowerAccumulator()
            var header: List<String>? = null
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.startsWith("t_wall_ms,")) { header = line.split(','); continue }
                PowerRow.parse(header ?: continue, line)?.let(acc::add)
            }
            return acc.stats()
        }
    }
}

/**
 * Adds power.csv rows up interval by interval. An interval counts only when both of its ends are
 * unplugged and no more than [maxGapMs] apart (a longer gap means the app was stopped); it counts as
 * game time when the game was in front at both ends. Power is the trapezoid of the two ends, charge
 * the same over |current|.
 */
internal class PowerAccumulator(private val maxGapMs: Long = 30_000L) {
    private var prev: PowerRow? = null
    private var rows = 0
    private var gameMs = 0L
    private var gameEnergyMwMs = 0.0
    private var gameUaMs = 0.0
    private var gameCounterUah = 0L
    private var gameCounterSeen = false
    private var gameLevelDrop = 0
    private var gameLevelSeen = false
    private var gameChargingMs = 0L
    private var unpluggedMs = 0L
    private var unpluggedEnergyMwMs = 0.0
    private var fullSum = 0.0
    private var fullCount = 0

    fun add(row: PowerRow) {
        rows++
        PowerMath.fullMah(row.chargeUah, row.levelPct)?.let { fullSum += it; fullCount++ }
        val p = prev
        prev = row
        if (p == null) return
        val dt = row.tWallMs - p.tWallMs
        if (dt <= 0 || dt > maxGapMs) return
        val game = p.game && row.game
        if (!p.unplugged || !row.unplugged) {
            if (game) gameChargingMs += dt
            return
        }
        val mw = mean(p.powerMw, row.powerMw)
        val ua = mean(p.currentUa?.let { abs(it).toDouble() }, row.currentUa?.let { abs(it).toDouble() })
        if (mw != null) {
            unpluggedMs += dt
            unpluggedEnergyMwMs += mw * dt
        }
        if (!game || mw == null) return
        gameMs += dt
        gameEnergyMwMs += mw * dt
        if (ua != null) gameUaMs += ua * dt
        if (p.chargeUah != null && row.chargeUah != null && p.chargeUah > 0 && row.chargeUah > 0) {
            gameCounterUah += p.chargeUah - row.chargeUah
            gameCounterSeen = true
        }
        if (p.levelPct != null && row.levelPct != null) {
            gameLevelDrop += p.levelPct - row.levelPct
            gameLevelSeen = true
        }
    }

    private fun mean(a: Double?, b: Double?): Double? = when {
        a != null && b != null -> (a + b) / 2
        else -> a ?: b
    }

    fun stats(): PowerStats = PowerStats(
        rows = rows,
        gameMs = gameMs,
        gameEnergyMwh = gameEnergyMwMs / 3_600_000.0,
        gameMah = gameUaMs / 3.6e9,
        gameCounterMah = if (gameCounterSeen) gameCounterUah / 1000.0 else null,
        gameLevelDrop = if (gameLevelSeen) gameLevelDrop else null,
        gameChargingMs = gameChargingMs,
        unpluggedMs = unpluggedMs,
        unpluggedEnergyMwh = unpluggedEnergyMwMs / 3_600_000.0,
        fullMah = if (fullCount > 0) fullSum / fullCount else null,
        last = prev,
    )
}

/** Appends power.csv rows; past [maxBytes] it writes one note and stops. Used from one thread. */
internal class PowerCsv(private val out: Writer, private val maxBytes: Long = MAX_BYTES, startBytes: Long = 0L) {
    private var bytes = startBytes
    private var capped = false

    fun header() = write(PowerRow.HEADER + "\n")

    fun add(row: PowerRow) {
        if (write(row.csv() + "\n")) out.flush()
    }

    private fun write(text: String): Boolean {
        if (capped) return false
        if (bytes + text.length > maxBytes - 64) {
            capped = true
            out.write("# capped at $maxBytes bytes\n")
            out.flush()
            return false
        }
        out.write(text)
        bytes += text.length
        return true
    }

    fun close() = runCatching { out.close() }

    companion object {
        const val MAX_BYTES = 2L shl 20

        /** Opens [file] for appending, writing the header when it is new. */
        fun open(file: File): PowerCsv {
            val fresh = !file.isFile || file.length() == 0L
            val csv = PowerCsv(java.io.BufferedWriter(java.io.FileWriter(file, true), 4096), startBytes = file.length())
            if (fresh) csv.header()
            return csv
        }
    }
}

/** Whether a game (not Steam, not the bare compositor) is in front with frames flowing; set by [FrameLogComponent]. */
object GamePhase {
    @Volatile @JvmStatic var inGame: Boolean = false
}

/**
 * getThermalHeadroom answers NaN when asked more than about once a second, and both the clock log
 * and the power log ask; this keeps one answer for [minGapMs] so neither starves the other.
 */
internal class HeadroomCache(private val minGapMs: Long = 1_500L, private val now: () -> Long = System::currentTimeMillis) {
    private var at = Long.MIN_VALUE / 2
    private var value: Float? = null

    @Synchronized fun get(read: () -> Float): Float? {
        val t = now()
        if (t - at < minGapMs) return value
        at = t
        value = runCatching { read() }.getOrNull()?.takeIf { !it.isNaN() }
        return value
    }

    companion object {
        val shared = HeadroomCache()
    }
}
