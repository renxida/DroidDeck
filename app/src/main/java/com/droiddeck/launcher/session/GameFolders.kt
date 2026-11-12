package com.droiddeck.launcher.session

import android.content.Context
import java.io.File
import java.util.zip.CRC32

object GameFolders {
    const val GUEST_DIR = "/root/Games"

    class Root(val host: File, val guest: String)

    fun roots(context: Context): List<Root> {
        adoptLegacy(context)
        return SessionPrefs.gameFolderBinds(context).map { (host, guest) -> Root(File(host), guest) }
    }

    @Synchronized
    private fun adoptLegacy(context: Context) {
        val dirs = SessionPrefs.addedGamesDirs(context)
        if (dirs.isEmpty()) return
        val binds = SessionPrefs.gameFolderBinds(context)
        val roots = legacyRoots(binds.map { it.first } + dirs).filter { it.host.path in dirs }
        SessionPrefs.setGameFolderBinds(context, binds + roots.map { it.host.path to it.guest })
        SessionPrefs.setAddedGamesDirs(context, emptyList())
    }

    internal fun legacyRoots(paths: List<String>): List<Root> {
        val hosts = paths.distinct().map { File(it) }
        val names = hosts.groupingBy { it.name.lowercase() }.eachCount()
        return hosts.map { host ->
            val name = host.name.ifEmpty { "games" }
            val guestName = if ((names[name.lowercase()] ?: 0) > 1) name + "-" + "%08x".format(CRC32().apply { update(host.path.toByteArray()) }.value).take(4) else name
            Root(host, "$GUEST_DIR/$guestName")
        }
    }
}
