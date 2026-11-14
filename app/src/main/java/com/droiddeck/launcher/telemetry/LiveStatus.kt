package com.droiddeck.launcher.telemetry

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The live status file the uploader rewrites every 30 s while a session runs (runs/.../<start>.live.txt):
 * settings, frame-time metrics for the last 30 s and the session so far, battery power, thermals, clocks, memory,
 * top processes, recent events and one history line per tick. Everything comes from the session
 * folder's own files, read incrementally, plus the sections other code adds through [addSection].
 */
object LiveStatus {
    const val SUFFIX = ".live.txt"
    const val HISTORY_LINES = 240
    internal const val WINDOW_MS = 30_000L

    private val sections = CopyOnWriteArrayList<Pair<String, () -> String?>>()

    /** Adds a titled section to every live file; providers run on the upload thread, and one returning null or blank is left out. */
    fun addSection(title: String, provider: () -> String?) {
        sections += title to provider
    }

    internal fun extraSections(): List<Pair<String, String>> = sections.mapNotNull { (title, provider) ->
        runCatching { provider() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { title to it.trimEnd() }
    }

    internal fun remotePath(zipPath: String): String = zipPath.removeSuffix(".zip") + SUFFIX
}

/** Frame-time summary; frames of a second or more count as pauses and are left out, as in tools/perf_report.py. */
internal data class FrameSummary(
    val frames: Int,
    val fps: Double?,
    val medianMs: Double?,
    val p95Ms: Double?,
    val p99Ms: Double?,
    val over50: Int,
    val over100: Int,
    val pauses: Int,
) {
    /** 1e6 / p99 frame time, the same 1% low tools/perf_report.py reports. */
    val low1Fps: Double? get() = p99Ms?.takeIf { it > 0 }?.let { 1000.0 / it }

    companion object {
        const val PAUSE_US = 1_000_000L
        val EMPTY = FrameSummary(0, null, null, null, null, 0, 0, 0)

        private fun index(n: Int, p: Double) = Math.round(p * (n - 1)).toInt().coerceIn(0, n - 1)

        fun of(frametimesUs: Collection<Long>): FrameSummary {
            val live = frametimesUs.filter { it in 1 until PAUSE_US }.sorted()
            val pauses = frametimesUs.count { it >= PAUSE_US }
            if (live.isEmpty()) return EMPTY.copy(pauses = pauses)
            fun ms(p: Double) = live[index(live.size, p)] / 1000.0
            return FrameSummary(live.size, live.size / (live.sum() / 1e6), ms(0.5), ms(0.95), ms(0.99),
                live.count { it > 50_000 }, live.count { it > 100_000 }, pauses)
        }
    }
}

/** Session-long frame-time histogram in 0.1 ms buckets: percentiles to 0.1 ms, counts and fps exact. */
internal class FrameHistogram {
    private val buckets = IntArray((FrameSummary.PAUSE_US / BUCKET_US).toInt())
    private var count = 0
    private var sumUs = 0L
    private var over50 = 0
    private var over100 = 0
    private var pauses = 0

    val frames: Int get() = count

    fun add(us: Long) {
        if (us <= 0) return
        if (us >= FrameSummary.PAUSE_US) { pauses++; return }
        count++
        sumUs += us
        if (us > 50_000) over50++
        if (us > 100_000) over100++
        buckets[(us / BUCKET_US).toInt()]++
    }

    private fun percentileMs(p: Double): Double? {
        if (count == 0) return null
        val target = Math.round(p * (count - 1))
        var seen = 0L
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen > target) return (i * BUCKET_US + BUCKET_US / 2) / 1000.0
        }
        return null
    }

    fun summary(): FrameSummary =
        if (count == 0) FrameSummary.EMPTY.copy(pauses = pauses)
        else FrameSummary(count, count / (sumUs / 1e6), percentileMs(0.5), percentileMs(0.95), percentileMs(0.99), over50, over100, pauses)

    private companion object {
        const val BUCKET_US = 100L
    }
}

/** Reads the session folder's files from where the last [sample] stopped. Not thread-safe: one caller. */
internal class LiveSampler(private val dir: File) {
    class Sample(
        val focus: String?,
        val gameRunning: Boolean,
        val gameSinceMs: Long?,
        val appId: String?,
        val lastExit: String?,
        val window: FrameSummary,
        val game: FrameSummary,
        val gameLabel: String,
        val all: FrameSummary,
        val cap: Int?,
        val gameCap: Int?,
        val capWhy: String?,
        val experiments: String?,
        val clocks: String?,
        val thermalStatus: Int?,
        val headroom: Double?,
        val batteryC: Double?,
        /** power.csv added up so far ([PowerStats]); null before its first row. */
        val power: PowerStats?,
        /** Average power drawn over the last 30 s of unplugged power.csv rows. */
        val windowMw: Double?,
        /** The newest memory.csv row by column name, blank columns left out. */
        val memory: Map<String, String>,
        val memoryAtMs: Long?,
        val processes: List<String>,
        val events: List<String>,
    ) {
        val gameRssMb: String? get() = memory["game_mb"]
        val gameSwapMb: String? get() = memory["game_swap_mb"]
        val gameMajfltS: String? get() = memory["game_majflt_s"]
        val swapinMbS: String? get() = memory["swapin_mb_s"]
    }

    private class Tail(val file: File) {
        var offset = 0L
        private val partial = StringBuilder()

        /** Calls [line] for each complete line written since the last read. */
        fun read(line: (String) -> Unit) {
            val length = file.length()
            if (length < offset) { offset = 0; partial.setLength(0) }
            if (length == offset) return
            RandomAccessFile(file, "r").use { f ->
                f.seek(offset)
                val buf = ByteArray(CHUNK)
                while (offset < length) {
                    val n = f.read(buf, 0, minOf(CHUNK.toLong(), length - offset).toInt())
                    if (n <= 0) break
                    offset += n
                    var start = 0
                    for (i in 0 until n) {
                        if (buf[i] == '\n'.code.toByte()) {
                            partial.append(String(buf, start, i - start, Charsets.ISO_8859_1))
                            line(partial.toString())
                            partial.setLength(0)
                            start = i + 1
                        }
                    }
                    if (start < n) partial.append(String(buf, start, n - start, Charsets.ISO_8859_1))
                }
            }
        }
    }

    private val frames = Tail(File(dir, FrameLogComponent.FILE))
    private val eventsTail = Tail(File(dir, "events.jsonl"))
    private val powerTail = Tail(File(dir, PowerRow.FILE))
    private var powerHeader: List<String>? = null
    private val powerAcc = PowerAccumulator()
    private var powerRows = 0
    private val powerWindow = ArrayDeque<Pair<Long, Double>>()
    private var t0WallMs: Long? = null
    private var focusEngine: String? = null
    private var focusPid = 0
    private var focusSteam: Boolean? = null
    private val windowTimes = ArrayDeque<Long>()
    private val windowFrames = ArrayDeque<Long>()
    private val gameHist = FrameHistogram()
    private val otherHist = FrameHistogram()
    private val allHist = FrameHistogram()
    private var gameRunning = false
    private var gameSinceMs: Long? = null
    private var appId: String? = null
    private var lastExit: String? = null
    private var cap: Int? = null
    private var gameCap: Int? = null
    private var capWhy: String? = null
    private var experiments: String? = null
    private val events = ArrayDeque<String>()
    private var started = false
    var startMs: Long = dir.lastModified()
        private set

    private val gameFocus = GameFocus()
    private var focusGame = false

    private fun focusIsGame() = focusGame

    fun sample(nowMs: Long): Sample {
        runCatching { eventsTail.read(::event) }
        runCatching { frames.read(::frameLine) }
        runCatching { powerTail.read(::powerLine) }
        val cutoff = nowMs - LiveStatus.WINDOW_MS
        while (windowTimes.isNotEmpty() && windowTimes.first() <= cutoff) { windowTimes.removeFirst(); windowFrames.removeFirst() }
        while (powerWindow.isNotEmpty() && powerWindow.first().first <= cutoff) powerWindow.removeFirst()
        val clockLine = runCatching { lastLine(File(dir, "clocks.log"), 4096) { CLOCK_LINE.containsMatchIn(it) } }.getOrNull()
        val (memory, memoryAt) = runCatching { memory() }.getOrNull() ?: (emptyMap<String, String>() to null)
        val focus = focusEngine?.let { "${if (focusSteam == true) "Steam" else it} pid $focusPid" }
        val game = if (gameHist.frames > 0 || otherHist.frames == 0) gameHist.summary() to "game"
            else otherHist.summary() to "non-Steam"
        return Sample(
            focus = focus,
            gameRunning = gameRunning || focusIsGame(),
            gameSinceMs = gameSinceMs,
            appId = appId,
            lastExit = lastExit,
            window = FrameSummary.of(windowFrames),
            game = game.first,
            gameLabel = game.second,
            all = allHist.summary(),
            cap = cap,
            gameCap = gameCap,
            capWhy = capWhy,
            experiments = experiments,
            clocks = clockLine,
            thermalStatus = clockLine?.let { THERMAL.find(it) }?.groupValues?.get(1)?.toIntOrNull(),
            headroom = clockLine?.let { HEADROOM.find(it) }?.groupValues?.get(1)?.toDoubleOrNull(),
            batteryC = clockLine?.let { BATTERY.find(it) }?.groupValues?.get(1)?.toDoubleOrNull(),
            power = if (powerRows > 0) powerAcc.stats() else null,
            windowMw = powerWindow.takeIf { it.isNotEmpty() }?.let { w -> w.sumOf { it.second } / w.size },
            memory = memory,
            memoryAtMs = memoryAt,
            processes = runCatching { processes() }.getOrDefault(emptyList()),
            events = events.toList(),
        )
    }

    private fun frameLine(line: String) {
        if (line.startsWith("#")) {
            if (t0WallMs == null) T0.find(line)?.let { t0WallMs = it.groupValues[1].toLong() }
            FOCUS.find(line)?.let { m ->
                focusEngine = m.groupValues[1]
                focusPid = m.groupValues[2].toIntOrNull() ?: 0
                focusSteam = m.groupValues[3] == "1"
                focusGame = gameFocus.isGame(focusEngine, focusPid, focusSteam == true)
            }
            return
        }
        val comma = line.indexOf(',')
        if (comma <= 0) return
        val t = line.substring(0, comma).toLongOrNull() ?: return
        val us = line.substring(comma + 1).trim().toLongOrNull() ?: return
        val t0 = t0WallMs ?: return
        allHist.add(us)
        if (focusIsGame()) gameHist.add(us) else if (focusSteam == false) otherHist.add(us)
        windowTimes.addLast(t0 + t)
        windowFrames.addLast(us)
        if (windowTimes.size > WINDOW_MAX_FRAMES) { windowTimes.removeFirst(); windowFrames.removeFirst() }
    }

    private fun powerLine(line: String) {
        if (line.startsWith("t_wall_ms,")) { powerHeader = line.trimEnd('\r').split(','); return }
        val row = PowerRow.parse(powerHeader ?: return, line.trimEnd('\r')) ?: return
        powerAcc.add(row)
        powerRows++
        if (row.unplugged) row.powerMw?.let { powerWindow.addLast(row.tWallMs to it) }
    }

    private fun event(line: String) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        val name = json.optString("event")
        val t = json.optLong("t")
        if (!started) {
            started = true
            if (t > 0) startMs = t
        }
        when (name) {
            "game.first_frame" -> { gameRunning = true; gameSinceMs = t }
            "game.exited" -> {
                gameRunning = false
                json.optString("app_id").takeIf { it.isNotEmpty() }?.let { appId = it }
                lastExit = "${stamp(t)} ${fields(json)}"
            }
            "steam.game_launch_requested" -> json.optString("gameId").takeIf { it.isNotEmpty() && it != "null" }?.let { appId = it }
            "pacing.cap" -> {
                cap = json.optInt("cap").takeIf { it > 0 }
                gameCap = json.optInt("game_cap").takeIf { it > 0 }
                capWhy = json.optString("why").takeIf { it.isNotEmpty() }
                json.optString("app_id").takeIf { it.isNotEmpty() && it != "null" }?.let { appId = it }
            }
            "game.experiments" -> experiments = json.optString("on") +
                (json.optString("pending").takeIf { it.isNotEmpty() }?.let { " (pending: $it)" } ?: "")
        }
        if (name in NOTABLE || NOTABLE_WORDS.any { name.contains(it) }) {
            events.addLast("${stamp(t)} $name ${fields(json)}".trimEnd().take(EVENT_CHARS))
            if (events.size > EVENTS) events.removeFirst()
        }
    }

    private fun stamp(t: Long) = "+" + LiveText.duration(t - startMs)

    private fun fields(json: JSONObject): String = json.keys().asSequence()
        .filter { it !in setOf("t", "event", "sessionId") }
        .joinToString(" ") { "$it=${json.opt(it).toString().replace('\n', ' ').take(80)}" }

    private fun memory(): Pair<Map<String, String>, Long?>? {
        val file = File(dir, "memory.csv")
        val header = firstLine(file)?.split(',') ?: return null
        val row = lastLine(file, 2048) { it.isNotEmpty() && it[0].isDigit() }?.split(',') ?: return null
        val values = LinkedHashMap<String, String>()
        header.forEachIndexed { i, name -> row.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { values[name.trim()] = it } }
        val at = values.remove("t_wall_ms")?.toLongOrNull()
        return values to at
    }

    private fun processes(): List<String> {
        val file = File(dir, "processes.csv")
        if (!file.isFile) return emptyList()
        val rows = tailLines(file, 32 * 1024).map { it.split(',') }
            .filter { it.size >= 7 && it[0].isNotEmpty() && it[0][0].isDigit() }
        val newest = rows.maxOfOrNull { it[0].toLongOrNull() ?: 0L } ?: return emptyList()
        return rows.filter { it[0].toLongOrNull() == newest }
            .sortedWith(compareByDescending<List<String>> { it[6].toDoubleOrNull() ?: -1.0 }.thenByDescending { it[4].toLongOrNull() ?: 0L })
            .take(PROCESSES)
            .map { String.format(Locale.US, "%6s%% rss %5s MB swap %4s MB %-9s %s (pid %s)",
                it[6].ifEmpty { "?" }, it[4], it[5], it[3], it[2].take(40), it[1]) }
    }

    companion object {
        private const val CHUNK = 256 * 1024
        private const val WINDOW_MAX_FRAMES = 30 * 240
        private const val EVENTS = 8
        private const val EVENT_CHARS = 200
        private const val PROCESSES = 6
        private val NOTABLE = setOf("game.first_frame", "game.exited", "pacing.cap", "game.experiments",
            "steam.game_launch_requested", "steam.signed_in", "steam.return_after_game", "guest.exited", "session.ready",
            "app.exit", "exit.classified", "memory.lmk_risk", "memory.trim", "memory.freed", "session.paged_out", "session.paged_in")
        private val NOTABLE_WORDS = listOf("fail", "error", "crash", "abnormal")
        private val T0 = Regex("""t0_wall_ms=(\d+)""")
        private val FOCUS = Regex("""^# t_ms=-?\d+ engine=(\S+) pid=(\d+) steam=(\d)""")
        private val CLOCK_LINE = Regex("""^\d\d:\d\d:\d\d """)
        private val THERMAL = Regex("""\bthermal (\d+)""")
        private val HEADROOM = Regex("""\bheadroom (\d+(?:\.\d+)?)""")
        private val BATTERY = Regex("""\bbattery (-?\d+(?:\.\d+)?) C""")

        private fun firstLine(file: File): String? = runCatching {
            RandomAccessFile(file, "r").use { f ->
                val buf = ByteArray(minOf(1024L, f.length()).toInt())
                f.readFully(buf)
                String(buf, Charsets.ISO_8859_1).substringBefore('\n').trimEnd('\r')
            }
        }.getOrNull()

        /** Complete lines in the last [bytes] of [file]. */
        internal fun tailLines(file: File, bytes: Int): List<String> {
            if (!file.isFile) return emptyList()
            return RandomAccessFile(file, "r").use { f ->
                val from = maxOf(0L, f.length() - bytes)
                f.seek(from)
                val buf = ByteArray((f.length() - from).toInt())
                f.readFully(buf)
                val lines = String(buf, Charsets.ISO_8859_1).split('\n')
                val whole = if (from > 0) lines.drop(1) else lines
                whole.dropLast(1)
            }
        }

        private fun lastLine(file: File, bytes: Int, accept: (String) -> Boolean): String? =
            tailLines(file, bytes).lastOrNull(accept)?.trimEnd('\r')
    }
}

/** Builds the live file's text. Pure: everything it shows comes in through [Input]. */
internal object LiveText {
    class Input(
        val build: String,
        val device: String,
        val startMs: Long,
        val nowMs: Long,
        val ended: Boolean,
        val appId: String?,
        val sample: LiveSampler.Sample?,
        val settings: String?,
        val remoteConfig: String?,
        val history: List<String>,
        val extra: List<Pair<String, String>> = emptyList(),
    )

    private const val SETTINGS_CHARS = 2500
    private val GAME_MEMORY = setOf("game_mb", "game_swap_mb", "game_majflt_s", "swapin_mb_s")
    private const val EXTRA_CHARS = 1500

    fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    private fun num(value: Double?, format: String = "%.1f"): String = value?.let { String.format(Locale.US, format, it) } ?: "-"

    const val HISTORY_COLUMNS = "uptime fps_30s p95_ms headroom cap battery_c game_rss_mb game_swap_mb game_majflt_s swapin_mb_s mw_30s"

    private fun rate(value: String?): String = value?.toDoubleOrNull()?.let { num(it) } ?: "-"

    /** One compact history line, in [HISTORY_COLUMNS] order. */
    fun historyLine(startMs: Long, nowMs: Long, sample: LiveSampler.Sample?): String {
        val w = sample?.window
        return listOf("+" + duration(nowMs - startMs), num(w?.fps), num(w?.p95Ms), num(sample?.headroom, "%.2f"),
            sample?.cap?.toString() ?: "-", num(sample?.batteryC), sample?.gameRssMb ?: "-", sample?.gameSwapMb ?: "-",
            rate(sample?.gameMajfltS), rate(sample?.swapinMbS), num(sample?.windowMw, "%.0f")).joinToString(" ")
    }

    private fun frameRow(label: String, s: FrameSummary): String =
        String.format(Locale.US, "%-14s %6s %7s %7s %7s %7s %5d %6d %7d", label, num(s.fps), num(s.medianMs), num(s.p95Ms),
            num(s.p99Ms), num(s.low1Fps), s.over50, s.over100, s.frames)

    fun build(input: Input): String = buildString {
        val s = input.sample
        appendLine("# Kestrel live session status, rewritten every 30 s while the session runs")
        appendLine("build: ${input.build}")
        appendLine("device: ${input.device}")
        appendLine("session start: ${utc(input.startMs)}")
        appendLine("now: ${utc(input.nowMs)} (uptime ${duration(input.nowMs - input.startMs)})")
        appendLine("state: ${if (input.ended) "session ended" else "session running"}")
        val appId = s?.appId ?: input.appId
        val game = when {
            s == null -> "unknown"
            s.gameRunning -> "yes" + (s.gameSinceMs?.let { ", since ${utc(it)} (${duration(input.nowMs - it)})" } ?: "")
            else -> "no"
        }
        appendLine("game running: $game")
        appendLine("app id: ${appId ?: "-"}")
        s?.focus?.let { appendLine("focus: $it") }
        s?.lastExit?.let { appendLine("last game exit: $it") }

        appendLine()
        appendLine("## settings")
        val settings = input.settings?.lines()?.filter { it.isNotBlank() && !it.startsWith("#") }?.joinToString("\n")
        if (settings.isNullOrEmpty()) appendLine("settings.txt: none")
        else appendLine(if (settings.length > SETTINGS_CHARS) settings.take(SETTINGS_CHARS) + "\n<cut>" else settings)
        input.remoteConfig?.takeIf { settings?.contains(it) != true }?.let { appendLine("now applied: $it") }
        appendLine("game experiments: ${s?.experiments ?: "-"}")
        val pacing = when {
            s?.cap == null -> "no game frame cap"
            else -> "cap ${s.cap} fps" + (s.gameCap?.let { " of $it" } ?: "") + (s.capWhy?.let { " ($it)" } ?: "")
        }
        appendLine("pacing: $pacing")

        appendLine()
        appendLine("## frames")
        appendLine(String.format(Locale.US, "%-14s %6s %7s %7s %7s %7s %5s %6s %7s", "", "fps", "med_ms", "p95_ms",
            "p99_ms", "1%low", ">50ms", ">100ms", "frames"))
        if (s == null) appendLine("no frame data")
        else {
            appendLine(frameRow("last 30 s", s.window))
            appendLine(frameRow("session ${s.gameLabel}", s.game))
            appendLine(frameRow("session all", s.all))
            val pauses = s.all.pauses
            if (pauses > 0) appendLine("pauses (frames of 1 s or more, left out above): $pauses")
        }

        appendLine()
        appendLine("## memory")
        val memAge = s?.memoryAtMs?.let { " (${((input.nowMs - it) / 1000).coerceAtLeast(0)} s ago)" } ?: ""
        appendLine("game$memAge: rss ${s?.gameRssMb ?: "-"} MB, swap ${s?.gameSwapMb ?: "-"} MB, " +
            "major faults ${rate(s?.gameMajfltS)}/s, swap-in ${rate(s?.swapinMbS)} MB/s")
        val rest = s?.memory?.filterKeys { it !in GAME_MEMORY }.orEmpty()
        appendLine("memory.csv: ${if (rest.isEmpty()) "-" else rest.entries.joinToString(" ") { "${it.key}=${it.value}" }}")

        appendLine()
        appendLine("## device")
        appendLine("thermal: status ${s?.thermalStatus ?: "-"}, headroom ${num(s?.headroom, "%.2f")}, battery ${s?.batteryC?.let { num(it) + " C" } ?: "-"}")
        appendLine("power: ${s?.power?.liveLine() ?: "no power.csv yet"}")
        appendLine("clocks: ${s?.clocks ?: "-"}")
        if (!s?.processes.isNullOrEmpty()) {
            appendLine("top processes (cpu, newest processes.csv snapshot):")
            s!!.processes.forEach { appendLine("  $it") }
        }

        appendLine()
        appendLine("## events")
        if (s?.events.isNullOrEmpty()) appendLine("none yet") else s!!.events.forEach { appendLine(it) }

        for ((title, text) in input.extra) {
            appendLine()
            appendLine("## $title")
            appendLine(if (text.length > EXTRA_CHARS) text.take(EXTRA_CHARS) + "\n<cut>" else text)
        }

        appendLine()
        appendLine("## history ($HISTORY_COLUMNS)")
        input.history.forEach { appendLine(it) }
    }
}

/** The newest [cap] history lines. */
internal class LiveHistory(private val cap: Int = LiveStatus.HISTORY_LINES) {
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > cap) lines.removeFirst()
    }

    fun lines(): List<String> = lines.toList()
}

/** One session's live file: its sampler, history and the remote file's sha. Used only on the upload thread. */
internal class LiveSession(val dir: File, val path: String) {
    val sampler = LiveSampler(dir)
    val history = LiveHistory()
    var sha: String? = null

    fun render(build: String, device: String, nowMs: Long, ended: Boolean, appId: String?, remoteConfig: String?,
               extra: List<Pair<String, String>>): String {
        val sample = runCatching { sampler.sample(nowMs) }.getOrNull()
        val start = sampler.startMs
        history.add(LiveText.historyLine(start, nowMs, sample))
        val settings = runCatching { File(dir, RemoteConfig.SETTINGS_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
        return LiveText.build(LiveText.Input(build, device, start, nowMs, ended, appId, sample, settings, remoteConfig,
            history.lines(), extra))
    }
}
