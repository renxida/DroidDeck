package com.droiddeck.launcher.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionPaths
import java.io.File

/**
 * At app start, turns Android's record of how the app's own processes ended (ApplicationExitInfo,
 * Android 11+) into `app.exit` events: in the session folder that was being written when the
 * process died, otherwise in the next session. An ANR's thread dump goes next to it as
 * app-exit-<time>-anr.txt.
 */
internal object AppExits {
    const val TRACE_PREFIX = "app-exit-"
    private const val TAG = "AppExits"
    private const val SEEN_FILE = "app-exits-seen"
    private const val FIRST_RUN_WINDOW_MS = 24 * 3600_000L
    private const val ACTIVE_SLACK_MS = 60_000L
    private const val MAX_TRACE = 256 shl 10

    /** Exits that mean something went wrong, as opposed to the user or the app closing it. */
    val ABNORMAL = setOf("crash", "crash_native", "anr", "low_memory", "signaled", "initialization_failure", "excessive_resource_usage")

    /** The memory balloon is meant to be killed by lmkd when memory runs short. */
    internal fun abnormal(reason: String, process: String?): Boolean =
        reason in ABNORMAL && process?.endsWith(com.droiddeck.launcher.session.MemoryBalloonService.PROCESS_SUFFIX) != true

    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 16) }.getOrNull() ?: return
        if (infos.isEmpty()) return
        val seenFile = File(TelemetryUploadSettings.dir(context), SEEN_FILE)
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrNull()
        val since = seen ?: (System.currentTimeMillis() - FIRST_RUN_WINDOW_MS)
        runCatching { seenFile.writeText(infos.maxOf { it.timestamp }.toString()) }
        val fresh = infos.filter { it.timestamp > since }.sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        val folders = runCatching { SessionPaths.sessionFolders(context) }.getOrDefault(emptyList())
        val current = SessionPaths.current()
        for (info in fresh) runCatching {
            val reason = reasonName(info.reason)
            val dir = folders.lastOrNull { it != current && wasActive(it, info.timestamp) }
            val fields = linkedMapOf<String, Any?>(
                "reason" to reason, "abnormal" to abnormal(reason, info.processName), "status" to info.status,
                "description" to info.description?.take(300), "process" to info.processName, "pid" to info.pid,
                "importance" to info.importance, "pss_kb" to info.pss, "rss_kb" to info.rss,
            )
            if (dir != null && info.reason == ApplicationExitInfo.REASON_ANR) {
                writeTrace(info, File(dir, "$TRACE_PREFIX${info.timestamp}-anr.txt"))?.let { fields["trace"] = it }
            }
            if (dir != null) SessionEvents.record("app.exit", fields, dir, info.timestamp)
            else Timeline.mark("app.exit", fields, info.timestamp)
        }.onFailure { Log.w(TAG, "exit record: ${it.javaClass.simpleName}") }
    }

    /** True when the session folder was still being written up to a minute before [at] and started before it. */
    internal fun wasActive(dir: File, at: Long): Boolean {
        val start = TelemetryBundle.sessionStartMs(dir)
        if (start > at) return false
        val newest = listOf("events.jsonl", "frames.csv", "memory.csv", "clocks.log", "session.log")
            .maxOfOrNull { File(dir, it).lastModified() } ?: 0L
        return newest >= at - ACTIVE_SLACK_MS
    }

    private fun writeTrace(info: ApplicationExitInfo, out: File): String? = runCatching {
        val bytes = info.traceInputStream?.use { input ->
            val buf = ByteArray(MAX_TRACE)
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            buf.copyOf(n)
        } ?: return null
        out.writeBytes(bytes)
        out.name
    }.getOrNull()

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason_$reason"
    }
}
