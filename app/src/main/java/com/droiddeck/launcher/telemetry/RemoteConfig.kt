package com.droiddeck.launcher.telemetry

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.Experiments
import com.droiddeck.launcher.session.SettingsReset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Settings the telemetry repo's owner manages from config/kestrel.json in that repo, read with the
 * upload token when a session starts, so test settings change without a reinstall:
 *
 *     {"schema": 1,
 *      "experiments": {"on": [ids]} or {"rotate": [[ids], ...], "always": [ids]},
 *      "session": {"<session setting key>": value or null, ...}}
 *
 * "rotate" gives each session the next row; the rotation moves on only after a session in which a game
 * ran two minutes or ended abnormally, so a session without a game does not use up a row. "session"
 * writes only keys [SettingsReset] counts as session settings (never telemetry's own), each with the
 * type it already has (a key with no stored value takes only true or false); null removes the key, back to
 * its default. Nothing here runs code: the config only
 * picks values the app already offers. The last good file is kept, so a session offline uses it.
 */
object RemoteConfig {
    private const val TAG = "RemoteConfig"
    const val PATH = "config/kestrel.json"
    const val SETTINGS_FILE = "settings.txt"
    private const val CACHE = "remote-config.json"
    private const val TIMEOUT_MS = 4_000
    private const val PLAYED_MS = 120_000L
    private val NEVER = setOf("telemetryAuto", "telemetryOff")

    class Applied(val source: String, val row: Int?, val rows: Int, val experiments: Set<String>?,
                  val session: Map<String, Any?>, val skipped: List<String>) {
        fun line(): String = buildString {
            append("remote config: ").append(source)
            if (row != null) append(", rotation row ").append(row + 1).append(" of ").append(rows)
            experiments?.let { append(", experiments ").append(Experiments.line(it)) }
            if (session.isNotEmpty()) append(", settings ").append(session.entries.joinToString(" ") { "${it.key}=${it.value}" })
            if (skipped.isNotEmpty()) append(", skipped ").append(skipped.joinToString(" "))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("remote_config", Context.MODE_PRIVATE)

    @Volatile var last: Applied? = null
        private set
    @Volatile private var lastRefresh = 0L

    /**
     * Fetches the config in the background and applies it, so the launcher already shows the managed
     * settings when the person taps play. Called when the app comes to the front; at most once a minute.
     * During a session it only refreshes the kept copy (apply = false), so nothing changes under a game.
     */
    fun refreshAsync(context: Context, apply: Boolean = true) {
        val app = context.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRefresh != 0L && now - lastRefresh < 60_000) return
        lastRefresh = now
        Thread({ if (fetch(app) != null && apply) applyCached(app) }, "remote-config").start()
    }

    /**
     * Applies the last fetched copy without touching the network, so a session never waits on GitHub.
     * Applying twice is harmless: the rotation moves on only after a played session.
     */
    fun applyCached(context: Context): Applied? {
        val app = context.applicationContext
        val text = cached(app) ?: return null
        return runCatching { apply(app, JSONObject(text), "fetched ${fetchedAt(app)}") }
            .onFailure { Log.w(TAG, "config not applied: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()?.also { last = it }
    }

    /** Fetches now (a background thread only), falling back to the kept copy; for unattended test runs. */
    fun fetchNow(context: Context): JSONObject? {
        val app = context.applicationContext
        val text = fetch(app) ?: cached(app) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun fetchedAt(context: Context): String = runCatching {
        val t = File(TelemetryUploadSettings.dir(context), CACHE).lastModified()
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t))
    }.getOrDefault("?")

    private fun cached(context: Context): String? =
        runCatching { File(TelemetryUploadSettings.dir(context), CACHE).readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun fetch(context: Context): String? {
        if (!TelemetryUploadSettings.active(context)) return null
        val token = TelemetryUploadSettings.token(context) ?: return null
        val repo = TelemetryUploadSettings.repo(context)
        return runCatching {
            val conn = (URL("https://api.github.com/repos/$repo/contents/$PATH").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github.raw+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "kestrel-telemetry")
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    Log.i(TAG, "no config fetched (HTTP $code)")
                    return null
                }
                val body = conn.inputStream.use { it.readBytes() }
                if (body.size > 64 * 1024) return null
                String(body, Charsets.UTF_8).also { text ->
                    JSONObject(text)
                    val target = File(TelemetryUploadSettings.dir(context), CACHE)
                    val tmp = File(target.path + ".tmp")
                    tmp.writeText(text)
                    tmp.renameTo(target)
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.i(TAG, "no config fetched: ${it.javaClass.simpleName}") }.getOrNull()
    }

    internal fun apply(context: Context, config: JSONObject, source: String): Applied {
        require(config.optInt("schema", 1) == 1) { "unknown schema" }
        val skipped = ArrayList<String>()
        var row: Int? = null
        var rows = 0
        var experiments: Set<String>? = null
        config.optJSONObject("experiments")?.let { exp ->
            val rotate = exp.optJSONArray("rotate")
            val always = ids(exp.optJSONArray("always"), skipped)
            if (rotate != null && rotate.length() > 0) {
                rows = rotate.length()
                val p = prefs(context)
                var next = p.getInt("next", 0)
                if (p.getBoolean("played", false)) next += 1
                row = Math.floorMod(next, rows)
                p.edit().putInt("next", next).putBoolean("played", false).apply()
                experiments = resolve(always + ids(rotate.optJSONArray(row!!), skipped))
            } else if (exp.has("on")) {
                experiments = resolve(always + ids(exp.optJSONArray("on"), skipped))
            }
        }
        experiments?.let { Experiments.set(context, it) }
        val written = LinkedHashMap<String, Any?>()
        config.optJSONObject("session")?.let { session ->
            val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
            val edit = prefs.edit()
            for (key in session.keys()) {
                if (key in NEVER || !SettingsReset.isSessionSetting(key)) { skipped += key; continue }
                val value = session.opt(key)
                if (value == null || value == JSONObject.NULL) { edit.remove(key); written[key] = null; continue }
                val ok = when (val old = prefs.all[key]) {
                    is Boolean -> (value as? Boolean)?.let { edit.putBoolean(key, it) } != null
                    is Int -> (value as? Number)?.let { edit.putInt(key, it.toInt()) } != null
                    is Long -> (value as? Number)?.let { edit.putLong(key, it.toLong()) } != null
                    is Float -> (value as? Number)?.let { edit.putFloat(key, it.toFloat()) } != null
                    is String -> (value as? String)?.let { edit.putString(key, it) } != null
                    // With no stored value to give the type, only a switch is safe to write: an Int or a
                    // String under a key the app reads with another type would throw where it is read.
                    null -> (value as? Boolean)?.let { edit.putBoolean(key, it) } != null
                    else -> old.let { false }
                }
                if (ok) written[key] = value else skipped += key
            }
            edit.commit()
        }
        return Applied(source, row, rows, experiments, written, skipped).also { Log.i(TAG, it.line()) }
    }

    private fun ids(array: JSONArray?, skipped: MutableList<String>): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            array!!.optString(i).takeIf { it in Experiments.ids } ?: run { skipped += array.optString(i); null }
        }

    /** The groups' one-of rule applied in order, so the config cannot turn on two of a group. */
    private fun resolve(ids: List<String>): Set<String> = ids.fold(emptySet()) { on, id -> Experiments.with(on, id, true) }

    /** After a game exits: a run of two minutes or an abnormal end uses up this session's rotation row. */
    fun gameExited(context: Context, exit: GameExitWatch.Exit) {
        if (exit.abnormal || (exit.runMs ?: 0L) >= PLAYED_MS) markPlayed(context)
    }

    /** A game has run two minutes in this session: the next session takes the next rotation row. */
    fun markPlayed(context: Context) {
        prefs(context).edit().putBoolean("played", true).apply()
    }

    /** Every session setting and switch the session started with, for the telemetry bundle. */
    fun writeSnapshot(context: Context, sessionDir: File, applied: Applied?, experiments: Set<String>) {
        runCatching {
            val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE).all
            val text = buildString {
                appendLine("# settings this session started with")
                appendLine(applied?.line() ?: "remote config: none")
                appendLine("experiments: " + Experiments.line(experiments))
                appendLine(com.droiddeck.launcher.session.AutoTest.summary(context))
                prefs.keys.filter { SettingsReset.isSessionSetting(it) }.sorted().forEach { appendLine("$it=${prefs[it]}") }
            }
            File(sessionDir, SETTINGS_FILE).writeText(text)
        }.onFailure { Log.w(TAG, "settings snapshot not written: ${it.javaClass.simpleName}") }
    }
}
