package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.testTag
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.frontend.Library
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue


@Composable
internal fun GameFileFolderActions(s: FrontEndState, a: FrontEndActions) {
    if (s.shortcutPicker) return
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        SecondaryButton(stringResource(R.string.game_frontend_files), compact = true) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = s.gameSyncFolder ?: stringResource(R.string.game_frontend_files)) { first ->
            MenuItem(stringResource(R.string.game_file_sync), checked = false, focusRequester = first) {
                open = false; a.onSyncGameFiles()
            }
            if (s.gameSyncFolder != null) MenuItem(stringResource(R.string.game_file_stop_sync), checked = false) {
                open = false; a.onStopGameFileSync()
            }
        }
    }
}


@Composable
internal fun ClientTag() {
    Box(Modifier.testTag("games-client-x64")) { StateChip(stringResource(R.string.mode_client_arch_x64), ChipTone.PLAIN) }
}

internal object GamesDialogState {
    var steamArt by androidx.compose.runtime.mutableStateOf<Library.SteamGame?>(null)
}

@Composable
internal fun GamesDialogs(a: FrontEndActions, games: List<Library.SteamGame>) {
    GamesDialogState.steamArt?.let { game ->
        SteamGameArtEditor(games.firstOrNull { it.appId == game.appId } ?: game, onClose = { GamesDialogState.steamArt = null }, onChanged = { a.onSteamGameArtChanged(game.appId) })
    }
}

@Composable
internal fun PageHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Text(
            title, fontSize = if (LocalNarrowPane.current) 22.sp else 26.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
        )
        trailing()
    }
}

@Composable
internal fun BusyChip(s: FrontEndState) {
    if (s.busy) ActionChip(if (s.percent >= 0) stringResource(R.string.games_runtime_busy_percent, s.percent) else s.stage, ok = false)
}

@Composable
internal fun RuntimeChip(s: FrontEndState) = when {
    s.busy -> Chip(if (s.percent >= 0) stringResource(R.string.setup_runtime_progress, s.stage, s.percent) else s.stage, ok = false)
    s.removalPending -> Chip(stringResource(R.string.runtime_removal_incomplete), ok = false)
    s.available != null && s.available != s.installed -> Chip(stringResource(R.string.games_runtime_update), ok = false)
    else -> Chip(stringResource(R.string.games_runtime_ready), ok = true)
}

internal fun playedSpan(lastPlayed: Long): String? {
    if (lastPlayed <= 0L) return null
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        lastPlayed * 1000L, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

@Composable
internal fun libraryLabel(g: Library.SteamGame): String =
    if (g.library == "internal") stringResource(R.string.games_internal) else g.library
