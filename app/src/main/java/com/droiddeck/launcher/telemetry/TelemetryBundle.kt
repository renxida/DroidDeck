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
 * The compact per-session zip the uploader sends: the app's own telemetry files as written, plus
 * the session's text reports and an excerpt of session.log through the share bundle's redactor.
 * Over [MAX_ZIP_BYTES] it drops session.log, then keeps only the tail of frames.csv.
 */
object TelemetryBundle {
    const val MAX_ZIP_BYTES = 20L shl 20
    private const val LOG_HEAD_BYTES = 256L shl 10
    private const val FRAMES_FALLBACK_BYTES = 16L shl 20
    private val RAW = listOf(BuildInfo.FILE, FrameLogComponent.FILE, "clocks.log", "memory.csv")
    private val SCRUBBED = listOf("events.jsonl", "device.txt", GraphicsReport.FILE)
    @Volatile private var learned = false

    /** [sessionLogBytes] caps the session.log excerpt (its first part and its end); 0 leaves it out. */
    fun write(context: Context, sessionDir: File, out: File, sessionLogBytes: Long, note: String): Boolean {
        if (!learned) learned = runCatching { LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context)) }.getOrDefault(false)
        val scratch = File(context.cacheDir, "telemetry-stage").apply { mkdirs() }
        try {
            for ((logBytes, framesBytes) in listOf(sessionLogBytes to Long.MAX_VALUE, 0L to Long.MAX_VALUE, 0L to FRAMES_FALLBACK_BYTES)) {
                if (!writeOnce(sessionDir, out, scratch, logBytes, framesBytes, note)) return false
                if (out.length() <= MAX_ZIP_BYTES) return true
            }
            out.delete()
            return false
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun writeOnce(dir: File, out: File, scratch: File, logBytes: Long, framesBytes: Long, note: String): Boolean = runCatching {
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
            if (logBytes > 0) scrubbed(zip, File(dir, "session.log"), "session.log", logBytes, scratch)
        }
        tmp.renameTo(out)
    }.getOrDefault(false)

    private fun scrubbed(zip: ZipOutputStream, file: File, name: String, limit: Long, scratch: File) {
        if (!file.isFile) return
        val excerpt = File(scratch, name)
        excerpt.outputStream().use { o ->
            val size = file.length()
            if (size <= limit) file.inputStream().use { it.copyTo(o) }
            else {
                val head = minOf(LOG_HEAD_BYTES, limit / 4)
                copyRange(file, 0, head, o, skipPartialLine = false, endAtLine = true)
                o.write("\n<cut: ${size - limit} bytes>\n".toByteArray())
                copyRange(file, size - (limit - head), limit - head, o, skipPartialLine = true)
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
