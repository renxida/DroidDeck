package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import coil.imageLoader

/**
 * Drops caches the app process holds for the front end while a session runs. Coil's memory cache
 * may hold 20 % of the large memory class in bitmaps (coil 2.6.0 Utils.kt defaultMemoryCacheSizePercent)
 * and, while the app is in the foreground, Coil itself only halves it from TRIM_MEMORY_RUNNING_LOW
 * (StrongMemoryCache.trimMemory). The images come back from Coil's disk cache when the front end shows them.
 */
object AppCaches {
    private const val TAG = "AppCaches"

    fun drop(context: Context, reason: String): Int? = runCatching {
        val cache = context.imageLoader.memoryCache ?: return@runCatching null
        val bytes = cache.size
        cache.clear()
        Log.i(TAG, line(reason, bytes))
        bytes
    }.getOrNull()

    fun line(reason: String, bytes: Int): String = "DROIDDECK_APP_CACHE dropped=coil reason=$reason mb=${bytes / (1 shl 20)}"
}
