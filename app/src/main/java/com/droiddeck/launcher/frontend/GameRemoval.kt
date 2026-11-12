package com.droiddeck.launcher.frontend

import java.io.File
import java.nio.file.Files

object GameRemoval {
    class SteamFiles(val dir: File, val manifest: File, val partial: File?)

    fun steamFiles(game: Library.SteamGame): SteamFiles? {
        val dir = game.gameFiles ?: return null
        val common = dir.parentFile ?: return null
        if (common.name != "common") return null
        val steamapps = common.parentFile ?: return null
        if (steamapps.name != "steamapps") return null
        val manifest = File(steamapps, "appmanifest_${game.appId}.acf")
        if (!manifest.isFile) return null
        if (!declares(manifest, game.appId)) return null
        return SteamFiles(dir, manifest, File(steamapps, "downloading/${game.appId}"))
    }

    fun delete(target: SteamFiles): Boolean {
        deleteTree(target.dir)
        if (target.dir.exists()) return false
        target.partial?.let { deleteTree(it) }
        target.manifest.delete()
        return !target.manifest.exists()
    }

    private fun deleteTree(dir: File) {
        if (Files.isSymbolicLink(dir.toPath())) {
            dir.delete()
            return
        }
        dir.listFiles()?.forEach { if (it.isDirectory) deleteTree(it) else it.delete() }
        dir.delete()
    }

    private fun declares(manifest: File, appId: Int): Boolean =
        runCatching { manifest.readText() }.getOrNull()?.let { Regex("\"appid\"\\s+\"$appId\"").containsMatchIn(it) } == true
}
