package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamLibraries
import com.droiddeck.launcher.session.SecondaryLibrary
import java.io.File

object Library {
    class SteamGame(
        val appId: Int, val name: String, val art: File?, val library: String, val gameId: Long = appId.toLong(),
        val hero: File? = null, val lastPlayed: Long = 0L,
        val gameFiles: File? = null, val protonPrefix: File? = null,
        val icon: File? = null,
        val logo: File? = null,
        val installedAt: Long = 0L,
    ) {
        fun withArt(art: File?, hero: File?, icon: File?, logo: File? = this.logo) =
            SteamGame(appId, name, art, library, gameId, hero, lastPlayed, gameFiles, protonPrefix, icon, logo, installedAt)

        val gameIdString: String get() = java.lang.Long.toUnsignedString(gameId)
        val profileKey: String get() = if (library == ADDED) java.lang.Integer.toUnsignedString(appId) else appId.toString()
    }
    const val ADDED = "added"
    private const val PLAYED = "added_games_played"

    private val NOT_GAMES = setOf(
        858280,
        961940,
        993090,
        1054830,
        1070560,
        1113280,
        1161040,
        1245040,
        1391110,
        1420170,
        1493710,
        1580130,
        1628350,
        1826330,
        1887720,
        2180100,
        228980,
        2348590,
        2805730,
        3029110,
        3127680,
        3658110,
        4183110,
        4185400,
        4427310,
        4628710,
        4628740,
        4690330,
    )
    private val STEAM_CAPSULES = listOf("library_capsule.jpg", "library_600x900.jpg")
    private val STEAM_HEROES = listOf("library_hero.jpg")
    internal val STEAM_LOGOS = listOf("logo.png")
    private val STEAM_ICON = Regex("[a-fA-F0-9]{40}\\.(jpg|png)")
    private val NAME = Regex("^\\s*\"name\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
    private val STATE = Regex("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val LAST_PLAYED = Regex("^\\s*\"LastPlayed\"\\s*\"(\\d+)\"", RegexOption.MULTILINE)
    private val PRIVATE_APPS = Regex("\"PrivateApps_\\d+\"\\s*\"\\[([^\\]]*)\\]\"")
    private val INSTALL_DIR = Regex("^\\s*\"installdir\"\\s*\"([^\"]*)\"", RegexOption.MULTILINE)

    internal fun steamLibraries(context: Context, arch: String = SessionPrefs.activeSteamClientArch(context)): List<Pair<File, String>> {
        val root = SessionPrefs.steamRoot(context, arch)
        val card = GameStorage.effective(context)
        val added = SteamLibraries.list(context).filter { it.host.isDirectory }
        if (arch == "x86_64") return listOfNotNull(root to "internal", card?.let { File(it.path, GameStorage.X64_CARD_DIR) to it.label }) +
            added.map { it.x64Host to it.label }
        return listOfNotNull(
            root to "internal",
            card?.let { File(it.path) to it.label },
        ) + added.map { it.host to it.label } + GameStorage.gamesFolderLibraries(context).map { it.host to it.label }
    }

    fun protonPrefix(
        context: Context, appId: Long, preferredLibrary: File? = null,
        libraries: List<File> = steamLibraries(context).map { it.first },
    ): File? {
        val ids = listOf(appId.toString(), java.lang.Integer.toString(appId.toInt())).distinct()
        return prefixRoots(context, libraries, preferredLibrary).asSequence()
            .flatMap { root -> ids.asSequence().map { id -> File(root, "steamapps/compatdata/$id/pfx") } }
            .firstOrNull { it.isDirectory }
    }

    fun compatdataDirs(context: Context, libraries: List<File> = steamLibraries(context).map { it.first }): List<File> =
        prefixRoots(context, libraries).map { File(it, "steamapps/compatdata") }.filter { it.isDirectory }

    private fun prefixRoots(context: Context, libraries: List<File>, preferredLibrary: File? = null): List<File> {
        val secondary = libraries.drop(1).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
        fun prefixRoot(root: File): File = if (runCatching { root.canonicalPath }.getOrNull() in secondary)
            SecondaryLibrary.privateRoot(context.filesDir, root).takeIf { File(it, "steamapps/compatdata").isDirectory } ?: root
            else root
        return (listOfNotNull(preferredLibrary) + libraries).map(::prefixRoot)
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    fun steamGames(
        context: Context, strictRead: Boolean = false, arch: String = SessionPrefs.activeSteamClientArch(context),
        withShared: Boolean = true,
    ): List<SteamGame> {
        val root = SessionPrefs.steamRoot(context, arch)
        val cache = File(root, "appcache/librarycache")
        val libraries = steamLibraries(context, arch)
        val roots = libraries.map { it.first }
        val out = LinkedHashMap<Int, SteamGame>()
        for ((library, label) in libraries) {
            val steamapps = File(library, "steamapps")
            val manifests = steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") }
            if (strictRead && steamapps.isDirectory) check(manifests != null) { context.getString(R.string.game_sync_err_manifests) }
            manifests?.sortedBy { it.name }?.forEach { manifest ->
                    val appId = manifest.name.removePrefix("appmanifest_").removeSuffix(".acf").toIntOrNull() ?: return@forEach
                    if (appId in NOT_GAMES || out.containsKey(appId)) return@forEach
                    val text = try { manifest.readText() } catch (e: Exception) { if (strictRead) throw e; return@forEach }
                    val name = NAME.find(text)?.groupValues?.get(1)?.trim().orEmpty()
                    val flags = STATE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (name.isEmpty() || flags and 4 == 0) return@forEach
                    val images = SteamCacheImages(File(cache, appId.toString()))
                    val art = images.find(STEAM_CAPSULES)
                    val hero = images.find(STEAM_HEROES)
                    val lastPlayed = LAST_PLAYED.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val installDir = INSTALL_DIR.find(text)?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
                    val gameFiles = installDir?.let { File(steamapps, "common/$it").takeIf(File::isDirectory) }
                    out[appId] = SteamGameArt.withOverrides(context, SteamGame(
                        appId, name, art, label, hero = hero, lastPlayed = lastPlayed,
                        gameFiles = gameFiles,
                        protonPrefix = protonPrefix(context, appId.toLong(), library, roots),
                        icon = images.icon(),
                        logo = images.find(STEAM_LOGOS),
                    ))
                }
        }
        if (withShared) {
            val other = if (arch == "x86_64") "arm64" else "x86_64"
            val shared = sharedApps(context, arch) - out.keys
            if (shared.isNotEmpty()) runCatching { steamGames(context, arch = other, withShared = false) }.getOrDefault(emptyList())
                .filter { it.appId in shared }.forEach { out[it.appId] = it }
        }
        return out.values.toList()
    }

    internal fun sharedApps(context: Context, arch: String): Set<Int> {
        val view = File(LinuxRuntime.rootDir(context), "root/.droiddeck-x64/shared/${if (arch == "x86_64") "arm64" else "x86-64"}/steamapps")
        return view.list().orEmpty().mapNotNull { Regex("appmanifest_(\\d+)\\.acf").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
    }

    fun steamLibraryArt(context: Context): List<Pair<Int, File>> {
        val root = SessionPrefs.activeSteamRoot(context)
        val cache = File(root, "appcache/librarycache")
        val concealed = concealedSteamApps(root)
        return cache.listFiles().orEmpty().mapNotNull { dir ->
            val appId = dir.name.toIntOrNull()?.takeIf { it !in NOT_GAMES && it.toLong() !in concealed } ?: return@mapNotNull null
            steamCacheImage(cache, appId, STEAM_CAPSULES)?.let { appId to it }
        }
    }

    internal fun concealedSteamApps(root: File): Set<Long> {
        val config = AddedGames.accountConfig(root) ?: return emptySet()
        val hidden = runCatching {
            val entries = org.json.JSONArray(File(config, "cloudstorage/cloud-storage-namespace-1.json").readText())
            val entry = (0 until entries.length()).map { entries.getJSONArray(it) }
                .firstOrNull { it.optString(0) == "user-collections.hidden" }?.optJSONObject(1)
            if (entry == null || entry.optBoolean("is_deleted")) emptySet() else {
                val collection = org.json.JSONObject(entry.optString("value"))
                fun ids(key: String) = collection.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty()
                ids("added").toSet() - ids("removed").toSet()
            }
        }.getOrDefault(emptySet())
        val private = runCatching {
            PRIVATE_APPS.find(File(config, "localconfig.vdf").readText())?.groupValues?.get(1)
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
        }.getOrNull().orEmpty()
        return hidden + private
    }

    fun fromSteamClient(g: SteamGame): Boolean = g.library != ADDED

    fun launchableGames(
        context: Context, strictRead: Boolean = false, added: List<AddedGames.Game>? = null,
        arch: String = SessionPrefs.activeSteamClientArch(context),
    ): List<SteamGame> {
        val roots = steamLibraries(context, arch).map { it.first }
        val list = added ?: AddedGames.scan(context)
        var steam = steamGames(context, strictRead, arch)
        val targets = steam.filter(::fromSteamClient).map { SteamGrid.target(it, context) } + list.map { SteamGrid.target(it, context) }
        if (runCatching { SteamGrid.sync(context, targets) }.getOrDefault(false)) steam = steamGames(context, strictRead, arch)
        val claimed = claimedFolders(steam)
        val own = list.filter { g ->
            g.steamAppId != null || claimed.isEmpty() ||
                (runCatching { g.folder.canonicalPath }.getOrDefault(g.folder.absolutePath) !in claimed &&
                    AddedGames.guestPath(context, g.folder).let { it == null || it !in claimed })
        }
        val played = shortcutsLastPlayed(SessionPrefs.steamRoot(context, arch))
        return (steam + own.map { g -> addedGame(context, g, roots, played[g.appId] ?: 0L) }).distinctBy { it.gameId }
    }

    fun recordPlayed(context: Context, game: SteamGame) {
        if (game.library == ADDED) recordPlayed(context, game.gameIdString)
    }

    fun recordPlayed(context: Context, gameId: String) {
        context.getSharedPreferences(PLAYED, Context.MODE_PRIVATE).edit()
            .putLong(gameId, System.currentTimeMillis() / 1000).apply()
    }

    internal fun recordedPlay(context: Context, gameId: Long): Long =
        context.getSharedPreferences(PLAYED, Context.MODE_PRIVATE).getLong(java.lang.Long.toUnsignedString(gameId), 0L)

    internal fun shortcutsLastPlayed(root: File): Map<Long, Long> = runCatching {
        val config = AddedGames.accountConfig(root) ?: return emptyMap()
        val bytes = File(config, "shortcuts.vdf").takeIf { it.isFile }?.readBytes() ?: return emptyMap()
        val vdf = SteamAppInfo.readMap(java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN), null)
        (vdf["shortcuts"] as? Map<*, *>)?.values?.filterIsInstance<Map<*, *>>()?.mapNotNull { s ->
            fun int(key: String) = s.entries.firstOrNull { (it.key as? String).equals(key, ignoreCase = true) }
                ?.let { (it.value as? String)?.toLongOrNull() }?.and(0xFFFFFFFFL)
            val id = int("appid") ?: return@mapNotNull null
            val time = int("LastPlayTime")?.takeIf { it > 0L } ?: return@mapNotNull null
            id to time
        }?.toMap()
    }.getOrNull().orEmpty()

    fun claimedFolders(steam: List<SteamGame>): Set<String> = steam.filter(::fromSteamClient).flatMap { g ->
        val dir = g.gameFiles ?: return@flatMap emptyList()
        listOfNotNull(
            runCatching { dir.canonicalPath }.getOrNull(),
            runCatching { java.nio.file.Files.readSymbolicLink(dir.toPath()).toString() }.getOrNull(),
        )
    }.toSet()

    fun addedGame(
        context: Context, g: AddedGames.Game, roots: List<File> = steamLibraries(context).map { it.first },
        lastPlayed: Long = 0L,
    ): SteamGame =
        AddedGameArt.resolve(context, g).let { art ->
            SteamGame(
                g.steamAppId ?: g.appId.toInt(), g.name, art.portrait ?: art.header, ADDED, g.gameId,
                hero = art.hero ?: art.header, lastPlayed = maxOf(lastPlayed, recordedPlay(context, g.gameId)), gameFiles = g.folder,
                protonPrefix = protonPrefix(context, g.steamAppId?.toLong() ?: g.appId, libraries = roots),
                icon = art.icon?.takeIf { it.extension.lowercase() != "ico" },
                logo = art.logo,
                installedAt = g.folder.lastModified(),
            )
        }

    internal fun steamCacheImage(cache: File, appId: Int, names: List<String>): File? =
        SteamCacheImages(File(cache, appId.toString())).find(names)

    internal class SteamCacheImages(appDir: File) {
        private val entries = appDir.listFiles().orEmpty()
        private val dirs = listOf(appDir) + entries.filter { it.isDirectory }.sortedBy { it.name }

        fun find(names: List<String>): File? = names.asSequence()
            .flatMap { name -> dirs.asSequence().map { File(it, name) } }
            .firstOrNull { it.isFile && it.length() > 0L }

        fun icon(): File? = find(listOf("icon.jpg", "icon.png"))
            ?: entries.firstOrNull { it.isFile && STEAM_ICON.matches(it.name) }
    }

    fun nameForProgram(program: String?): String? =
        if (program == com.droiddeck.launcher.runtime.UserApps.SCRIPT_LAUNCHER) {
            com.droiddeck.launcher.session.SessionState.programArgs.firstOrNull()?.let { appNames[it] ?: it.substringAfterLast('/') }
        } else com.droiddeck.launcher.session.NoSteamLaunch.nameFor(program, com.droiddeck.launcher.session.SessionState.programArgs)

    val appNames = java.util.concurrent.ConcurrentHashMap<String, String>()
}
