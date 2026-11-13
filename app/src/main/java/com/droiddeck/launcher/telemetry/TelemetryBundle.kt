package com.droiddeck.launcher.telemetry

import android.content.Context
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GraphicsReport
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The compact per-session zip the uploader sends: the app's own telemetry files as written, the
 * session's text reports, crash evidence ([CrashEvidence]: crash-lines.txt, crash.log, app exit
 * traces, the x86_64 Steam's output, Proton logs and the games' own logs, newest first within
 * [EVIDENCE_BYTES]) and an excerpt of session.log, all text through the share bundle's redactor.
 * Over [MAX_ZIP_BYTES] it drops session.log, then keeps only the tail of frames.csv.
 */
object TelemetryBundle {
    const val MAX_ZIP_BYTES = 20L shl 20
    private const val LOG_HEAD_BYTES = 256L shl 10
    private const val FRAMES_FALLBACK_BYTES = 16L shl 20
    private const val EVIDENCE_BYTES = 6L shl 20
    private const val EVIDENCE_FILE_BYTES = 1L shl 20
    private const val X64_LOG_BYTES = 2L shl 20
    private const val X64_LOG_UNKNOWN_START = 2L shl 20
    private const val STEAM_LOG_BYTES = 256L shl 10
    private val RAW = listOf(BuildInfo.FILE, FrameLogComponent.FILE, LatencyLogComponent.FILE, "clocks.log", "memory.csv", "processes.csv",
        RemoteConfig.SETTINGS_FILE, com.droiddeck.launcher.session.SessionScreenshot.FILE)
    /** The Steam client's own logs that time shader pre-caching, downloads and compatibility-tool picks. */
    internal val STEAM_LOGS = listOf("shader_log.txt", "content_log.txt", "compat_log.txt", "bootstrap_log.txt", "console_log.txt")
    private val SCRUBBED = listOf("events.jsonl", "device.txt", GraphicsReport.FILE)
    @Volatile private var learned = false

    /** Where the evidence comes from: [x64LogFrom] is the x86_64 Steam log's length at the session start (-1: unknown). */
    class Sources(val sinceMs: Long, val x64Log: File?, val x64LogFrom: Long, val compatdata: List<File>,
                  val steamLogDirs: List<Pair<String, File>> = emptyList())

    fun sources(context: Context, sessionDir: File, x64LogFrom: Long = -1): Sources = Sources(
        sessionStartMs(sessionDir), CrashEvidence.x64Log(context), x64LogFrom,
        listOf("arm64", "x86_64").flatMap { arch ->
            runCatching { com.droiddeck.launcher.frontend.Library.compatdataDirs(context, com.droiddeck.launcher.frontend.Library.steamLibraries(context, arch).map { it.first }) }
                .getOrDefault(emptyList())
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) },
        listOf(
            "arm64" to File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs"),
            "x86_64" to File(LinuxRuntime.rootDir(context), "root/.droiddeck-x64/.local/share/Steam/logs"),
        ),
    )

    /** Steam client logs written since [sinceMs], as (zip name, file). */
    internal fun steamLogs(dirs: List<Pair<String, File>>, sinceMs: Long): List<Pair<String, File>> =
        dirs.flatMap { (arch, dir) ->
            STEAM_LOGS.map { File(dir, it) }.filter { it.isFile && it.length() > 0 && it.lastModified() >= sinceMs - 60_000 }
                .map { "steam-logs/$arch/${it.name}" to it }
        }

    internal fun sessionStartMs(dir: File): Long = runCatching {
        File(dir, "events.jsonl").bufferedReader().use { org.json.JSONObject(it.readLine()).getLong("t") }
    }.getOrElse { dir.lastModified() }

    /** [sessionLogBytes] caps the session.log excerpt (its first part and its end); 0 leaves it out. */
    fun write(context: Context, sessionDir: File, out: File, sessionLogBytes: Long, note: String,
              sources: Sources = sources(context, sessionDir)): Boolean {
        if (!learned) learned = runCatching { LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context)) }.getOrDefault(false)
        val scratch = File(context.cacheDir, "telemetry-stage").apply { mkdirs() }
        try {
            for ((logBytes, framesBytes) in listOf(sessionLogBytes to Long.MAX_VALUE, 0L to Long.MAX_VALUE, 0L to FRAMES_FALLBACK_BYTES)) {
                if (!writeOnce(sessionDir, out, scratch, logBytes, framesBytes, note, sources)) return false
                if (out.length() <= MAX_ZIP_BYTES) return true
            }
            out.delete()
            return false
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun writeOnce(dir: File, out: File, scratch: File, logBytes: Long, framesBytes: Long, note: String,
                          sources: Sources): Boolean = runCatching {
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("upload.txt"))
            zip.write("session: ${dir.name}\n$note\n".toByteArray())
            zip.closeEntry()
            if (!File(dir, BuildInfo.FILE).isFile) {
                zip.putNextEntry(ZipEntry(BuildInfo.FILE))
                zip.write("build_label: ${com.droiddeck.launcher.BuildConfig.BUILD_LABEL}\n".toByteArray())
                zip.closeEntry()
            }
            for (name in RAW) {
                val file = File(dir, name).takeIf { it.isFile } ?: continue
                zip.putNextEntry(ZipEntry(name))
                val limit = if (name == FrameLogComponent.FILE) framesBytes else Long.MAX_VALUE
                if (file.length() <= limit) file.inputStream().use { it.copyTo(zip) }
                else {
                    zip.write("# trimmed to the last $limit bytes for upload\n".toByteArray())
                    copyRange(file, file.length() - limit, limit, zip, skipPartialLine = true)
                }
                zip.closeEntry()
            }
            for (name in SCRUBBED) scrubbed(zip, File(dir, name), name, Long.MAX_VALUE, scratch)
            runCatching { evidence(zip, dir, scratch, sources) }
            if (logBytes > 0) scrubbed(zip, File(dir, "session.log"), "session.log", logBytes, scratch)
        }
        tmp.renameTo(out)
    }.getOrDefault(false)

    private fun evidence(zip: ZipOutputStream, dir: File, scratch: File, sources: Sources) {
        val seen = HashSet<String>()
        fun add(file: File, name: String, limit: Long, from: Long = 0L) {
            if (seen.add(name)) runCatching { scrubbed(zip, file, name, limit, scratch, from) }
        }
        val x64 = sources.x64Log?.takeIf { it.isFile && it.length() > 0 }
        val x64From = when {
            x64 == null -> -1L
            sources.x64LogFrom in 0..x64.length() -> sources.x64LogFrom
            sources.x64LogFrom > x64.length() -> 0L
            x64.lastModified() >= sources.sinceMs -> maxOf(0L, x64.length() - X64_LOG_UNKNOWN_START)
            else -> -1L
        }
        val lines = CrashEvidence.crashLines(listOfNotNull(
            Triple("session.log", File(dir, "session.log"), 0L),
            x64?.takeIf { x64From >= 0 }?.let { Triple("steam-x64.log", it, x64From) },
        ))
        if (lines.isNotEmpty()) {
            val f = File(scratch, CrashEvidence.LINES_FILE).apply { writeText(lines, Charsets.ISO_8859_1) }
            add(f, CrashEvidence.LINES_FILE, Long.MAX_VALUE)
            f.delete()
        }
        add(File(dir, "crash.log"), "crash.log", EVIDENCE_FILE_BYTES)
        dir.listFiles { f -> f.isFile && f.name.startsWith(AppExits.TRACE_PREFIX) }?.sortedByDescending { it.lastModified() }?.take(4)
            ?.forEach { add(it, it.name, 256L shl 10) }
        if (x64 != null && x64From >= 0 && x64.length() > x64From) add(x64, "steam-x64.log", X64_LOG_BYTES, x64From)
        for ((name, file) in steamLogs(sources.steamLogDirs, sources.sinceMs)) add(file, name, STEAM_LOG_BYTES)
        var budget = EVIDENCE_BYTES
        val logs = CrashEvidence.protonLogs(dir) + runCatching { CrashEvidence.gameLogs(sources.compatdata, sources.sinceMs) }.getOrDefault(emptyList())
        for ((name, file) in logs.sortedByDescending { it.second.lastModified() }) {
            if (budget <= 0) break
            val take = minOf(EVIDENCE_FILE_BYTES, budget, file.length())
            if (take <= 0) continue
            if (name in seen) continue
            add(file, name, take)
            budget -= take
        }
    }

    private fun scrubbed(zip: ZipOutputStream, file: File, name: String, limit: Long, scratch: File, from: Long = 0L) {
        if (!file.isFile) return
        val excerpt = File(scratch, "excerpt")
        excerpt.outputStream().use { o ->
            val size = file.length() - from
            if (size <= 0) return
            if (size <= limit && from == 0L) file.inputStream().use { it.copyTo(o) }
            else if (size <= limit) copyRange(file, from, size, o, skipPartialLine = false)
            else {
                val head = minOf(LOG_HEAD_BYTES, limit / 4)
                copyRange(file, from, head, o, skipPartialLine = false, endAtLine = true)
                o.write("\n<cut: ${size - limit} bytes>\n".toByteArray())
                copyRange(file, file.length() - (limit - head), limit - head, o, skipPartialLine = true)
            }
        }
        zip.putNextEntry(ZipEntry(name))
        val writer = zip.bufferedWriter()
        LogRedactor.scrubForShare(excerpt, writer)
        writer.flush()
        zip.closeEntry()
        excerpt.delete()
    }

    /** Copies [count] bytes from [start], starting after the first newline if [skipPartialLine], ending at the last one if [endAtLine]. */
    internal fun copyRange(file: File, start: Long, count: Long, out: java.io.OutputStream,
                           skipPartialLine: Boolean, endAtLine: Boolean = false) {
        RandomAccessFile(file, "r").use { f ->
            f.seek(start.coerceAtLeast(0))
            val bytes = ByteArray(minOf(count, f.length() - f.filePointer).coerceAtLeast(0).toInt())
            f.readFully(bytes)
            var from = 0
            if (skipPartialLine && start > 0) from = (bytes.indexOf('\n'.code.toByte()) + 1).coerceAtLeast(0)
            var to = bytes.size
            if (endAtLine) to = (bytes.lastIndexOf('\n'.code.toByte()) + 1).takeIf { it > from } ?: from
            out.write(bytes, from, to - from)
        }
    }
}
