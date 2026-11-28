package com.droiddeck.launcher.ui

import android.content.Context
import com.droiddeck.launcher.BuildConfig
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The version line in Settings (About): which app build is installed, when it was made and which native engine it
 * carries, and, on the front screen while an update waits, which build that is. Pure formatting here so it can be
 * tested on the JVM.
 */
internal object VersionLine {
    private const val ENGINE_ASSET = "linuxfs/usr/local/share/droiddeck/native-engine.json"

    /** "0.7.1 · 7b9eef4 · Nov 17 14:05 · engine 105"; empty parts are left out. */
    fun installed(versionName: String, commit: String, commitTimeMs: Long, engine: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        listOf(versionName.trim(), commit.trim().take(7), time(commitTimeMs, zone), engine?.let { "engine $it" }.orEmpty())
            .filter { it.isNotEmpty() }.joinToString(" · ")

    /** "3c2d1e0 · Nov 18 09:12" for a waiting build; null when nothing names it. */
    fun build(commit: String, commitTimeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? =
        listOf(commit.trim().take(7), time(commitTimeMs, zone)).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }

    /** "native-engine-105" → "105"; anything else is shown as given; null when there is no tag. */
    fun engineNumber(tag: String?): String? {
        val t = tag?.trim().orEmpty()
        if (t.isEmpty()) return null
        return Regex("""^native-engine-(\d+)$""").find(t)?.groupValues?.get(1) ?: t
    }

    private fun time(ms: Long, zone: ZoneId): String =
        if (ms > 0) DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US).withZone(zone).format(Instant.ofEpochMilli(ms)) else ""

    @Volatile private var engineCache: String? = null
    @Volatile private var engineRead = false

    /** The engine release this APK embeds (CI writes its pin into the assets); null for a build without one. */
    fun embeddedEngine(context: Context): String? {
        if (!engineRead) {
            engineCache = runCatching {
                context.assets.open(ENGINE_ASSET).use { JSONObject(it.readBytes().decodeToString()).optString("tag") }
            }.getOrNull().let(::engineNumber)
            engineRead = true
        }
        return engineCache
    }

    fun forThisApp(context: Context): String =
        installed(BuildConfig.VERSION_NAME, BuildConfig.SOURCE_COMMIT, BuildConfig.COMMIT_TIME, embeddedEngine(context))
}
