package com.droiddeck.launcher.session

import java.io.File
import java.io.RandomAccessFile

enum class SessionLaunchFailure(val detail: String) {
    STEAM_ILLEGAL_INSTRUCTION("the Steam client stopped on an illegal instruction"),
    VULKAN_DEVICE_MISSING("gamescope found no usable Vulkan device and never started the session"),
    STEAM_CLIENT_MISSING("Proton needs the Steam client's steamclient.so, which is not installed yet");

    /** The session never got going, so its exit is a failed start whatever its status. */
    val failedStart get() = this != STEAM_ILLEGAL_INSTRUCTION

    companion object {
        const val COMPOSITOR_UP = "== gamescope: the session runs inside the compositor"
        const val STEAM_CLIENT_MISSING_MARKER = "droiddeck-proton-run: the Steam client is not installed yet"
        private val ANSI = Regex("\u001b\\[[0-?]*[ -/]*[@-~]")
        private val STEAM_EXIT = Regex("== steam exited rc=(\\d+)")
        private const val TAIL_BYTES = 512L * 1024

        /** The same verdict from the end of a session log on disk. */
        fun find(log: File?): SessionLaunchFailure? {
            if (log == null || !log.isFile) return null
            return runCatching {
                RandomAccessFile(log, "r").use { f ->
                    val start = maxOf(0L, f.length() - TAIL_BYTES)
                    f.seek(start)
                    val bytes = ByteArray((f.length() - start).toInt())
                    f.readFully(bytes)
                    find(String(bytes, Charsets.ISO_8859_1).lineSequence())
                }
            }.getOrNull()
        }

        fun find(lines: Sequence<String>): SessionLaunchFailure? {
            var steamStarted = false
            var illegalInstruction = false
            var missingDevice = false
            var compositorUp = false
            var steamClientMissing = false
            lines.forEach { raw ->
                val line = ANSI.replace(raw, "").trim()
                when {
                    line.startsWith("== droiddeck-session ") -> {
                        steamStarted = false
                        illegalInstruction = false
                        missingDevice = false
                        compositorUp = false
                        steamClientMissing = false
                    }
                    line.startsWith(COMPOSITOR_UP) -> {
                        compositorUp = true
                        missingDevice = false
                    }
                    line.startsWith("== STEP ") && line.contains("starting the Steam client") -> {
                        steamStarted = true
                        illegalInstruction = false
                    }
                    STEAM_EXIT.matches(line) && steamStarted ->
                        illegalInstruction = STEAM_EXIT.matchEntire(line)?.groupValues?.get(1) == "132"
                    line.startsWith("[gamescope]") && line.contains("vulkan:") && !compositorUp -> when {
                        line.contains("failed to find physical device") -> missingDevice = true
                        line.contains("Missing required extension") -> missingDevice = true
                        line.contains("selecting physical device") -> missingDevice = false
                    }
                    line.startsWith(STEAM_CLIENT_MISSING_MARKER) -> steamClientMissing = true
                }
            }
            return when {
                illegalInstruction -> STEAM_ILLEGAL_INSTRUCTION
                missingDevice -> VULKAN_DEVICE_MISSING
                steamClientMissing -> STEAM_CLIENT_MISSING
                else -> null
            }
        }
    }
}
