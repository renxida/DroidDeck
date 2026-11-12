package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.Library

object NoSteamLaunch {
    const val PROTON_RUN = "/usr/local/bin/droiddeck-proton-run"

    class Command(val program: String, val args: List<String>, val name: String)

    fun scope(game: Library.SteamGame): String? =
        game.profileKey.takeIf { game.appId > 0 && GameEnvironment.validScope(it) }

    fun enabled(config: GameEnvironment.Config, game: Library.SteamGame): Boolean =
        scope(game)?.let { GameEnvironment.gameNoSteam(config, it) } == true

    fun enabled(context: Context, game: Library.SteamGame): Boolean =
        scope(game) != null && runCatching { enabled(GameEnvironmentStore.read(context), game) }.getOrDefault(false)

    fun enabled(context: Context, appId: Long): Boolean = runCatching {
        GameEnvironment.gameNoSteam(GameEnvironmentStore.read(context), (appId and 0xFFFFFFFFL).toString())
    }.getOrDefault(false)

    fun command(game: Library.SteamGame): Command? =
        if (game.appId > 0) Command(PROTON_RUN, listOf("--app", game.appId.toString()), game.name) else null

    fun nameFor(program: String?, args: List<String>): String? = when (program) {
        PROTON_RUN -> args.getOrNull(args.indexOf("--app") + 1)?.takeIf { args.contains("--app") }?.let { names[it] }
        else -> null
    }

    val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun remember(command: Command) {
        if (command.program == PROTON_RUN) command.args.getOrNull(command.args.indexOf("--app") + 1)?.let { names[it] = command.name }
    }
}
