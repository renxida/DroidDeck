package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library

internal fun homeGames(games: List<Library.SteamGame>): List<Library.SteamGame> =
    games.sortedWith(compareByDescending<Library.SteamGame> { it.lastPlayed }.thenBy { it.name.lowercase() })

@Composable
internal fun SteamHome(s: FrontEndState, a: FrontEndActions, onSelect: (String) -> Unit, modifier: Modifier) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (last != null) {
                PrimaryButton(
                    stringResource(R.string.home_play_game, last.name),
                    enabled = !s.busy && last.gameIdString !in s.preparingGames, main = true, large = true, fill = true,
                    icon = Icons.Filled.PlayArrow, modifier = Modifier.testTag("play-last").widthIn(max = 460.dp),
                ) { a.onSteamGame(last) }
                SecondaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, modifier = Modifier.testTag("play-steam"), onClick = a.onPlay)
            } else {
                PrimaryButton(
                    stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, large = true,
                    icon = Icons.Filled.PlayArrow, modifier = Modifier.testTag("play-steam"), onClick = a.onPlay,
                )
            }
        }
        if (games.isEmpty()) {
            Text(stringResource(R.string.home_empty), fontSize = 14.sp, color = colors.onSurfaceVariant)
        } else {
            SectionTitle(stringResource(R.string.home_games), null)
            games.forEach { g ->
                HomeGameRow(
                    g, enabled = !s.busy && g.gameIdString !in s.preparingGames,
                    onPlay = { a.onSteamGame(g) }, onOpen = { onSelect(GameRef.key(g)) },
                )
            }
        }
    }
}

@Composable
private fun HomeGameRow(g: Library.SteamGame, enabled: Boolean, onPlay: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).paneItem("home:${g.gameIdString}")
            .heightIn(min = 56.dp).clip(Shape12)
            .background(colors.surface)
            .glideBorder(hot, Shape12, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onOpen)
            .controllerConfirm(onClick = onOpen)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            g.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        SecondaryButton(stringResource(R.string.lib_play), enabled = enabled, compact = true, modifier = Modifier.testTag("play:${g.gameIdString}"), onClick = onPlay)
    }
}
