package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.LaunchInfo
import com.droiddeck.launcher.session.MemoryReadout
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SteamBranch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Home as a game card, a draft behind Settings > Advanced > "Home game card (draft)"; off by default. */
internal object HomeDraft {
    var on by mutableStateOf(false)
        private set

    fun load(context: Context) {
        on = SessionPrefs.homeDraft(context)
    }

    fun set(context: Context, value: Boolean) {
        on = value
        SessionPrefs.setHomeDraft(context, value)
    }
}

@Composable
internal fun SteamHomeDraft(s: FrontEndState, a: FrontEndActions, onSelect: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val games = remember(s.steamGames) { homeGames(s.steamGames) }
    val last = games.firstOrNull()
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = if (narrow) 16.dp else 32.dp, vertical = if (narrow) 16.dp else 28.dp),
    ) {
        Wordmark(if (narrow) 26.dp else 32.dp)
        if (s.busy || s.removalPending || (s.available != null && s.available != s.installed)) RuntimeChip(s)
        if (last == null) {
            PrimaryButton(
                stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, large = true,
                icon = Icons.Filled.PlayArrow, modifier = Modifier.testTag("play-steam"), onClick = a.onPlay,
            )
            Text(stringResource(R.string.home_empty), fontSize = 14.sp, color = colors.onSurfaceVariant)
        } else {
            HomeGameCard(last, enabled = !s.busy && last.gameIdString !in s.preparingGames, a = a, onOpen = { onSelect(GameRef.key(last)) })
            SecondaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, modifier = Modifier.testTag("play-steam"), onClick = a.onPlay)
            val others = games.drop(1)
            if (others.isNotEmpty()) {
                SectionTitle(stringResource(R.string.home_games), null)
                others.forEach { g ->
                    HomeGameRow(
                        g, enabled = !s.busy && g.gameIdString !in s.preparingGames,
                        onPlay = { a.onSteamGame(g) }, onOpen = { onSelect(GameRef.key(g)) },
                        onStable = { a.onSteamGameStable(g) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeGameCard(g: Library.SteamGame, enabled: Boolean, a: FrontEndActions, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val branches = SteamBranch.offered(g.appId)
    var stable by rememberSaveable(g.appId) { mutableStateOf(false) }
    val art: File? = g.hero ?: g.art
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).clip(Shape12).background(colors.surface).border(1.dp, pal.line, Shape12),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(CARD_ART_RATIO).background(artBrush(hueOf(g.name))).testTag("home-card")
                .paneItem("home:${g.gameIdString}").glideBorder(hot, Shape12, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onOpen)
                .controllerConfirm(onClick = onOpen),
        ) {
            if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ReadinessLine()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    stringResource(R.string.lib_play), enabled = enabled, main = true, large = true, fill = true,
                    icon = Icons.Filled.PlayArrow, modifier = Modifier.weight(1f).testTag("play-last"),
                ) { if (branches && stable) a.onSteamGameStable(g) else a.onSteamGame(g) }
                if (branches) SegmentedTabs(
                    listOf(false to stringResource(R.string.boot_branch_beta), true to stringResource(R.string.boot_branch_stable)), stable,
                    modifier = Modifier.width(BRANCH_TOGGLE_WIDTH),
                    tabModifier = { Modifier.testTag(if (it) "home-branch-stable" else "home-branch-beta") },
                ) { stable = it }
            }
        }
    }
}

/** Free memory and whether a game is likely to start smoothly in it, read again every few seconds. */
@Composable
private fun ReadinessLine() {
    val colors = MaterialTheme.colorScheme
    val memory by produceState<MemoryReadout.Device?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { MemoryReadout.parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull() }
            delay(MEMORY_REFRESH_MS)
        }
    }
    val device = memory ?: return
    val low = LaunchInfo.lowOnMemory(device)
    val free = String.format(Locale.getDefault(), "%.1f", device.availableKb / (1024.0 * 1024.0))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("home-ready")) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (low) AttentionAmber else LocalPalette.current.good))
        Text(stringResource(if (low) R.string.home_ready_low else R.string.home_ready, free), fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

private const val CARD_ART_RATIO = 460f / 215f
private val BRANCH_TOGGLE_WIDTH = 148.dp
private const val MEMORY_REFRESH_MS = 5_000L
