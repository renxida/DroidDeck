package com.droiddeck.launcher.telemetry

import android.content.Context
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Uploads each session's [TelemetryBundle] to runs/{build}/{device}/{start}.zip in the user's own
 * GitHub repository through the contents API, when Telemetry upload is on and has a token: a
 * checkpoint every five minutes (overwriting the same file, so a crash still leaves data), the
 * whole bundle at the session's end, and whatever is still queued at the next app start. Every 30 s
 * it also rewrites a small text file beside the zip ([LiveStatus]) so a running session can be watched.
 * All work runs on one background-priority thread; the token only ever goes into the Authorization header.
 */
object TelemetryUploader {
    private const val TAG = "TelemetryUpload"
    private const val CHECKPOINT_MS = 5 * 60_000L
    private const val CHECKPOINT_LOG_BYTES = 512L shl 10
    private const val FINAL_LOG_BYTES = 8L shl 20
    private const val KEEP_ENTRIES = 8
    private const val API = "https://api.github.com"

    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, "telemetry-upload").apply { isDaemon = true }
    }
    @Volatile private var checkpoint: Future<*>? = null
    @Volatile private var live: Future<*>? = null
    private var liveSession: LiveSession? = null
    private const val LIVE_FIRST_MS = 10_000L
    private const val LIVE_MS = 30_000L
    @Volatile private var lastImmediateMs = 0L
    private const val IMMEDIATE_GAP_MS = 30_000L

    internal class Entry(val meta: File) {
        val json: JSONObject = runCatching { JSONObject(meta.readText()) }.getOrDefault(JSONObject())
        val zip: File get() = File(meta.path.removeSuffix(".json") + ".zip")
        val dir: File get() = File(json.optString("dir"))
        val path: String get() = json.optString("path")
        val x64LogFrom: Long get() = json.optLong("x64_log_from", -1L)
        var sha: String?
            get() = json.optString("sha").ifEmpty { null }
            set(value) { json.put("sha", value ?: "") }
        var complete: Boolean
            get() = json.optBoolean("complete")
            set(value) { json.put("complete", value) }
        fun save() = runCatching { meta.writeText(json.toString()) }
    }

    private fun outbox(context: Context) = File(TelemetryUploadSettings.dir(context), "outbox").apply { mkdirs() }

    internal fun remotePath(build: String, device: String, startMs: Long): String {
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "runs/${BuildInfo.safe(build)}/${BuildInfo.safe(device)}/${time.format(Date(startMs))}.zip"
    }

    private fun entryFor(context: Context, dir: File, create: Boolean): Entry? {
        val meta = File(outbox(context), BuildInfo.safe(dir.name) + ".json")
        if (meta.isFile) return Entry(meta)
        if (!create) return null
        meta.writeText(JSONObject()
            .put("dir", dir.path)
            .put("path", remotePath(BuildInfo.slug(), BuildInfo.deviceSlug(), System.currentTimeMillis()))
            .put("complete", false)
            .put("x64_log_from", CrashEvidence.x64Log(context).length())
            .toString())
        prune(context)
        return Entry(meta)
    }

    private fun prune(context: Context) {
        val metas = outbox(context).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        metas.drop(KEEP_ENTRIES).forEach { Entry(it).zip.delete(); it.delete() }
    }

    fun sessionStarted(context: Context, dir: File) {
        val app = context.applicationContext
        checkpoint?.cancel(false)
        checkpoint = null
        live?.cancel(false)
        live = null
        if (!TelemetryUploadSettings.active(app)) return
        executor.execute { runCatching { entryFor(app, dir, create = true) } }
        checkpoint = executor.scheduleWithFixedDelay({
            runCatching { entryFor(app, dir, create = true)?.let { upload(app, it, complete = false) } }
                .onFailure { Log.w(TAG, "checkpoint: ${it.javaClass.simpleName}") }
        }, CHECKPOINT_MS, CHECKPOINT_MS, TimeUnit.MILLISECONDS)
        live = executor.scheduleWithFixedDelay({
            runCatching { liveText(app, dir, ended = false)?.let { sendLive(app, it) } }
                .onFailure { Log.w(TAG, "live status: ${it.javaClass.simpleName}") }
        }, LIVE_FIRST_MS, LIVE_MS, TimeUnit.MILLISECONDS)
    }

    /** The live file's text for this tick; samples even when offline, so the history has no gaps. */
    private fun liveText(context: Context, dir: File, ended: Boolean): String? {
        val entry = entryFor(context, dir, create = true) ?: return null
        if (entry.path.isEmpty()) return null
        val session = liveSession?.takeIf { it.dir.path == dir.path }
            ?: LiveSession(dir, LiveStatus.remotePath(entry.path)).also { liveSession = it }
        return session.render(BuildInfo.slug(), BuildInfo.deviceSlug(), System.currentTimeMillis(), ended,
            com.droiddeck.launcher.session.SessionState.gameId, RemoteConfig.last?.line(), LiveStatus.extraSections())
    }

    private fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.activeNetwork != null
    }.getOrDefault(true)

    private fun sendLive(context: Context, text: String) {
        val session = liveSession ?: return
        val token = TelemetryUploadSettings.token(context) ?: return
        if (!online(context)) return
        val url = "$API/repos/${TelemetryUploadSettings.repo(context)}/contents/${session.path}"
        val body = text.toByteArray(Charsets.UTF_8)
        var code = putSmall(url, token, session.path, session.sha, body) { session.sha = it }
        if (code == 409 || code == 422) {
            session.sha = currentSha(url, token)
            code = putSmall(url, token, session.path, session.sha, body) { session.sha = it }
        }
        if (code != 200 && code != 201) Log.w(TAG, "live status ${session.path} answered HTTP $code")
    }

    /** Upload was switched on or given a token while a session runs: start its checkpoints and send one now. */
    fun settingsChanged(context: Context) {
        val app = context.applicationContext
        if (!TelemetryUploadSettings.active(app)) return
        flushPending(app)
        val dir = com.droiddeck.launcher.session.SessionPaths.current() ?: return
        if (checkpoint != null) return
        sessionStarted(app, dir)
        Log.i(TAG, "upload switched on mid-session: checkpoints started")
        executor.execute {
            runCatching { entryFor(app, dir, create = true)?.let { upload(app, it, complete = false, kind = "switched on mid-session") } }
                .onFailure { Log.w(TAG, "mid-session upload: ${it.javaClass.simpleName}") }
        }
    }

    /** Builds the final bundle before the session folder can be released (a logs-off folder is deleted), then uploads it. */
    fun sessionEnded(context: Context, dir: File) {
        val app = context.applicationContext
        checkpoint?.cancel(false)
        checkpoint = null
        live?.cancel(false)
        live = null
        if (!TelemetryUploadSettings.active(app)) return
        val built = executor.submit<Pair<Entry?, String?>> {
            val entry = entryFor(app, dir, create = true)?.takeIf { build(app, it, complete = true) }
            val text = if (liveSession?.dir?.path == dir.path) runCatching { liveText(app, dir, ended = true) }.getOrNull() else null
            entry to text
        }
        val (entry, text) = runCatching { built.get(2, TimeUnit.MINUTES) }.getOrNull() ?: return
        executor.execute {
            text?.let { runCatching { sendLive(app, it) } }
            liveSession = null
            entry?.let { runCatching { send(app, it) } }
        }
    }

    /** Uploads a checkpoint now (a game ended abnormally), at most once every 30 seconds. */
    fun uploadNow(context: Context, dir: File) {
        val app = context.applicationContext
        if (!TelemetryUploadSettings.active(app)) return
        val now = System.currentTimeMillis()
        if (now - lastImmediateMs < IMMEDIATE_GAP_MS) return
        lastImmediateMs = now
        executor.execute {
            runCatching { entryFor(app, dir, create = true)?.takeIf { !it.complete }?.let { upload(app, it, complete = false, kind = "abnormal game exit") } }
                .onFailure { Log.w(TAG, "immediate upload: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * At app start: records how the app's processes last ended ([AppExits]), then sends what an
     * earlier run left queued, rebuilding a crashed session's bundle from its folder first.
     */
    fun flushPending(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching { AppExits.record(app) }.onFailure { Log.w(TAG, "app exits: ${it.javaClass.simpleName}") }
            if (!TelemetryUploadSettings.active(app)) return@execute
            val metas = outbox(app).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return@execute
            val running = com.droiddeck.launcher.session.SessionPaths.current()?.path
            for (meta in metas) {
                val entry = Entry(meta)
                if (entry.dir.path == running) continue
                runCatching {
                    if (!entry.complete && entry.dir.isDirectory) upload(app, entry, complete = true)
                    else if (entry.zip.isFile) send(app, entry)
                    else { meta.delete() }
                }.onFailure { Log.w(TAG, "queued upload: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun build(context: Context, entry: Entry, complete: Boolean, kind: String? = null): Boolean {
        val note = "kind: ${kind ?: if (complete) "final" else "checkpoint"}\nbuilt_ms: ${System.currentTimeMillis()}\npath: ${entry.path}"
        val ok = TelemetryBundle.write(context, entry.dir, entry.zip, if (complete) FINAL_LOG_BYTES else CHECKPOINT_LOG_BYTES, note,
            TelemetryBundle.sources(context, entry.dir, entry.x64LogFrom))
        if (ok && complete) {
            entry.complete = true
            entry.save()
        }
        return ok
    }

    private fun upload(context: Context, entry: Entry, complete: Boolean, kind: String? = null) {
        if (build(context, entry, complete, kind)) send(context, entry)
    }

    private fun send(context: Context, entry: Entry) {
        val token = TelemetryUploadSettings.token(context) ?: return
        if (!entry.zip.isFile || entry.path.isEmpty()) return
        val url = "$API/repos/${TelemetryUploadSettings.repo(context)}/contents/${entry.path}"
        var code = put(url, token, entry)
        if (code == 409 || code == 422) {
            entry.sha = currentSha(url, token)
            code = put(url, token, entry)
        }
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        if (code == 200 || code == 201) {
            TelemetryUploadSettings.setLastResult(context, "$time uploaded ${entry.path}")
            if (entry.complete) {
                entry.zip.delete()
                entry.meta.delete()
            } else entry.save()
        } else {
            Log.w(TAG, "upload of ${entry.path} answered HTTP $code")
            TelemetryUploadSettings.setLastResult(context, "$time upload failed (HTTP $code); retried at the next start")
            entry.save()
        }
    }

    private fun connect(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "kestrel-telemetry")
        }

    /** PUT with the base64 body streamed from the zip; returns the HTTP code, or -1 when offline. */
    private fun put(url: String, token: String, entry: Entry): Int {
        val zip = entry.zip
        val head = requestHead(entry.path, entry.sha)
        val tail = "\"}"
        val b64 = (zip.length() + 2) / 3 * 4
        val conn = try { connect(url, token, "PUT") } catch (e: IOException) { return -1 }
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(head.length + b64 + tail.length)
            conn.outputStream.use { out ->
                out.write(head.toByteArray(Charsets.US_ASCII))
                Base64.getEncoder().wrap(Unclosed(out)).use { enc -> zip.inputStream().use { it.copyTo(enc) } }
                out.write(tail.toByteArray(Charsets.US_ASCII))
            }
            val code = conn.responseCode
            if (code == 200 || code == 201) {
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                runCatching { JSONObject(body).optJSONObject("content")?.optString("sha") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let { entry.sha = it }
            } else runCatching { conn.errorStream?.use { it.readBytes() } }
            code
        } catch (e: IOException) {
            -1
        } finally {
            conn.disconnect()
        }
    }

    /** PUT of a small in-memory file with short timeouts; [onSha] gets the new blob sha. Returns the HTTP code, or -1 when offline. */
    private fun putSmall(url: String, token: String, path: String, sha: String?, body: ByteArray, onSha: (String) -> Unit): Int {
        val json = (requestHead(path, sha) + Base64.getEncoder().encodeToString(body) + "\"}").toByteArray(Charsets.US_ASCII)
        val conn = try { connect(url, token, "PUT") } catch (e: IOException) { return -1 }
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(json.size)
            conn.outputStream.use { it.write(json) }
            val code = conn.responseCode
            if (code == 200 || code == 201) {
                val reply = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                runCatching { JSONObject(reply).optJSONObject("content")?.optString("sha") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let(onSha)
            } else runCatching { conn.errorStream?.use { it.readBytes() } }
            code
        } catch (e: IOException) {
            -1
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The JSON body up to the base64 content. No branch: the contents API then writes to the default
     * branch, and on an empty repository its first PUT creates that branch with the first commit.
     */
    internal fun requestHead(path: String, sha: String?): String =
        JSONObject().put("message", "telemetry $path").apply { sha?.let { put("sha", it) } }
            .toString().removeSuffix("}") + ",\"content\":\""

    private fun currentSha(url: String, token: String): String? {
        val conn = try { connect(url, token, "GET") } catch (e: IOException) { return null }
        return try {
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).optString("sha").ifEmpty { null }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private class Unclosed(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }
}
