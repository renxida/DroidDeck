package com.droiddeck.launcher.update

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * One check of the fork's "latest" release: the release's metadata first (a conditional request, so an unchanged
 * release costs a 304), then the APK (conditional too) into [dir], then [verify]. The release's and the APK's
 * ETags are kept only once an asset has been dealt with, so a download that broke off is tried again next time.
 */
internal class UpdateCheck(
    private val dir: File,
    private val prefs: SharedPreferences,
    private val http: UpdateHttp,
    private val verify: (File) -> String?,
    private val progress: (Int) -> Unit = {},
) {
    sealed class Outcome {
        /** Nothing new, and nothing staged. */
        object UpToDate : Outcome()
        /** A verified APK waits in [file]. */
        class Ready(val file: File) : Outcome()
        /** The release's APK failed verification and was deleted. */
        class Rejected(val reason: String) : Outcome()
        /** The check did not finish; it runs again next time. */
        class Failed(val reason: String) : Outcome()

        fun line(): String = when (this) {
            UpToDate -> "up to date"
            is Ready -> "update ready"
            is Rejected -> "rejected: $reason"
            is Failed -> "failed: $reason"
        }
    }

    val staged: File get() = File(dir, APK)

    /** The staged APK when it still passes [verify]; otherwise it is deleted. */
    fun stagedReady(): File? {
        val file = staged.takeIf { it.isFile } ?: return null
        val refused = runCatching { verify(file) }.getOrElse { "verification failed: ${it.javaClass.simpleName}" }
        if (refused == null) return file
        android.util.Log.i(TAG, "staged update dropped: $refused")
        file.delete()
        return null
    }

    private fun idle(): Outcome = stagedReady()?.let { Outcome.Ready(it) } ?: Outcome.UpToDate

    fun run(): Outcome {
        dir.mkdirs()
        val releaseHeaders = linkedMapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        prefs.getString(RELEASE_ETAG, null)?.let { releaseHeaders["If-None-Match"] = it }
        val (releaseEtag, asset) = try {
            http.get(RELEASE_API, releaseHeaders).use { reply ->
                when (reply.code) {
                    304 -> return idle()
                    200 -> {
                        val text = reply.body?.use { readCapped(it, MAX_RELEASE_JSON) } ?: return Outcome.Failed("empty release")
                        reply.etag to findAsset(JSONObject(text))
                    }
                    else -> return Outcome.Failed("release HTTP ${reply.code}")
                }
            }
        } catch (e: Exception) {
            return Outcome.Failed("release: ${e.javaClass.simpleName}")
        }
        if (asset == null) return Outcome.Failed("the release has no $APK")
        if (asset.key == prefs.getString(HANDLED, null)) {
            remember(releaseEtag, prefs.getString(APK_ETAG, null), asset.key)
            return idle()
        }
        if (asset.size > MAX_APK_BYTES) return Outcome.Failed("asset of ${asset.size} bytes is too large")

        val apkHeaders = linkedMapOf<String, String>()
        prefs.getString(APK_ETAG, null)?.let { apkHeaders["If-None-Match"] = it }
        val part = File(dir, "$APK.part")
        val apkEtag = try {
            http.get(asset.url, apkHeaders).use { reply ->
                when (reply.code) {
                    304 -> {
                        remember(releaseEtag, prefs.getString(APK_ETAG, null), asset.key)
                        return idle()
                    }
                    200 -> {
                        val body = reply.body ?: return Outcome.Failed("empty APK")
                        if (reply.length > MAX_APK_BYTES) return Outcome.Failed("APK of ${reply.length} bytes is too large")
                        val expected = if (asset.size > 0) asset.size else reply.length
                        val written = body.use { copyCapped(it, part, expected) }
                        if (expected > 0 && written != expected) {
                            part.delete()
                            return Outcome.Failed("APK was $written of $expected bytes")
                        }
                        reply.etag
                    }
                    else -> return Outcome.Failed("APK HTTP ${reply.code}")
                }
            }
        } catch (e: Exception) {
            part.delete()
            return Outcome.Failed("APK: ${e.javaClass.simpleName}")
        }
        staged.delete()
        if (!part.renameTo(staged)) {
            part.delete()
            return Outcome.Failed("could not stage the APK")
        }
        val refused = runCatching { verify(staged) }.getOrElse { "verification failed: ${it.javaClass.simpleName}" }
        remember(releaseEtag, apkEtag, asset.key)
        if (refused != null) {
            staged.delete()
            android.util.Log.w(TAG, "update rejected: $refused")
            return Outcome.Rejected(refused)
        }
        return Outcome.Ready(staged)
    }

    private fun remember(releaseEtag: String?, apkEtag: String?, key: String) {
        prefs.edit().apply {
            if (releaseEtag != null) putString(RELEASE_ETAG, releaseEtag) else remove(RELEASE_ETAG)
            if (apkEtag != null) putString(APK_ETAG, apkEtag) else remove(APK_ETAG)
            putString(HANDLED, key)
        }.commit()
    }

    private fun copyCapped(input: java.io.InputStream, target: File, expected: Long): Long {
        var total = 0L
        var lastPercent = -1
        target.outputStream().use { out ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_APK_BYTES) throw java.io.IOException("APK larger than $MAX_APK_BYTES bytes")
                out.write(buffer, 0, n)
                if (expected > 0) {
                    val percent = (total * 100 / expected).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                }
            }
            out.fd.sync()
        }
        return total
    }

    internal class Asset(val key: String, val url: String, val size: Long)

    companion object {
        private const val TAG = "SelfUpdate"
        const val REPO = "familiar-of-cedar/lanner"
        const val TAG_NAME = "latest"
        const val APK = "lanner.apk"
        /** The name the release's APK had before; a release that has only this one is still followed. */
        const val APK_ALIAS = "kestrel.apk"
        const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/tags/$TAG_NAME"
        const val APK_URL = "https://github.com/$REPO/releases/download/$TAG_NAME/$APK"
        const val APK_ALIAS_URL = "https://github.com/$REPO/releases/download/$TAG_NAME/$APK_ALIAS"
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        private const val MAX_RELEASE_JSON = 1024 * 1024
        internal const val RELEASE_ETAG = "release_etag"
        internal const val APK_ETAG = "apk_etag"
        internal const val HANDLED = "handled_asset"

        /**
         * The release's lanner.apk, or its kestrel.apk when it has no lanner.apk. Downloads always go to the fixed
         * [APK_URL] or [APK_ALIAS_URL], never to a URL the release names.
         */
        internal fun findAsset(release: JSONObject): Asset? {
            val assets = release.optJSONArray("assets") ?: return null
            for ((name, url) in listOf(APK to APK_URL, APK_ALIAS to APK_ALIAS_URL)) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name") != name) continue
                    return Asset("${a.optLong("id")}@${a.optString("updated_at")}@${a.optLong("size")}", url, a.optLong("size", 0L))
                }
            }
            return null
        }

        private fun readCapped(input: java.io.InputStream, cap: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > cap) throw java.io.IOException("response larger than $cap bytes")
                out.write(buffer, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
