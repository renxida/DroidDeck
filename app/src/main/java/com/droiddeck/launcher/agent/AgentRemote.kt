package com.droiddeck.launcher.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.AutoTest
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SteamBranch
import com.droiddeck.launcher.telemetry.TelemetryUploadSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote control through the person's own telemetry repository: the agent bridge (the same calls
 * tools/droiddeckctl makes over adb) driven from files instead of a cable.
 *
 *     remote/inbox/<id>.json    {"payload": base64 JSON, "sig": base64 ECDSA P-256 / SHA-256 over the payload bytes}
 *                               payload: {"id": "<id>", "expires": <ms>, "method": "...", "arg": null, "request": {...}}
 *     remote/outbox/<id>.json   {"id", "method", "at", "result": {...}}           written here
 *     remote/outbox/<id>.jpg    for "screenshot"
 *
 * A command runs only when its signature checks out against [PUBLIC_KEY], the operator's key compiled
 * into the app (the private half never leaves the operator's side), its id matches its file name, it
 * has not expired and it has not run before. Write access to the repository alone can't run anything.
 * Shell commands are announced in the notification.
 *
 * Off unless the person turns on Setup > Diagnostics > Remote control. While on, a foreground service
 * with a notification polls the inbox (a conditional request; unchanged listings cost no rate limit),
 * every [ACTIVE_MS] for ten minutes after a command and every [IDLE_MS] otherwise. The bridge's own gate
 * still applies: guest shell, native and cdp commands need Setup > Diagnostics > Debugging tools.
 *
 * Methods beyond the bridge's: "start" ({"appId", "branch"} or {"steamUrl"}) opens a session the way
 * the library does; "screenshot" uploads a picture of the session; "autotest" runs the queued test.
 */
object AgentRemote {
    private const val TAG = "AgentRemote"
    private const val PREFS = "agent_remote"
    private const val API = "https://api.github.com"
    const val INBOX = "remote/inbox"
    const val OUTBOX = "remote/outbox"
    const val ACTIVE_MS = 5_000L
    const val IDLE_MS = 30_000L
    private const val ACTIVE_FOR_MS = 10 * 60_000L
    private const val MAX_DONE = 400
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val SHELL = setOf("guest", "native", "cdp", "override")
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEC+g8jJWsIWr01pqQffb0eJwcL6gcF5/LcWdbiUpO7Yv/MSwngzSiVT6lKqZPr+lGMHVLr97V41rVbm2X/GbhpQ=="

    /** Notification text updater, set by the running service. */
    @Volatile internal var announce: ((String) -> Unit)? = null

    /** The command inside a signed file, or why it was refused. */
    internal fun unseal(file: JSONObject, id: String, nowMs: Long, key: String = PUBLIC_KEY): Pair<JSONObject?, String?> {
        val payload = runCatching { Base64.decode(file.optString("payload"), Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null to "no payload"
        val sig = runCatching { Base64.decode(file.optString("sig"), Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null to "no signature"
        val ok = runCatching {
            val pub = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
            java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(payload); verify(sig) }
        }.getOrDefault(false)
        if (!ok) return null to "bad signature"
        val command = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return null to "payload is not JSON"
        if (command.optString("id") != id) return null to "id does not match the file name"
        if (command.optLong("expires", 0L) < nowMs) return null to "expired"
        return command to null
    }

    /** Set by the session screen while it shows, so "screenshot" can copy its pixels. */
    @Volatile var capture: ((File, (Boolean) -> Unit) -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        if (on) start(context) else context.stopService(Intent(context, AgentRemoteService::class.java))
    }

    fun start(context: Context) {
        if (!enabled(context) || !TelemetryUploadSettings.active(context)) return
        runCatching {
            val intent = Intent(context, AgentRemoteService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }.onFailure { Log.w(TAG, "remote control not started: ${it.message}") }
    }

    fun lastResult(context: Context): String? = prefs(context).getString("last", null)

    internal fun idFromName(name: String): String? =
        name.takeIf { it.endsWith(".json") }?.removeSuffix(".json")?.takeIf { ID.matches(it) }

    /** Command files to run, oldest name first, minus the ones already done. */
    internal fun pending(listing: JSONArray, done: Set<String>): List<Pair<String, String>> =
        (0 until listing.length()).mapNotNull { i ->
            val item = listing.optJSONObject(i) ?: return@mapNotNull null
            val id = idFromName(item.optString("name")) ?: return@mapNotNull null
            if (id in done || item.optString("type") != "file") null else id to item.optString("sha")
        }.sortedBy { it.first }

    // ---- one poll ----

    private var etag: String? = null

    /** Returns true when a command ran. */
    fun pollOnce(context: Context): Boolean {
        val token = TelemetryUploadSettings.token(context) ?: return false
        val repo = TelemetryUploadSettings.repo(context)
        val conn = connect("$API/repos/$repo/contents/$INBOX", token, "GET")
        etag?.let { conn.setRequestProperty("If-None-Match", it) }
        val listing = try {
            when (conn.responseCode) {
                304, 404 -> return false
                200 -> {
                    etag = conn.getHeaderField("ETag")
                    JSONArray(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                }
                else -> return false
            }
        } finally { conn.disconnect() }
        val done = doneSet(context)
        var ran = false
        for ((id, sha) in pending(listing, done)) {
            val file = runCatching { JSONObject(raw(repo, token, "$INBOX/$id.json") ?: return@runCatching null) }.getOrNull()
            markDone(context, id)
            val (command, refused) = if (file == null) null to "not JSON" else unseal(file, id, System.currentTimeMillis())
            val result = if (command == null) err("REFUSED", refused ?: "refused") else run(context, repo, token, id, command)
            val out = JSONObject().put("id", id).put("method", command?.optString("method"))
                .put("at", System.currentTimeMillis()).put("result", result)
            put(repo, token, "$OUTBOX/$id.json", out.toString(2).toByteArray(Charsets.UTF_8), "lanner remote: result $id")
            delete(repo, token, "$INBOX/$id.json", sha, "lanner remote: ran $id")
            prefs(context).edit().putString("last", "$id ${command?.optString("method")} ok=${result.optBoolean("ok")}").apply()
            ran = true
        }
        return ran
    }

    private fun run(context: Context, repo: String, token: String, id: String, command: JSONObject): JSONObject {
        val method = command.optString("method")
        val request = command.optJSONObject("request") ?: JSONObject()
        if (method in SHELL) announce?.invoke(context.getString(R.string.remote_notification_shell,
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())))
        return try {
            when (method) {
                "ping" -> JSONObject().put("ok", true).put("version", com.droiddeck.launcher.BuildConfig.VERSION_NAME)
                "start" -> startSession(context, request)
                "autotest" -> {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var message = ""
                    AutoTest.runNow(context) { message = it; latch.countDown() }
                    latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    JSONObject().put("ok", true).put("message", message)
                }
                "screenshot" -> screenshot(context, repo, token, id)
                else -> {
                    val extras = Bundle().apply { putString(AgentBridgeProvider.EXTRA_REQUEST,
                        Base64.encodeToString(request.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)) }
                    val json = context.contentResolver.call("${context.packageName}.agent", method,
                        command.optString("arg").takeIf { command.has("arg") && !command.isNull("arg") }, extras)
                        ?.getString(AgentBridgeProvider.RESULT_JSON)
                    if (json == null) err("NO_RESPONSE", "no response") else JSONObject(json)
                }
            }
        } catch (e: Exception) {
            err("COMMAND_FAILED", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startSession(context: Context, request: JSONObject): JSONObject {
        val appId = request.optInt("appId", 0)
        val url = request.optString("steamUrl").takeIf { it.startsWith("steam://") }
            ?: if (appId > 0) "steam://rungameid/$appId" else null
        if (appId > 0) SteamBranch.request(context, appId, request.optString("branch", SteamBranch.BETA))
        val intent = Intent(context, SessionActivity::class.java)
            .setAction(SessionService.ACTION_AGENT_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_STEAM)
        url?.let { intent.putExtra(SessionService.EXTRA_STEAM_URL, it) }
        context.startActivity(intent)
        return JSONObject().put("ok", true).put("steamUrl", url)
    }

    private fun screenshot(context: Context, repo: String, token: String, id: String): JSONObject {
        val take = capture ?: return err("NO_SESSION_SCREEN", "the session screen is not showing")
        val dir = File(context.cacheDir, "remote-shot").apply { mkdirs() }
        File(dir, com.droiddeck.launcher.session.SessionScreenshot.FILE).delete()
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        android.os.Handler(android.os.Looper.getMainLooper()).post { take(dir) { ok = it; latch.countDown() } }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        val file = File(dir, com.droiddeck.launcher.session.SessionScreenshot.FILE)
        if (!ok || !file.isFile) return err("SCREENSHOT_FAILED", "no picture")
        val code = put(repo, token, "$OUTBOX/$id.jpg", file.readBytes(), "lanner remote: screenshot $id")
        return JSONObject().put("ok", code in 200..201).put("path", "$OUTBOX/$id.jpg").put("bytes", file.length())
    }

    // ---- GitHub contents API ----

    private fun connect(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "kestrel-telemetry")
        }

    private fun raw(repo: String, token: String, path: String): String? {
        val conn = connect("$API/repos/$repo/contents/$path", token, "GET")
        conn.setRequestProperty("Accept", "application/vnd.github.raw+json")
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes() }.takeIf { it.size <= AgentAccess.MAX_REQUEST_CHARS }?.toString(Charsets.UTF_8)
        } finally { conn.disconnect() }
    }

    private fun put(repo: String, token: String, path: String, body: ByteArray, message: String): Int {
        val conn = connect("$API/repos/$repo/contents/$path", token, "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val payload = JSONObject().put("message", message).put("content", Base64.encodeToString(body, Base64.NO_WRAP))
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) { -1 } finally { conn.disconnect() }
    }

    private fun delete(repo: String, token: String, path: String, sha: String, message: String): Int {
        val conn = connect("$API/repos/$repo/contents/$path", token, "DELETE")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            conn.outputStream.use { it.write(JSONObject().put("message", message).put("sha", sha).toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) { -1 } finally { conn.disconnect() }
    }

    private fun err(code: String, message: String) =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message))

    private fun doneSet(context: Context): Set<String> =
        prefs(context).getString("done", "")!!.split(' ').filter { it.isNotEmpty() }.toSet()

    private fun markDone(context: Context, id: String) {
        val list = prefs(context).getString("done", "")!!.split(' ').filter { it.isNotEmpty() } + id
        prefs(context).edit().putString("done", list.takeLast(MAX_DONE).joinToString(" ")).commit()
    }

    // ---- loop ----

    internal fun loop(context: Context, running: () -> Boolean) {
        var activeUntil = 0L
        while (running()) {
            val ran = runCatching { pollOnce(context) }
                .onFailure { Log.i(TAG, "poll failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
            val now = SystemClock.elapsedRealtime()
            if (ran) activeUntil = now + ACTIVE_FOR_MS
            try { Thread.sleep(if (now < activeUntil) ACTIVE_MS else IDLE_MS) } catch (_: InterruptedException) { return }
        }
    }
}

class AgentRemoteService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.remote_channel), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, com.droiddeck.launcher.FrontEndActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        fun note(text: String?) = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(R.string.remote_notification))
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(open).setOngoing(true).build()
        startForeground(NOTIFICATION, note(null))
        AgentRemote.announce = { text -> runCatching { manager?.notify(NOTIFICATION, note(text)) } }
        if (!AgentRemote.enabled(this) || !TelemetryUploadSettings.active(this)) { stopSelf(); return START_NOT_STICKY }
        if (worker?.isAlive != true) {
            running = true
            worker = Thread({ AgentRemote.loop(applicationContext) { running } }, "agent-remote").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        AgentRemote.announce = null
        worker?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "remote"
        private const val NOTIFICATION = 0x4b7e59
    }
}
