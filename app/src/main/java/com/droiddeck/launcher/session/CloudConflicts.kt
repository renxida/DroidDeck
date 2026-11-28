package com.droiddeck.launcher.session

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Steam Cloud conflicts that nobody can answer. When a save changed on this phone and on another
 * computer since the last sync, Steam marks it conflicting and asks which copy to keep. Inside the
 * session that dialog never shows, so the conflict sits there for good: every later launch logs
 * "Download failed due to conflicts in build list", the phone keeps its own copy and nothing from
 * the other computer ever arrives (Slay the Spire 2's progress.save, Nov 13 to 15).
 *
 * Before Steam starts, this settles each such file for the cloud copy, since the phone is the
 * second device: the phone's copy moves to root/lanner-cloud-backups/<app>/<time>/ and its
 * remotecache.vdf entry is dropped, so Steam downloads the cloud copy on the next launch.
 * A conflict counts as still open only while the cache has neither copy's hash recorded as synced
 * from the cloud and the phone's file is still the one the log called local.
 */
object CloudConflicts {
    private const val TAG = "CloudConflicts"
    private const val LOG_TAIL = 2L * 1024 * 1024
    private val CONFLICT = Regex(
        """\[AppID (\d+)\] (\S.*?) (?:has local changes but mismatch - creating a conflict|already marked as conflicting)\. Local/Remote SHA: ([0-9a-f]{40}) / ([0-9a-f]{40})""")
    private val SYNCED = Regex("""\[AppID (\d+)\] Successfully synced to ChangeNumber""")

    data class Conflict(val app: String, val file: String, val localSha: String, val remoteSha: String)

    /** The conflicts the newest lines of [log] leave open: a later full sync of the app closes all of its earlier ones. */
    fun parse(log: String): List<Conflict> {
        val open = LinkedHashMap<Pair<String, String>, Conflict>()
        for (line in log.lineSequence()) {
            SYNCED.find(line)?.let { m -> open.keys.removeAll { it.first == m.groupValues[1] } }
            val m = CONFLICT.find(line) ?: continue
            val (app, file, local, remote) = m.destructured
            open[app to file] = Conflict(app, file, local, remote)
        }
        return open.values.toList()
    }

    /** The cache entry's "sha" for [file], or null when the cache has no entry for it. */
    fun cachedSha(cache: String, file: String): String? {
        val at = cache.indexOf("\n\t\"$file\"\n")
        if (at < 0) return null
        val end = cache.indexOf('}', at)
        if (end < 0) return null
        return Regex(""""sha"\s+"([0-9a-f]+)"""").find(cache.substring(at, end))?.groupValues?.get(1)
    }

    /** [cache] without the entry for [file]. */
    fun withoutEntry(cache: String, file: String): String =
        cache.replace(Regex("""\n\t"${Regex.escape(file)}"\n\t\{[^}]*\}"""), "")

    private fun sha1(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun tail(file: File): String = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - LOG_TAIL).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        ""
    }

    /** Games that keep their own copy of what they put in Steam Cloud, and where. */
    private fun mirrors(home: File, app: String, account: String): List<File> {
        val steamId = (76561197960265728L + (account.toLongOrNull() ?: return emptyList())).toString()
        return when (app) {
            "2868840" -> listOf(File(home, ".local/share/SlayTheSpire2/steam/$steamId"))
            else -> emptyList()
        }
    }

    /** Settles the open conflicts under the guest's Steam installs for the cloud copy. Returns the files it settled. */
    fun apply(root: File, now: Long = System.currentTimeMillis()): List<String> {
        val settled = ArrayList<String>()
        val homes = listOf(File(root, "root"), File(root, "root/.droiddeck-x64"))
        for (home in homes) {
            val steam = File(home, ".local/share/Steam")
            val log = File(steam, "logs/cloud_log.txt")
            if (!log.isFile) continue
            val conflicts = parse(tail(log))
            if (conflicts.isEmpty()) continue
            val users = File(steam, "userdata").listFiles()?.filter { it.isDirectory } ?: continue
            for ((app, group) in conflicts.groupBy { it.app }) {
                for (user in users) {
                    val dir = File(user, app)
                    val cacheFile = File(dir, "remotecache.vdf")
                    if (!cacheFile.isFile) continue
                    try {
                        var cache = cacheFile.readText()
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now))
                        val backup = File(root, "root/lanner-cloud-backups/$app/$stamp")
                        var changed = false
                        for (c in group) {
                            val cached = cachedSha(cache, c.file) ?: continue
                            if (cached == c.remoteSha) continue
                            val local = File(dir, "remote/${c.file}")
                            val localSha = if (local.isFile) sha1(local) else null
                            if (localSha != null && localSha != c.localSha && localSha != cached) continue
                            if (local.isFile) {
                                val to = File(backup, "steam/${c.file}")
                                to.parentFile?.mkdirs()
                                if (!local.renameTo(to)) { local.copyTo(to, overwrite = true); local.delete() }
                            }
                            for (mirror in mirrors(home, app, user.name)) {
                                for (name in listOf(c.file, "${c.file}.backup")) {
                                    val copy = File(mirror, name)
                                    if (!copy.isFile) continue
                                    val to = File(backup, "game/$name")
                                    to.parentFile?.mkdirs()
                                    if (!copy.renameTo(to)) { copy.copyTo(to, overwrite = true); copy.delete() }
                                }
                            }
                            cache = withoutEntry(cache, c.file)
                            changed = true
                            settled.add("$app:${c.file}")
                            Log.i(TAG, "took the cloud copy of $app ${c.file}; phone copy kept in $backup")
                        }
                        if (changed) {
                            File(dir, "remotecache.vdf.bak").writeText(cacheFile.readText())
                            val staged = File(dir, "remotecache.vdf.staged")
                            staged.writeText(cache)
                            if (!staged.renameTo(cacheFile)) { cacheFile.writeText(cache); staged.delete() }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "could not settle cloud conflicts for $app", e)
                    }
                }
            }
        }
        return settled
    }
}
