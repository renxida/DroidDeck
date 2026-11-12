package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.Executors

object SteamGameArt {
    internal fun dir(context: Context, appId: Int) = File(context.filesDir, "steam-art/$appId")

    private fun sourceFile(context: Context, appId: Int, slot: GameArt.Slot) = File(dir(context, appId), "source-${slot.key}")

    internal fun setSource(context: Context, appId: Int, slot: GameArt.Slot, value: String) {
        val file = sourceFile(context, appId, slot)
        if (value.isEmpty()) file.delete() else file.apply { parentFile?.mkdirs() }.writeText(value)
    }

    fun chosenFile(context: Context, appId: Int, slot: GameArt.Slot): File? = GameArt.chosen(dir(context, appId), slot)

    fun withOverrides(context: Context, game: Library.SteamGame): Library.SteamGame {
        if (!dir(context, game.appId).isDirectory) return game
        val cover = chosenFile(context, game.appId, GameArt.Slot.COVER)
        val hero = chosenFile(context, game.appId, GameArt.Slot.BACKGROUND)
        val icon = chosenFile(context, game.appId, GameArt.Slot.ICON)
        val logo = chosenFile(context, game.appId, GameArt.Slot.LOGO)
        if (cover == null && hero == null && icon == null && logo == null) return game
        return Library.SteamGame(
            game.appId, game.name, cover ?: game.art, game.library, game.gameId,
            hero ?: game.hero, game.lastPlayed, game.gameFiles, game.protonPrefix, icon ?: game.icon,
            logo ?: game.logo,
        )
    }

    fun chosenSource(context: Context, appId: Int, slot: GameArt.Slot): Pair<String, String> {
        val value = sourceFile(context, appId, slot).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return value.substringBefore('|') to value.substringAfter('|', "")
    }

    fun describe(context: Context, game: Library.SteamGame): List<GameArt.SlotState> {
        val cache = File(SessionPrefs.activeSteamRoot(context), "appcache/librarycache")
        val own = withOverrides(context, game)
        return GameArt.Slot.values().map { slot ->
            chosenFile(context, game.appId, slot)?.let {
                return@map GameArt.SlotState(slot, it, chosenSource(context, game.appId, slot).first.ifEmpty { GameArt.FILE }, true)
            }
            val auto = when (slot) {
                GameArt.Slot.COVER -> own.art
                GameArt.Slot.WIDE -> Library.steamCacheImage(cache, game.appId, listOf("library_header.jpg", "header.jpg"))
                GameArt.Slot.BACKGROUND -> own.hero
                GameArt.Slot.ICON -> own.icon
                GameArt.Slot.LOGO -> Library.steamCacheImage(cache, game.appId, listOf("logo.png"))
            }
            GameArt.SlotState(slot, auto, if (auto != null) GameArt.STEAM else GameArt.AUTO, false)
        }
    }

    fun choices(context: Context, game: Library.SteamGame, slot: GameArt.Slot): GameArt.Choices {
        val out = ArrayList<GameArt.Option>()
        GameArt.steamOption(game.appId.toString(), slot)?.let { out.add(it) }
        val (found, status) = GameArt.sgdbChoices(context, File(dir(context, game.appId), "sgdb-id"), game.appId, game.name, slot)
        out.addAll(found)
        return GameArt.Choices(out, status)
    }

    fun choose(context: Context, game: Library.SteamGame, slot: GameArt.Slot, option: GameArt.Option): Boolean {
        return synchronized(SteamGrid.lock) {
            val folder = dir(context, game.appId)
            val fresh = GameArt.storeNew(folder, slot, option) ?: return@synchronized false
            if (!push(context, game, slot, fresh)) {
                fresh.delete()
                return@synchronized false
            }
            GameArt.dropOlder(folder, slot, fresh)
            setSource(context, game.appId, slot, option.group + "|" + option.full)
            true
        }
    }

    fun reset(context: Context, game: Library.SteamGame, slot: GameArt.Slot) {
        synchronized(SteamGrid.lock) {
            if (!push(context, game, slot, null)) return@synchronized
            GameArt.chosen(dir(context, game.appId), slot)?.delete()
            setSource(context, game.appId, slot, "")
        }
    }

    fun position(context: Context, game: Library.SteamGame): LogoPosition.Value? = SteamGrid.position(SteamGrid.target(game, context))

    fun setPosition(context: Context, game: Library.SteamGame, value: LogoPosition.Value?): Boolean {
        if (!SteamGrid.writePosition(context, SteamGrid.target(game, context), value)) return false
        publish(context) { SteamLive.setLogoPosition(context, game.appId.toLong(), value) }
        return true
    }

    private fun push(context: Context, game: Library.SteamGame, slot: GameArt.Slot, file: File?): Boolean {
        val type = slot.assetType ?: return true
        if (!SteamGrid.write(context, SteamGrid.target(game, context), slot, file)) return false
        publish(context) { SteamLive.setArt(context, game.appId.toLong(), type, file) }
        return true
    }

    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var publishNow = false

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "steam-art-publish") }

    private fun publish(context: Context, live: () -> Unit) {
        val app = context.applicationContext
        val work = Runnable {
            try { live() } catch (e: Exception) { Log.w("SteamGameArt", "live: ${e.message}") }
            try {
                val arch = SessionPrefs.activeSteamClientArch(app)
                LibraryCache.save(app, Library.launchableGames(app, arch = arch), arch == "x86_64")
            } catch (e: Exception) { Log.w("SteamGameArt", "listing: ${e.message}") }
        }
        if (publishNow) work.run() else worker.execute(work)
    }
}
