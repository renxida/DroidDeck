package com.droiddeck.launcher.telemetry

import android.os.SystemClock
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionPaths
import java.io.File

/**
 * Timed marks for events.jsonl, on the same wall clock (`t`, epoch ms) as SessionEvents. A mark made
 * while a session folder is open goes straight in; one made before (process start, the front end's
 * first frame, a setup download) waits here and is written into the next session's events.jsonl
 * with its original time.
 */
object Timeline {
    private class Mark(val event: String, val at: Long, val fields: Map<String, Any?>)

    private const val MAX_PENDING = 64
    private val pending = ArrayList<Mark>()
    @Volatile private var frontendDrawn = false

    @JvmStatic
    @JvmOverloads
    fun mark(event: String, fields: Map<String, Any?> = emptyMap(), at: Long = System.currentTimeMillis()) {
        val dir = SessionPaths.current()
        if (dir != null) {
            SessionEvents.record(event, fields, dir, at)
            return
        }
        synchronized(pending) { if (pending.size < MAX_PENDING) pending += Mark(event, at, fields) }
    }

    fun processStarted() {
        val now = System.currentTimeMillis()
        val sinceStart = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        mark("app.process_start", mapOf("pid" to android.os.Process.myPid()), now - sinceStart.coerceAtLeast(0))
        mark("app.created", at = now)
    }

    fun frontendDrawn() {
        if (frontendDrawn) return
        frontendDrawn = true
        mark("frontend.first_frame")
    }

    internal fun flushInto(dir: File) {
        val marks = synchronized(pending) { pending.toList().also { pending.clear() } }
        marks.forEach { SessionEvents.record(it.event, it.fields + ("before_session" to true), dir, it.at) }
    }
}
