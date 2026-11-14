package com.droiddeck.launcher.telemetry

import java.io.File
import java.io.RandomAccessFile

/**
 * Notices a game ending: its focused process (the pid gamescope reports) leaving /proc, or the
 * game-env wrapper's `DROIDDECK_TIMELINE event=game_exit` line where it waits for the game, or crash
 * output after a launch that never drew a window. Exit codes are only known from that line: the
 * plain launch path execs Proton, so nothing in the app or the guest is the game's parent.
 */
class GameExitWatch(private val alive: (Int) -> Boolean) {
    data class Exit(
        val appId: String?,
        val pid: Int,
        val engine: String,
        val runMs: Long?,
        val status: Int?,
        val signal: Int?,
        val how: String,
        val evidence: List<String>,
    ) {
        val abnormal: Boolean get() = (status != null && status != 0) || (signal ?: 0) != 0 || evidence.isNotEmpty()

        fun fields(): Map<String, Any?> = linkedMapOf(
            "app_id" to appId, "pid" to pid.takeIf { it > 0 }, "engine" to engine.ifEmpty { null },
            "run_s" to runMs?.let { it / 1000.0 }, "status" to status, "signal" to signal,
            "abnormal" to abnormal, "how" to how, "evidence" to evidence.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private class Game(val pid: Int, var engine: String, val sinceMs: Long) { var goneMs = 0L }

    private val games = LinkedHashMap<Int, Game>()
    private var execAppId: String? = null
    private var execMs = 0L
    private var lastGameMs = 0L
    private var evidence = ArrayList<String>()
    private var evidenceMs = 0L
    private var fatal = false
    private var wrapperExit: Exit? = null
    private var wrapperExitMs = 0L

    fun focus(pid: Int, engine: String, steam: Boolean, nowMs: Long) {
        if (steam || pid <= 0 || engine.isEmpty() || engine == "GAMESCOPE") return
        val game = games[pid]
        if (game != null) { game.engine = engine; return }
        if (games.size >= MAX_GAMES || !alive(pid)) return
        games[pid] = Game(pid, engine, nowMs)
    }

    /** One line of guest stderr (session.log or the x86_64 Steam's log). */
    fun line(text: String, nowMs: Long) {
        if (text.startsWith(EXEC)) {
            execAppId = field(text, "app_id")
            execMs = field(text, "wall_ms")?.toLongOrNull() ?: nowMs
            evidence = ArrayList()
            fatal = false
            wrapperExit = null
            return
        }
        if (text.startsWith(EXIT)) {
            wrapperExit = Exit(field(text, "app_id"), 0, "", field(text, "run_ms")?.toLongOrNull(),
                field(text, "status")?.toIntOrNull(), field(text, "signal")?.toIntOrNull(), "wrapper", emptyList())
            wrapperExitMs = nowMs
            return
        }
        if ((execMs > 0 || games.isNotEmpty()) && evidence.size < MAX_EVIDENCE && isCrashLine(text)) {
            evidence.add(text.trim().take(MAX_LINE))
            evidenceMs = nowMs
            if (FATAL_MARKERS.any { text.contains(it) }) fatal = true
        }
    }

    fun poll(nowMs: Long): List<Exit> {
        val out = ArrayList<Exit>()
        val it = games.values.iterator()
        while (it.hasNext()) {
            val game = it.next()
            if (game.goneMs == 0L) {
                if (!alive(game.pid)) game.goneMs = nowMs
                continue
            }
            if (nowMs - game.goneMs < SETTLE_MS && wrapperExit == null) continue
            it.remove()
            val wrapper = wrapperExit
            out.add(Exit(wrapper?.appId ?: execAppId, game.pid, game.engine,
                wrapper?.runMs ?: (game.goneMs - (if (execMs > 0) execMs else game.sinceMs)),
                wrapper?.status, wrapper?.signal, if (wrapper != null) "process gone, wrapper status" else "process gone", evidence))
            consumed(nowMs)
        }
        val wrapper = wrapperExit
        if (wrapper != null && nowMs - wrapperExitMs >= (if (games.isEmpty()) SETTLE_MS else WRAPPER_WAIT_MS)) {
            out.add(wrapper.copy(appId = wrapper.appId ?: execAppId, evidence = evidence))
            consumed(nowMs)
        } else if (wrapper == null && games.isEmpty() && execMs > 0 && fatal &&
            nowMs - evidenceMs >= CRASH_SETTLE_MS && nowMs - lastGameMs > CRASH_SETTLE_MS) {
            out.add(Exit(execAppId, 0, "", nowMs - execMs, null, null, "crash output", evidence))
            consumed(nowMs)
        }
        return out
    }

    private fun consumed(nowMs: Long) {
        execAppId = null
        execMs = 0L
        evidence = ArrayList()
        fatal = false
        wrapperExit = null
        lastGameMs = nowMs
    }

    /** Reads lines appended to [files] since the watch began; at most [MAX_READ] bytes per file per call. */
    class Tail(private val files: List<File>) {
        private val offsets = LongArray(files.size) { files[it].length() }
        private val partial = Array(files.size) { StringBuilder() }

        fun read(line: (String) -> Unit) {
            files.forEachIndexed { i, file ->
                val length = file.length()
                if (length < offsets[i]) { offsets[i] = 0; partial[i].setLength(0) }
                if (length == offsets[i]) return@forEachIndexed
                val from = maxOf(offsets[i], length - MAX_READ)
                if (from > offsets[i]) partial[i].setLength(0)
                val text = runCatching {
                    RandomAccessFile(file, "r").use { f ->
                        f.seek(from)
                        ByteArray((length - from).toInt()).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
                    }
                }.getOrNull() ?: return@forEachIndexed
                offsets[i] = length
                var start = 0
                while (true) {
                    val end = text.indexOf('\n', start)
                    if (end < 0) break
                    val buf = partial[i]
                    if (buf.isNotEmpty()) { buf.append(text, start, end); line(buf.toString()); buf.setLength(0) }
                    else line(text.substring(start, end))
                    start = end + 1
                }
                if (start < text.length && partial[i].length < MAX_PARTIAL) partial[i].append(text, start, minOf(text.length, start + MAX_PARTIAL))
            }
        }
    }

    companion object {
        private const val EXEC = "DROIDDECK_TIMELINE event=game_exec "
        private const val EXIT = "DROIDDECK_TIMELINE event=game_exit "
        private const val MAX_GAMES = 8
        private const val MAX_EVIDENCE = 12
        private const val MAX_LINE = 240
        private const val SETTLE_MS = 3_000L
        private const val CRASH_SETTLE_MS = 5_000L
        private const val WRAPPER_WAIT_MS = 10_000L
        const val MAX_READ = 1L shl 20
        private const val MAX_PARTIAL = 4096

        /** Lines guest programs print when a process dies: FEX's SIGILL report, Wine, .NET, Godot, glibc. */
        val CRASH_MARKERS = listOf(
            "droiddeck-fex: SIG", "Unhandled page fault", "Unhandled exception", "Unhandled stack overflow",
            "wine: Unhandled", "Program crashed", "CrashHandlerException", "Segmentation fault", "core dumped",
            "terminate called", "Fatal error.",
        )
        /** Of those, the ones printed only as the process dies; enough to call a launch with no window a crash. */
        private val FATAL_MARKERS = listOf("droiddeck-fex: SIG", "wine: Unhandled", "Program crashed", "Segmentation fault", "core dumped")

        fun isCrashLine(text: String): Boolean = CRASH_MARKERS.any { text.contains(it) }

        private const val EXPLORER_FAILED = "DROIDDECK_TIMELINE event=game_explorer_failed "
        private const val RELAUNCH = "DROIDDECK_TIMELINE event=game_relaunch "
        private const val CORES_RESET = "DROIDDECK_TIMELINE event=game_cores_reset "

        /**
         * The events.jsonl name and fields of droiddeck-game-env's lines that end no game: the explorer watchdog's
         * (Wine's desktop never started for a launch, `action=relaunch` on the first attempt, `none` after, and the
         * launch it then started once more) and the core watch's (the cpuset changed under the game and its
         * threads were pinned again, `action=repin`, or left where they were).
         */
        fun relaunchEvent(text: String): Pair<String, Map<String, String>>? {
            val name = when {
                text.startsWith(EXPLORER_FAILED) -> "game.explorer_failed"
                text.startsWith(RELAUNCH) -> "game.relaunch"
                text.startsWith(CORES_RESET) -> "game.cores_reset"
                else -> return null
            }
            return name to text.trim().split(' ').drop(2).mapNotNull {
                val at = it.indexOf('=')
                if (at > 0) it.substring(0, at) to it.substring(at + 1) else null
            }.toMap()
        }

        private fun field(text: String, name: String): String? =
            text.split(' ').firstOrNull { it.startsWith("$name=") }?.substring(name.length + 1)?.takeIf { it.isNotEmpty() }

        fun procAlive(pid: Int): Boolean = File("/proc/$pid").exists()
    }
}
