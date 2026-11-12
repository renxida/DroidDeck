package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

fun <T> sortGames(
    items: List<T>,
    sort: String,
    title: (T) -> String,
    lastPlayed: (T) -> Long = { 0L },
    installedAt: (T) -> Long = { 0L },
    size: (T) -> Long = { 0L },
): List<T> {
    val byTitle = compareBy<T> { title(it).lowercase() }
    return when (sort) {
        "lastPlayed" -> items.sortedWith(compareByDescending(lastPlayed).then(byTitle))
        "recentlyInstalled" -> items.sortedWith(compareByDescending(installedAt).then(byTitle))
        "titleAsc" -> items.sortedWith(byTitle)
        "titleDesc" -> items.sortedWith(byTitle.reversed())
        "sizeDesc" -> items.sortedWith(compareByDescending(size).then(byTitle))
        "sizeAsc" -> items.sortedWith(compareBy<T> { size(it).takeIf { b -> b > 0 } ?: Long.MAX_VALUE }.then(byTitle))
        else -> items
    }
}

internal fun <T> retainOrder(items: List<T>, previous: List<T>, key: (T) -> String): List<T> {
    if (items.size != previous.size) return items
    val byKey = items.associateBy(key)
    if (previous.any { key(it) !in byKey }) return items
    return previous.map { byKey.getValue(key(it)) }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    bytes >= 0L -> "$bytes B"
    else -> ""
}

fun formatSpeed(bytesPerSec: Long): String = if (bytesPerSec <= 0L) "" else "${formatBytes(bytesPerSec)}/s"

@Composable
internal fun cardArtRequest(url: Any?, cover: Any?): coil.request.ImageRequest? {
    val ctx = LocalContext.current
    val under = (cover as? String)?.takeIf { it != url }
    return remember(url, under) {
        url?.let { coil.request.ImageRequest.Builder(ctx).data(it).apply { if (under != null) placeholderMemoryCacheKey(under) }.build() }
    }
}
