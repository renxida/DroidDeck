package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import java.io.File
import com.droiddeck.launcher.frontend.Library
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

internal object GameRef {
    fun appId(key: String): Int? = key.removePrefix("app:").toIntOrNull()

    fun key(game: Library.SteamGame) = "app:${game.appId}"
}

internal enum class GameTab { ABOUT, LAUNCH, INSTALL }

@Composable
internal fun GameDetail(
    key: String, s: FrontEndState, a: FrontEndActions, lead: FocusRequester, backLabel: String, hopRail: String?, onBack: () -> Unit,
) {
    val narrow = LocalNarrowPane.current
    val stacked = narrow || LocalConfiguration.current.screenWidthDp < 700
    val appId = remember(key) { GameRef.appId(key) }
    val game = s.steamGames.firstOrNull { it.appId == appId }
    LaunchedEffect(game == null) { if (game == null) onBack() }
    if (game == null) return
    val title = game.name
    val tabs = buildList {
        if (!s.shortcutPicker && title.isNotBlank()) add(GameTab.ABOUT)
        add(GameTab.LAUNCH)
        add(GameTab.INSTALL)
    }
    var picked by remember(key) { mutableStateOf<GameTab?>(null) }
    val tab = picked?.takeIf { it in tabs } ?: tabs.first()
    val tabFocus = remember(key) { GameTab.entries.associateWith { FocusRequester() } }
    var inPanel by remember(key) { mutableStateOf(false) }
    var inPage by remember(key) { mutableStateOf(false) }
    var onStrip by remember(key) { mutableStateOf(false) }
    var dropped by remember(key) { mutableStateOf(0) }
    LaunchedEffect(dropped) {
        if (dropped == 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        if (!inPage) focusWithinFrames({ onStrip }) { tabFocus.getValue(tab) }
    }
    fun cycle(step: Int) {
        if (tabs.size < 2) return
        val at = tabs.indexOf(tab).coerceAtLeast(0)
        picked = tabs[(at + step + tabs.size) % tabs.size]
    }
    val art: Any? = game.hero
    val artModel: Any? = if (art is String) cardArtRequest(art, null) else art
    val inset = LocalDetailInset.current
    GameBackdrop(artModel, art ?: game.art, hueOf(title), fadeTop = inset.top < 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = inset.horizontal).padding(top = inset.top).onFocusChanged { inPage = it.hasFocus }.bumpers(onPrevious = { cycle(-1) }, onNext = { cycle(1) })) {
            Box {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.glass(CircleShape)) { BackLink(backLabel, compact = true, onClick = onBack) }
                    Spacer(Modifier.weight(1f))
                    if (!s.shortcutPicker) CompositionLocalProvider(LocalPillButtons provides true) { RemoveButton(game, a) }
                }
            }
            val header: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameStatusLine(game)
                    GameTitle(title, game.logo, if (narrow) 30.sp else 40.sp)
                    Spacer(Modifier.height(2.dp))
                    GameCapsule { GameActions(game, s, a, Modifier.focusRequester(lead), compactMenu = stacked) }
                }
            }
            header()
            Box {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val labels = tabs.map { t ->
                        when (t) {
                            GameTab.ABOUT -> stringResource(R.string.game_tab_about)
                            GameTab.LAUNCH -> stringResource(R.string.game_tab_launch)
                            GameTab.INSTALL -> stringResource(R.string.game_tab_install)
                        }
                    }
                    GameTabBar(
                        labels, tabs.indexOf(tab), { picked = tabs[it] }, Modifier.onFocusChanged { onStrip = it.hasFocus },
                        focusRequesters = tabs.map { tabFocus.getValue(it) }, bumpers = tabs.size > 1, compact = stacked,
                    )
                    Box(Modifier.fillMaxWidth().onFocusChanged { if (inPanel && !it.hasFocus) dropped++; inPanel = it.hasFocus }.testTag("game-tab-${tab.name.lowercase()}")) {
                        when (tab) {
                            GameTab.ABOUT -> Column(Modifier.fillMaxWidth().tabPanel()) { GameResourcesRow(title, game) }
                            GameTab.LAUNCH -> LaunchTab(game, s, a, hopRail)
                            GameTab.INSTALL -> InstallTab(game)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameStatusLine(game: Library.SteamGame) {
    val colors = MaterialTheme.colorScheme
    val state = listOf(stringResource(R.string.lib_eyebrow_installed), libraryLabel(game))
    val size = game.gameFiles?.let { diskSize(it) }
    val line = (state + listOfNotNull(size?.let { formatBytes(it) })).filter { it.isNotBlank() }.joinToString(" · ").replaceFirstChar { it.uppercase() }
    val muted = colors.onBackground.copy(alpha = 0.78f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(line, fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun GameCapsule(actions: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(6.dp),
    ) {
        CompositionLocalProvider(LocalPillButtons provides true) { Box(Modifier.weight(1f)) { actions() } }
    }
}

@Composable
private fun GameTabBar(
    tabs: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, focusRequesters: List<FocusRequester>? = null,
    bumpers: Boolean = true, compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(modifier.fillMaxWidth().glass(CircleShape).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bumpers) BumperKey("LB", stringResource(R.string.widgets_prev_tab)) { onSelect((selected + tabs.size - 1) % tabs.size) }
        tabs.forEachIndexed { i, label ->
            val on = i == selected
            val src = remember { MutableInteractionSource() }
            val hot = rememberHot(src)
            val pick = { onSelect(i) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f)
                    .testTag("tab-$i")
                    .then(focusRequesters?.getOrNull(i)?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .clip(CircleShape)
                    .background(if (on) pal.signal.copy(alpha = 0.3f) else if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
                    .glideBorder(hot, CircleShape, pal.signal, if (on) pal.signal.copy(alpha = 0.5f) else Color.Transparent)
                    .hoverable(src).selectable(selected = on, interactionSource = src, indication = null, role = Role.Tab, onClick = pick)
                    .controllerConfirm(onClick = pick)
                    .padding(horizontal = 6.dp, vertical = if (compact) 8.dp else 10.dp),
            ) {
                Text(
                    label, fontSize = if (compact) 13.sp else 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on || hot) colors.onBackground else colors.onSurfaceVariant,
                )
            }
        }
        if (bumpers) BumperKey("RB", stringResource(R.string.widgets_next_tab)) { onSelect((selected + 1) % tabs.size) }
    }
}

@Composable
private fun GameTitle(title: String, logo: Any?, titleSize: TextUnit) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) AsyncImage(
        model = logo, contentDescription = title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        onError = { failed = true },
        modifier = Modifier.padding(vertical = 4.dp).height((titleSize.value * 1.5f).dp).widthIn(max = (titleSize.value * 7f).dp).clip(RoundedCornerShape(10.dp)).testTag("game-logo"),
    ) else Text(
        title, fontSize = titleSize, lineHeight = titleSize * 1.12f, fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 24f)),
    )
}

@Composable
private fun GameActions(game: Library.SteamGame, s: FrontEndState, a: FrontEndActions, leadOn: Modifier, compactMenu: Boolean) {
    Actions {
        if (s.shortcutPicker) {
            PrimaryButton(stringResource(R.string.game_shortcut_choose), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn) { a.onSteamGame(game) }
            FileButtons(game, a)
        } else {
            val preparing = game.gameIdString in s.preparingGames
            val branches = com.droiddeck.launcher.session.SteamBranch.offered(game.appId)
            PrimaryButton(
                stringResource(if (preparing) R.string.common_starting else if (branches) R.string.lib_play_beta else R.string.lib_play),
                enabled = !s.busy && !preparing, main = true, icon = Icons.Filled.PlayArrow, modifier = leadOn,
            ) { a.onSteamGame(game) }
            if (branches && !preparing) {
                SecondaryButton(stringResource(R.string.lib_play_stable), enabled = !s.busy, compact = true,
                    modifier = Modifier.testTag("play-stable:${game.gameIdString}")) { a.onSteamGameStable(game) }
            }
            if (compactMenu) GameMoreMenu(game, a) else {
                FileButtons(game, a)
                GameShortcutMenu(game, a)
                EditArtButton(game)
            }
        }
        BusyChip(s)
    }
}

@Composable
private fun FileButtons(game: Library.SteamGame, a: FrontEndActions) {
    game.gameFiles?.takeIf { it.isDirectory }?.let { dir -> SecondaryButton(stringResource(R.string.games_files), compact = true) { a.onBrowseFiles(dir) } }
    game.protonPrefix?.takeIf { it.isDirectory }?.let { dir -> SecondaryButton(stringResource(R.string.games_prefix), compact = true) { a.onBrowseFiles(dir) } }
}

@Composable
private fun GameShortcutMenu(g: Library.SteamGame, a: FrontEndActions) {
    var open by remember(g.gameId) { mutableStateOf(false) }
    Box {
        SecondaryButton(stringResource(R.string.game_shortcut), compact = true) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = stringResource(R.string.game_shortcut)) { first ->
            MenuItem(stringResource(R.string.game_shortcut_add), checked = false, focusRequester = first) { open = false; a.onGameShortcut(g) }
            MenuItem(stringResource(R.string.game_file_export), checked = false) { open = false; a.onExportGameFile(g) }
            MenuItem(stringResource(R.string.game_link_copy), checked = false) { open = false; a.onCopyGameLink(g) }
        }
    }
}

private fun editArt(g: Library.SteamGame): () -> Unit = { GamesDialogState.steamArt = g }

@Composable
private fun EditArtButton(g: Library.SteamGame) {
    val open = editArt(g)
    IconAction(Icons.Outlined.Edit, stringResource(R.string.game_edit_art), "game:edit", onClick = open)
}

@Composable
private fun GameMoreMenu(g: Library.SteamGame, a: FrontEndActions) {
    var open by remember(g.gameId) { mutableStateOf(false) }
    val edit = editArt(g)
    Box {
        SecondaryButton(stringResource(R.string.game_more)) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = stringResource(R.string.game_more), compact = true) { first ->
            var lead: FocusRequester? = first
            fun take(): FocusRequester? = lead.also { lead = null }
            g.gameFiles?.takeIf { it.isDirectory }?.let { dir -> MenuItem(stringResource(R.string.games_files), checked = false, focusRequester = take()) { open = false; a.onBrowseFiles(dir) } }
            g.protonPrefix?.takeIf { it.isDirectory }?.let { dir -> MenuItem(stringResource(R.string.games_prefix), checked = false, focusRequester = take()) { open = false; a.onBrowseFiles(dir) } }
            MenuItem(stringResource(R.string.game_shortcut_add), checked = false, focusRequester = take()) { open = false; a.onGameShortcut(g) }
            MenuItem(stringResource(R.string.game_file_export), checked = false) { open = false; a.onExportGameFile(g) }
            MenuItem(stringResource(R.string.game_link_copy), checked = false) { open = false; a.onCopyGameLink(g) }
            MenuItem(stringResource(R.string.game_edit_art), checked = false) { open = false; edit() }
        }
    }
}

@Composable
internal fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, id: String, danger: Boolean = false, enabled: Boolean = true, frosted: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pill = LocalPillButtons.current
    val shape = if (pill) CircleShape else Shape12
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val accent = if (danger) colors.error else pal.signal
    val fill = when {
        hot -> accent.copy(alpha = if (danger) 0.22f else 0.14f)
        pill -> Color.White.copy(alpha = 0.09f)
        danger -> Color.Black.copy(alpha = 0.34f)
        else -> Color.White.copy(alpha = 0.03f)
    }
    val button: @Composable () -> Unit = {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.paneItem(id).downToFirstTile().size(if (pill) 40.dp else 44.dp).clip(shape)
                .background(fill)
                .glideBorder(hot, shape, accent, if (pill) Color.White.copy(alpha = 0.14f) else pal.line2)
                .alpha(if (enabled) 1f else 0.45f)
                .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, enabled = enabled, onClick = onClick)
                .controllerConfirm(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label },
        ) {
            Icon(icon, contentDescription = null, tint = if (danger && hot) colors.error else if (danger && !pill) Color.White else colors.onBackground, modifier = Modifier.size(18.dp))
        }
    }
    if (frosted) Box(Modifier.glass(CircleShape)) { button() } else button()
}

@Composable
private fun RemoveButton(g: Library.SteamGame, a: FrontEndActions) {
    if (g.gameFiles?.isDirectory != true) return
    var asking by remember(g.gameId) { mutableStateOf(false) }
    val label = stringResource(R.string.common_uninstall)
    IconAction(Icons.Outlined.Delete, label, "game:remove", danger = true, frosted = true) { asking = true }
    if (asking) ConfirmDialog(
        title = stringResource(R.string.lib_uninstall_title, g.name),
        text = stringResource(R.string.games_uninstall_body),
        confirm = label,
        onConfirm = { a.onGameRemoved(g) },
        onDismiss = { asking = false },
    )
}

@Composable
internal fun Modifier.tabPanel(): Modifier {
    if (LocalGlass.current != null) return glass(RoundedCornerShape(22.dp), 0.22f).padding(horizontal = 18.dp, vertical = 14.dp)
    val shape = RoundedCornerShape(16.dp)
    return clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line, shape).padding(horizontal = 16.dp, vertical = 14.dp)
}

@Composable
private fun LaunchTab(game: Library.SteamGame, s: FrontEndState, a: FrontEndActions, hopRail: String?) {
    val host = rememberMenuHost()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        LaunchSettings(s, a, host, game, hopRail ?: GameRef.key(game))
        if (canEditSteamLaunchOptions(game, s.shortcutPicker)) {
            SteamLaunchOptionsPanel(game.appId)
        }
    }
}

internal fun canEditSteamLaunchOptions(game: Library.SteamGame, shortcutPicker: Boolean): Boolean = !shortcutPicker

@Composable
private fun InstallTab(game: Library.SteamGame) {
    val folder = game.gameFiles
    val size = folder?.let { diskSize(it) }
    val rows = buildList {
        add(stringResource(R.string.game_install_location) to libraryLabel(game).ifBlank { stringResource(R.string.games_internal) })
        if (folder != null) {
            add(stringResource(R.string.game_install_folder) to folder.path)
            add(stringResource(R.string.game_install_size) to (size?.let { formatBytes(it) } ?: stringResource(R.string.cloud_pending)))
        }
        add(stringResource(R.string.game_install_played) to (playedSpan(game.lastPlayed)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.games_never_played)))
    }.filter { it.second.isNotBlank() }
    Column(Modifier.fillMaxWidth().tabPanel(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((label, value) in rows) FactRow(label, value)
    }
}

@Composable
internal fun Modifier.readable(): Modifier {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(8.dp)
    return clip(shape).glideBorder(hot, shape, pal.signal, restColor = Color.Transparent).focusable(interactionSource = src)
}

@Composable
internal fun FactRow(label: String, value: String, labelWidth: androidx.compose.ui.unit.Dp = 150.dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().readable().padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(labelWidth))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun diskSize(folder: File): Long? {
    LaunchedEffect(folder.path) { FolderSizes.request(folder) }
    return FolderSizes.known[folder.path]
}

internal object FolderSizes {
    val known = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun request(folder: File) {
        if (!asked.add(folder.path)) return
        Thread({
            var total = 0L
            val walked = runCatching {
                java.nio.file.Files.walkFileTree(folder.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
                })
            }.isSuccess
            if (walked) main.post { known[folder.path] = total } else asked.remove(folder.path)
        }, "folder-size").start()
    }
}
