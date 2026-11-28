package com.droiddeck.launcher.update

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The updater's only network access: unauthenticated GETs, so tests can stand in for GitHub. */
interface UpdateHttp {
    class Reply(val code: Int, val etag: String?, val length: Long, val body: InputStream?, private val release: () -> Unit = {}) : Closeable {
        override fun close() {
            runCatching { body?.close() }
            runCatching { release() }
        }
    }

    fun get(url: String, headers: Map<String, String>): Reply
}

internal object UrlConnectionHttp : UpdateHttp {
    override fun get(url: String, headers: Map<String, String>): UpdateHttp.Reply {
        require(url.startsWith("https://")) { "https only" }
        val proxy = com.droiddeck.launcher.core.DownloadSource.httpProxy()
        val conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "kestrel-self-update")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = try { conn.responseCode } catch (e: Exception) { conn.disconnect(); throw e }
        if (!conn.url.toString().startsWith("https://")) {
            conn.disconnect()
            throw java.io.IOException("redirected off https")
        }
        val body = if (code == 200) conn.inputStream else null
        return UpdateHttp.Reply(code, conn.getHeaderField("ETag"), conn.contentLengthLong, body) { conn.disconnect() }
    }
}
