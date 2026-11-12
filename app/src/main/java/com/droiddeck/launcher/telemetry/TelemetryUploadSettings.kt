package com.droiddeck.launcher.telemetry

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Telemetry upload settings. The GitHub token lives in its own owner-only file under no_backup, away
 * from SharedPreferences and session folders, so no settings export, backup, log bundle or report
 * can pick it up; nothing reads it except [TelemetryUploader] for the Authorization header.
 */
object TelemetryUploadSettings {
    const val DEFAULT_REPO = "renxida/kestrel-telemetry"
    private val REPO = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
    private val TOKEN = Regex("[A-Za-z0-9_]{20,255}")
    private const val SETTINGS = "settings.properties"
    private const val TOKEN_FILE = "token"
    private val lock = Any()

    fun dir(context: Context): File = File(context.noBackupFilesDir, "telemetry").apply { mkdirs() }

    private fun load(context: Context): Properties = Properties().apply {
        runCatching { File(dir(context), SETTINGS).inputStream().use { load(it) } }
    }

    private fun store(context: Context, change: (Properties) -> Unit) = synchronized(lock) {
        val props = load(context)
        change(props)
        val target = File(dir(context), SETTINGS)
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        tmp.renameTo(target)
    }

    fun enabled(context: Context): Boolean = load(context).getProperty("enabled") == "true"

    fun setEnabled(context: Context, on: Boolean) = store(context) { it.setProperty("enabled", on.toString()) }

    fun repo(context: Context): String = load(context).getProperty("repo")?.takeIf { validRepo(it) } ?: DEFAULT_REPO

    fun validRepo(repo: String): Boolean = REPO.matches(repo) && repo.split('/').none { it == "." || it == ".." }

    fun setRepo(context: Context, repo: String): Boolean {
        val value = repo.trim()
        if (!validRepo(value)) return false
        store(context) { it.setProperty("repo", value) }
        return true
    }

    fun validToken(token: String): Boolean = TOKEN.matches(token.trim())

    fun hasToken(context: Context): Boolean = token(context) != null

    internal fun token(context: Context): String? =
        runCatching { File(dir(context), TOKEN_FILE).readText().trim() }.getOrNull()?.takeIf { validToken(it) }

    fun setToken(context: Context, token: String): Boolean {
        val value = token.trim()
        if (!validToken(value)) return false
        synchronized(lock) {
            val target = File(dir(context), TOKEN_FILE)
            val tmp = File(target.path + ".tmp")
            tmp.delete()
            tmp.createNewFile()
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
            tmp.writeText(value)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
        }
        return true
    }

    fun clearToken(context: Context) = synchronized(lock) { File(dir(context), TOKEN_FILE).delete() }

    fun active(context: Context): Boolean = enabled(context) && hasToken(context)

    fun lastResult(context: Context): String? = load(context).getProperty("last")

    internal fun setLastResult(context: Context, text: String) = runCatching { store(context) { it.setProperty("last", text) } }
}
