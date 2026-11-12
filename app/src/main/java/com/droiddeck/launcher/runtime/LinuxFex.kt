package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.core.FileUtils
import org.json.JSONObject
import java.io.File

object LinuxFex {
    const val AUTO = "auto"
    const val ON = "on"
    const val OFF = "off"
    val MODES = listOf(AUTO, ON, OFF)
    private const val MODE_FILE = "fex"
    private const val STATUS = "root/.local/share/droiddeck-fex/status.json"

    fun ready(context: Context): Boolean =
        FileUtils.readString(File(LinuxRuntime.rootDir(context), STATUS))
            ?.let { runCatching { JSONObject(it).optBoolean("ready") }.getOrNull() } == true

    fun mode(dir: File): String = FileUtils.readString(File(dir, MODE_FILE))?.trim()?.takeIf { it in MODES } ?: AUTO

    fun setMode(dir: File, mode: String): Boolean {
        val file = File(dir, MODE_FILE)
        return when (mode) {
            AUTO -> !file.exists() || file.delete()
            ON, OFF -> FileUtils.writeString(file, mode)
            else -> false
        }
    }
}
