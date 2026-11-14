package com.droiddeck.launcher.telemetry

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.RandomAccessFile

/**
 * Finds what a crash leaves behind for the upload bundle: guest stderr (session.log, and the x86_64
 * Steam's own log, which carries its games' output), the lines in it that report a dying process
 * (FEX's SIGILL report from libfaultreport, Wine, .NET, Godot), Proton logs the session asked for,
 * and the games' own logs inside their Proton prefixes.
 */
internal object CrashEvidence {
    const val LINES_FILE = "crash-lines.txt"
    private const val SCAN_BYTES = 8L shl 20
    private const val MAX_LINES_BYTES = 256 shl 10
    private const val MAX_LISTED = 64

    fun x64Log(context: Context): File = File(LinuxRuntime.rootDir(context), "root/.droiddeck-x64/steam-x64.log")

    /**
     * Godot keeps user:// in %APPDATA%\<name> (or %APPDATA%\Godot\app_userdata\<name>) and writes
     * logs\godot.log plus dated copies there; Unity writes %USERPROFILE%\AppData\LocalLow\<company>\<product>\Player.log.
     */
    private val GAME_LOG_PATTERNS = listOf(
        listOf("AppData", "Roaming", "*", "logs", "*.log"),
        listOf("AppData", "Roaming", "Godot", "app_userdata", "*", "logs", "*.log"),
        listOf("AppData", "LocalLow", "*", "*", "Player*.log"),
    )

    /** Game logs changed since [sinceMs] in any prefix under [compatdata], newest first, as (zip name, file). */
    fun gameLogs(compatdata: List<File>, sinceMs: Long): List<Pair<String, File>> {
        val found = ArrayList<Pair<String, File>>()
        for (root in compatdata) {
            val prefixes = root.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }?.take(256) ?: continue
            for (prefix in prefixes) {
                val user = File(prefix, "pfx/drive_c/users/steamuser")
                val base = runCatching { File(prefix, "pfx").canonicalPath + "/" }.getOrNull() ?: continue
                if (!user.isDirectory) continue
                for (pattern in GAME_LOG_PATTERNS) expand(user, pattern, base) { file ->
                    if (file.lastModified() >= sinceMs - 60_000) {
                        val rel = file.path.removePrefix(user.path + "/")
                        found += "game-logs/${prefix.name}/${safePath(rel)}" to file
                    }
                }
            }
        }
        return found.distinctBy { it.first }.sortedByDescending { it.second.lastModified() }
    }

    private fun expand(dir: File, parts: List<String>, base: String, hit: (File) -> Unit) {
        val part = parts.first()
        val last = parts.size == 1
        val names = if (part.contains('*')) {
            val (head, tail) = part.substringBefore('*') to part.substringAfter('*')
            dir.list()?.filter { it.startsWith(head) && it.endsWith(tail) && it.length >= head.length + tail.length }
                ?.sorted()?.take(MAX_LISTED) ?: return
        } else listOf(part)
        for (name in names) {
            val f = File(dir, name)
            val inside = runCatching { f.canonicalPath.startsWith(base) }.getOrDefault(false)
            if (!inside) continue
            if (last) { if (f.isFile) hit(f) } else if (f.isDirectory) expand(f, parts.drop(1), base, hit)
        }
    }

    /** Proton/DXVK/VKD3D logs the session's game logging wrote into the session folder (games/<launch>/ or the folder itself). */
    fun protonLogs(sessionDir: File): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        sessionDir.listFiles { f -> f.isFile && f.name.startsWith("steam-") && f.name.endsWith(".log") }?.forEach { out += "proton/${it.name}" to it }
        File(sessionDir, "games").listFiles { f -> f.isDirectory }?.take(MAX_LISTED)?.forEach { launch ->
            launch.listFiles { f -> f.isFile && (f.name.endsWith(".log") || f.name.endsWith(".txt")) }?.take(MAX_LISTED)?.forEach {
                out += "proton/${safePath(launch.name)}/${safePath(it.name)}" to it
            }
        }
        return out.sortedByDescending { it.second.lastModified() }
    }

    /** Lines in guest stderr that report a dying process or a game's start and end, oldest first, at most 256 KB of the newest. */
    fun crashLines(sources: List<Triple<String, File, Long>>): String {
        val lines = ArrayDeque<String>()
        var bytes = 0
        for ((label, file, from) in sources) {
            if (!file.isFile) continue
            val length = file.length()
            val base = if (from in 0..length) from else 0L
            val start = maxOf(base, length - SCAN_BYTES)
            runCatching {
                RandomAccessFile(file, "r").use { f ->
                    f.seek(start)
                    val reader = java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(f.channel), Charsets.ISO_8859_1).buffered()
                    var keepNext = false
                    var first = start > base
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (first) { first = false; continue }
                        val wanted = keepNext || GameExitWatch.isCrashLine(line) || line.startsWith("DROIDDECK_TIMELINE event=game_") ||
                            line.startsWith("DROIDDECK_DIAGNOSTICS event=game_") || line.startsWith("DROIDDECK_GAME_EXIT ")
                        keepNext = line.contains("droiddeck-fex: SIG")
                        if (!wanted) continue
                        val kept = "$label: ${line.take(1000)}"
                        lines.addLast(kept)
                        bytes += kept.length + 1
                        while (bytes > MAX_LINES_BYTES && lines.isNotEmpty()) bytes -= lines.removeFirst().length + 1
                    }
                }
            }
        }
        return if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
    }

    const val RESOURCE_LOADS_FILE = "resource-loads.txt"
    private const val LOAD_PREFIX = "Loading resource: "
    private const val MAX_LOAD_PATHS = 20_000

    /**
     * Godot's --verbose log (experiment godot-verbose) names each resource it loads. Lists the distinct paths in the
     * order first loaded, with how often, and a count per extension; null when the log names none.
     */
    fun resourceLoads(file: File): String? = runCatching {
        val counts = LinkedHashMap<String, Int>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val at = line.indexOf(LOAD_PREFIX)
                if (at < 0) continue
                val path = line.substring(at + LOAD_PREFIX.length).trim().take(300)
                if (path.isEmpty()) continue
                if (path in counts || counts.size < MAX_LOAD_PATHS) counts[path] = (counts[path] ?: 0) + 1
            }
        }
        if (counts.isEmpty()) return@runCatching null
        val byExt = counts.keys.groupingBy { it.substringAfterLast('/').substringAfterLast('.', "").lowercase() }.eachCount()
        buildString {
            append("# resources Godot loaded: ${counts.size} distinct, ${counts.values.sum()} loads\n")
            for ((ext, n) in byExt.entries.sortedByDescending { it.value }) append("# .${ext.ifEmpty { "(none)" }} $n\n")
            for ((path, n) in counts) append(n).append('\t').append(path).append('\n')
        }
    }.getOrNull()

    private fun safePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { it.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80) }
}
