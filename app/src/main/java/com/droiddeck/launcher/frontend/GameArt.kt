package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object GameArt {
    private const val TAG = "GameArt"
    private const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    enum class Slot(val key: String, val assetType: Int?) {
        COVER("p", 0), WIDE("header", 3), BACKGROUND("hero", 1), LOGO("logo", 2), ICON("icon", null),
    }

    const val STEAM = "steam"
    const val SGDB = "sgdb"
    const val FILE = "file"
    const val AUTO = ""

    class SlotState(val slot: Slot, val file: File?, val source: String, val chosen: Boolean)

    class Option(val group: String, val thumb: String, val full: String)

    class Choices(val options: List<Option>, val sgdb: SteamGridDb.Status)

    class Found<T>(val items: List<T>, val status: SteamGridDb.Status)

    private val CHOSEN = Regex("""chosen-(p|header|hero|logo|icon)(?:-\d+)?\.(\w+)""")

    internal fun chosen(cache: File, slot: Slot): File? =
        cache.listFiles { f -> CHOSEN.matchEntire(f.name)?.groupValues?.get(1) == slot.key }?.maxWithOrNull(compareBy({ it.lastModified() }, { it.name }))

    private val CHOOSABLE = setOf("png", "jpg", "jpeg", "webp")

    internal fun steamOption(steamId: String, slot: Slot): Option? {
        val remote = when (slot) { Slot.COVER -> "library_600x900.jpg"; Slot.WIDE -> "header.jpg"; Slot.BACKGROUND -> "library_hero.jpg"; Slot.LOGO -> "logo.png"; Slot.ICON -> null } ?: return null
        val url = "$CDN$steamId/$remote".takeIf { exists(it) } ?: steamAssetUrl(steamId, remote) ?: return null
        return Option(STEAM, url, url)
    }

    internal fun sgdbChoices(context: Context, marker: File, steamId: Int?, name: String, slot: Slot): Pair<List<Option>, SteamGridDb.Status> {
        val key = SteamGridDb.key(context)
        if (key.isEmpty()) return emptyList<Option>() to SteamGridDb.Status.NO_KEY
        marker.parentFile?.mkdirs()
        return try {
            val gridId = remembered(marker, SteamGridDb.keyChangedAt(context), retryMiss = true) {
                SteamGridDb.gameId(key, steamId, name)?.toString()
            }?.toIntOrNull() ?: return emptyList<Option>() to SteamGridDb.Status.NO_GAME
            SteamGridDb.images(key, gridId, slot, if (slot == Slot.ICON) 3 else 5).map { (thumb, full) -> Option(SGDB, thumb, full) } to SteamGridDb.Status.OK
        } catch (e: Exception) {
            Log.w(TAG, "$name: SteamGridDB failed (${e.javaClass.simpleName})")
            emptyList<Option>() to ((e as? SteamGridDb.Failed)?.status ?: SteamGridDb.Status.SERVER)
        }
    }

    fun searchGames(context: Context, term: String): Found<SteamGridDb.Game> = sgdb(context) { SteamGridDb.search(it, term) }

    fun gameImages(context: Context, id: Int, slot: Slot): Found<Option> = sgdb(context) { key ->
        SteamGridDb.all(key, id, slot).map { (thumb, full) -> Option(SGDB, thumb, full) }
    }

    private fun <T> sgdb(context: Context, call: (String) -> List<T>): Found<T> {
        val key = SteamGridDb.key(context)
        if (key.isEmpty()) return Found(emptyList(), SteamGridDb.Status.NO_KEY)
        return try {
            Found(call(key), SteamGridDb.Status.OK)
        } catch (e: Exception) {
            Log.w(TAG, "SteamGridDB: ${e.javaClass.simpleName}")
            Found(emptyList(), (e as? SteamGridDb.Failed)?.status ?: SteamGridDb.Status.SERVER)
        }
    }

    internal fun store(cache: File, slot: Slot, option: Option): Boolean {
        val fresh = storeNew(cache, slot, option) ?: return false
        dropOlder(cache, slot, fresh)
        return true
    }

    internal fun dropOlder(cache: File, slot: Slot, keep: File) {
        cache.listFiles { f -> f != keep && CHOSEN.matchEntire(f.name)?.groupValues?.get(1) == slot.key }?.forEach { it.delete() }
    }

    private val lastStamp = java.util.concurrent.atomic.AtomicLong(0)

    private fun nextStamp(): Long = lastStamp.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    internal fun storeNew(cache: File, slot: Slot, option: Option): File? {
        val bytes = if (option.full.startsWith("https://")) get(option.full) else runCatching { File(option.full).readBytes() }.getOrNull()
        bytes ?: return null
        val ext = option.full.substringAfterLast('.', "png").substringBefore('?').lowercase().takeIf { it in CHOOSABLE + "ico" } ?: "png"
        cache.mkdirs()
        val suffix = if (ext == "jpeg") "jpg" else ext
        var dst = File(cache, "chosen-${slot.key}-" + "%013d".format(nextStamp()) + "." + suffix)
        while (dst.exists()) dst = File(cache, "chosen-${slot.key}-" + "%013d".format(nextStamp()) + "." + suffix)
        val tmp = File(cache, dst.name + ".tmp")
        tmp.writeBytes(bytes)
        return if (tmp.renameTo(dst)) dst else null
    }

    private fun exists(url: String): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    internal fun remembered(marker: File, notBefore: Long = 0L, retryMiss: Boolean = false, find: () -> String?): String? {
        if (marker.isFile) {
            val value = marker.readText().trim()
            if (value != "none") return value
            val at = marker.lastModified()
            if (!retryMiss && at >= notBefore && System.currentTimeMillis() - at < RETRY_AFTER_MS) return null
        }
        val found = find()
        marker.writeText(found ?: "none")
        return found
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val EDITION = Regex("(?i)\\b(complete|definitive|legendary|goty|game of the year|deluxe|ultimate|remastered)( edition)?\\b")

    internal fun nameMatch(name: String, got: String): Int {
        val want = normalize(name)
        val wantShort = normalize(name.replace(EDITION, " ").trim().ifEmpty { name })
        val have = normalize(got)
        if (have.isEmpty() || wantShort.isEmpty()) return 0
        return when {
            have == want -> 3
            have == wantShort -> 2
            have.startsWith(wantShort) || wantShort.startsWith(have) -> 1
            else -> 0
        }
    }

    private fun steamAssetUrl(id: String, remote: String): String? {
        val assets = steamAssets.getOrPut(id) {
            runCatching {
                val input = """{"ids":[{"appid":$id}],"context":{"language":"english","country_code":"US"},"data_request":{"include_assets":true}}"""
                val json = get("https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?input_json=" + URLEncoder.encode(input, "UTF-8"))
                    ?.toString(Charsets.UTF_8) ?: return@runCatching emptyMap()
                val a = JSONObject(json).optJSONObject("response")?.optJSONArray("store_items")?.optJSONObject(0)?.optJSONObject("assets")
                    ?: return@runCatching emptyMap()
                val format = a.optString("asset_url_format").takeIf { it.contains("\${FILENAME}") } ?: return@runCatching emptyMap()
                fun url(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotEmpty() } }
                    ?.let { "https://shared.akamai.steamstatic.com/store_item_assets/" + format.replace("\${FILENAME}", it) }
                mapOf(
                    "library_600x900.jpg" to url("library_capsule_2x", "library_capsule"),
                    "header.jpg" to url("header"),
                    "library_hero.jpg" to url("library_hero_2x", "library_hero"),
                ).filterValues { it != null }.mapValues { it.value!! }
            }.getOrDefault(emptyMap())
        }
        return assets[remote]
    }

    private val steamAssets = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    internal fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "$url: ${e.message}"); null
        } finally {
            c.disconnect()
        }
    }
}
