package com.droiddeck.launcher.frontend

import android.content.Context
import java.io.File

object WinCompSources {
    enum class Origin { STEAM, STEAM_NAME, FOLDER }

    data class Finding(val component: String?, val original: String, val origin: Origin, val auto: Boolean, val args: List<String> = emptyList())

    fun steamDepots(ids: List<String>): List<Pair<String, String>> = ids.mapNotNull { SteamRedists.DEPOTS[it] }.distinctBy { it.first }

    fun steamList(context: Context, gameDir: File?, appId: Int, gameName: String, auto: Boolean): List<Finding> {
        val local = SteamRedists.detect(gameDir, appId)
        val pairs = (if (local.isNotEmpty()) local.map { it.componentName to it.label }
        else SteamAppInfo.info(context, appId)?.let { steamDepots(it.sharedDepots) }.orEmpty()) +
            SteamRedists.installScript(gameDir) + SteamRedists.SCRIPTED[appId].orEmpty()
        return pairs.distinctBy { it.first.removeSuffix("_dll") }.map { (component, label) ->
            Finding(component, label, if (auto) Origin.STEAM else Origin.STEAM_NAME, auto, listOf(label, gameName, appId.toString()))
        }
    }

    fun forGame(context: Context, game: Library.SteamGame): List<Finding> {
        val folder = game.gameFiles
        val out = ArrayList<Finding>()
        if (game.library != Library.ADDED && game.appId > 0) out += steamList(context, folder, game.appId, game.name, auto = true)
        if (game.library == Library.ADDED && folder != null) SteamMatch.get(context, folder.path)?.let { match ->
            val id = match.appId ?: return@let
            when (match.certainty) {
                SteamMatch.Certainty.FILES -> out += steamList(context, folder, id, game.name, auto = true)
                SteamMatch.Certainty.NAME -> out += steamList(context, folder, id, game.name, auto = false)
                else -> {}
            }
        }
        folder?.let { DependencyDetector.detect(it) }?.forEach { rec ->
            out += Finding(rec.componentName, rec.reason, Origin.FOLDER, false, listOf(rec.reason, rec.kind.name))
        }
        return out
    }
}
