package com.droiddeck.launcher.frontend

import android.content.Context
import java.io.File

object WinCompSources {
    enum class Origin { STEAM, FOLDER }

    data class Finding(val component: String?, val original: String, val origin: Origin, val auto: Boolean, val args: List<String> = emptyList())

    fun steamDepots(ids: List<String>): List<Pair<String, String>> = ids.mapNotNull { SteamRedists.DEPOTS[it] }.distinctBy { it.first }

    fun steamList(context: Context, gameDir: File?, appId: Int, gameName: String): List<Finding> {
        val local = SteamRedists.detect(gameDir, appId)
        val pairs = (if (local.isNotEmpty()) local.map { it.componentName to it.label }
        else SteamAppInfo.info(context, appId)?.let { steamDepots(it.sharedDepots) }.orEmpty()) +
            SteamRedists.installScript(gameDir) + SteamRedists.SCRIPTED[appId].orEmpty()
        return pairs.distinctBy { it.first.removeSuffix("_dll") }.map { (component, label) ->
            Finding(component, label, Origin.STEAM, true, listOf(label, gameName, appId.toString()))
        }
    }

    fun forGame(context: Context, game: Library.SteamGame): List<Finding> {
        val folder = game.gameFiles
        val out = ArrayList<Finding>()
        if (game.appId > 0) out += steamList(context, folder, game.appId, game.name)
        folder?.let { DependencyDetector.detect(it) }?.forEach { rec ->
            out += Finding(rec.componentName, rec.reason, Origin.FOLDER, false, listOf(rec.reason, rec.kind.name))
        }
        return out
    }
}
