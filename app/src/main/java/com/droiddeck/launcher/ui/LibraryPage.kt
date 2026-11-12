package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue

internal class LibraryEntry(val key: String, val source: String, val title: String, val game: Library.SteamGame)

internal fun librarySource(g: Library.SteamGame): String = if (g.library == Library.ADDED) Library.ADDED else SOURCE_STEAM

internal fun libraryEntries(games: List<Library.SteamGame>): List<LibraryEntry> =
    games.map { LibraryEntry(GameRef.key(it), librarySource(it), it.name, it) }.distinctBy { it.key }

internal const val LIBRARY_ALL = "all"
internal fun librarySources(entries: List<LibraryEntry>): List<String> =
    listOf(SOURCE_STEAM, Library.ADDED).filter { source -> entries.any { it.source == source } }

internal object LibraryDisplay {
    val scales = listOf(0.75f, 0.875f, 1f, 1.2f, 1.45f)
    var level by mutableIntStateOf(2)
        private set
    var title by mutableStateOf(true)
        private set
    var size by mutableStateOf(true)
        private set

    fun load(context: android.content.Context) {
        level = SessionPrefs.libraryCardLevel(context).coerceIn(0, scales.lastIndex)
        title = SessionPrefs.libraryShowTitle(context)
        size = SessionPrefs.libraryShowSize(context)
    }

    fun step(context: android.content.Context, by: Int) {
        level = (level + by).coerceIn(0, scales.lastIndex)
        SessionPrefs.setLibraryCardLevel(context, level)
    }

    fun toggleTitle(context: android.content.Context) {
        title = !title
        SessionPrefs.setLibraryShowTitle(context, title)
    }

    fun toggleSize(context: android.content.Context) {
        size = !size
        SessionPrefs.setLibraryShowSize(context, size)
    }
}

internal object LibraryView {
    var source by mutableStateOf(LIBRARY_ALL)
    var query by mutableStateOf("")
    var focusFirst = false
}

@Composable
internal fun LibraryPage(s: FrontEndState, a: FrontEndActions, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    val ff = LocalFrontFocus.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    remember { LibraryDisplay.load(ctx) }
    val all = remember(s.steamGames) { libraryEntries(s.steamGames) }
    val sources = librarySources(all)
    val source = LibraryView.source.takeIf { it == LIBRARY_ALL || it in sources } ?: LIBRARY_ALL
    val q = LibraryView.query.trim().lowercase()
    val candidates = all.filter { (source == LIBRARY_ALL || it.source == source) && (q.isEmpty() || it.title.lowercase().contains(q)) }
    val sort = s.storesSort
    val sizeSort = sort == "sizeAsc" || sort == "sizeDesc"
    fun sizeOf(e: LibraryEntry): Long? = e.game.gameFiles.let { if (it == null) 0L else FolderSizes.known[it.path] }
    LaunchedEffect(sizeSort, candidates.map { it.key }) {
        if (!sizeSort) return@LaunchedEffect
        for (e in candidates) e.game.gameFiles?.let { FolderSizes.request(it) }
    }
    var sizesWaited by remember(sizeSort) { mutableStateOf(false) }
    LaunchedEffect(sizeSort) { if (sizeSort) { kotlinx.coroutines.delay(8_000); sizesWaited = true } }
    val sizesPending = sizeSort && !sizesWaited && candidates.any { sizeOf(it) == null }
    val sorted = sortGames(
        candidates, sort, title = { it.title },
        lastPlayed = { it.game.lastPlayed.coerceAtLeast(0L) },
        installedAt = { it.game.installedAt.coerceAtLeast(0L) },
        size = { e -> sizeOf(e) ?: 0L },
    )
    var stable by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    val visible = if (sizesPending) retainOrder(candidates, stable) { it.key } else sorted
    SideEffect { if (!sizesPending) stable = sorted }

    val open = selected.takeIf { it.startsWith("app:") }
    val back = { onSelect("games") }
    BackHandler(enabled = open == null && LibraryView.query.isNotEmpty()) { LibraryView.query = "" }
    BackHandler(enabled = open != null) { back() }
    val lead = remember { FocusRequester() }
    var detailFocused by remember { mutableStateOf(false) }
    var lastOpened by remember { mutableStateOf<String?>(null) }
    val wasOpen = remember { booleanArrayOf(false) }
    val backLabel = stringResource(R.string.rail_games)
    LaunchedEffect(open) {
        val closing = open == null && wasOpen[0]
        wasOpen[0] = open != null
        if (open != null) lastOpened = open
        else if (!closing) return@LaunchedEffect
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard || ff == null) return@LaunchedEffect
        repeat(60) { if (!HeldKeys.confirm) return@repeat; androidx.compose.runtime.withFrameNanos { } }
        if (open != null) {
            val backId = "back:$backLabel"
            repeat(45) { n ->
                androidx.compose.runtime.withFrameNanos { }
                if (detailFocused) return@LaunchedEffect
                val req = if (n < 20) lead else ff.items[backId]?.takeIf { (ff.attached[backId] ?: 0) > 0 } ?: lead
                runCatching { req.requestFocus() }
            }
            return@LaunchedEffect
        }
        val id = lastOpened?.let { "card:$it" }
        repeat(45) {
            androidx.compose.runtime.withFrameNanos { }
            if (id != null && ff.last == id) return@LaunchedEffect
            val card = id?.let { ff.items[it] }?.takeIf { (ff.attached[id] ?: 0) > 0 }
            if (card != null) runCatching { card.requestFocus() }
            else if (ff.firstTileAttached > 0 && runCatching { ff.firstTile.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LaunchedEffect(Unit) {
        if (!LibraryView.focusFirst || ff == null) return@LaunchedEffect
        LibraryView.focusFirst = false
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        kotlinx.coroutines.delay(Motion.ms(240).toLong())
        focusWithinFrames({ ff.last?.startsWith("card:") == true }) { if (ff.firstTileAttached > 0) ff.firstTile else FocusRequester.Default }
    }
    if (all.isEmpty() && open == null) {
        LibraryEmpty(s, a, modifier)
        return
    }
    val grid = remember { LazyGridState() }
    val detailScroll = rememberScrollState()
    var cycled by remember { mutableIntStateOf(0) }
    var gridFocused by remember { mutableStateOf(false) }
    LaunchedEffect(source, sort) { grid.scrollToItem(0) }
    LaunchedEffect(cycled) {
        if (cycled == 0 || ff == null || inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        focusWithinFrames({ gridFocused }) {
            if (ff.firstTileAttached > 0) ff.firstTile else ff.items["libchip:$source"] ?: FocusRequester.Default
        }
    }
    CardDetailHost(
        open, detailScroll,
        gridScrolled = { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0 },
        onDetailFocus = { detailFocused = it },
        modifier = modifier.padding(horizontal = if (narrow) 14.dp else 22.dp).padding(top = if (narrow) 10.dp else 14.dp),
        bleedHorizontal = if (narrow) 14.dp else 22.dp, bleedTop = if (narrow) 10.dp else 14.dp,
        grid = {
            LibraryGrid(
                s, a, grid, visible, sources, source, sizesPending,
                onSource = { LibraryView.source = it },
                onCycle = { step ->
                    val ids = listOf(LIBRARY_ALL) + sources
                    LibraryView.source = ids[(ids.indexOf(source).coerceAtLeast(0) + step + ids.size) % ids.size]
                    cycled++
                },
                onOpen = { focusManager.clearFocus(force = true); onSelect(it) },
                searchCleared = open != null,
                onFocus = { gridFocused = it },
            )
        },
    ) { key -> GameDetail(key, s, a, lead, backLabel, hopRail = "games", onBack = back) }
}

@Composable
private fun LibraryEmpty(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val narrow = LocalNarrowPane.current
    Column(modifier = modifier.padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
        Rise(0) {
            PageHeader(stringResource(R.string.rail_games)) {
                if (s.steamGamesX64) ClientTag()
                if (!s.shortcutPicker) AddGameButton { GamesDialogState.adding = true }
            }
        }
        Rise(1) { Note(stringResource(if (s.shortcutPicker && s.shortcutLibraryScanning) R.string.game_shortcut_scanning else R.string.games_empty)) }
        if (!s.shortcutPicker) Rise(2) {
            Actions {
                PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.padding(top = 12.dp).testTag("play-steam"), onClick = a.onPlay)
            }
        }
        Box(Modifier.padding(top = 12.dp)) { GameFileFolderActions(s, a) }
    }
}

@Composable
private fun LibraryGrid(
    s: FrontEndState, a: FrontEndActions, grid: LazyGridState, visible: List<LibraryEntry>,
    sources: List<String>, source: String, sizesPending: Boolean,
    onSource: (String) -> Unit, onCycle: (Int) -> Unit, onOpen: (String) -> Unit, onFocus: (Boolean) -> Unit, searchCleared: Boolean,
) {
    val listMode = s.storesView == "list"
    val capsules = s.storesView == "capsules"
    val tileWidth = (if (capsules) CapsuleWidth else if (listMode) CardWidth else LibraryCardWidth) * LibraryDisplay.scales[LibraryDisplay.level]
    val focus = LocalFrontFocus.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var columns by remember { mutableIntStateOf(1) }
    val headerItems = 2 + (if (sizesPending) 1 else 0)
    val tile: @Composable (LibraryEntry) -> Unit = { e ->
        val first = e.key == visible.firstOrNull()?.key
        val navigation = if (focus == null) Modifier else Modifier.onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val direction = when (key.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
            val index = visible.indexOfFirst { it.key == e.key }
            val next = storeGridNeighbor(index, visible.size, if (listMode) 1 else columns, direction)
            when {
                direction == 0 || index < 0 -> false
                next == null -> direction > 0
                else -> {
                    if (key.action == KeyEvent.ACTION_DOWN) {
                        val target = "card:${visible[next].key}"
                        val targetIndex = headerItems + next
                        val currentIndex = headerItems + index
                        val shown = grid.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                        if (!shown || (focus.attached[target] ?: 0) == 0 ||
                            runCatching { focus.items.getValue(target).requestFocus() }.isFailure) scope.launch {
                            launch { grid.revealRow(currentIndex, targetIndex, direction, with(density) { 10.dp.toPx() }) }
                            focusWithinFrames({ focus.last == target }) { focus.items[target] ?: FocusRequester.Default }
                        }
                    }
                    true
                }
            }
        }
        val picker = s.shortcutPicker
        val choose = if (picker) ({ a.onSteamGame(e.game) }) else null
        if (listMode) LibraryRow(e, first, navigation, choose, onOpen) else LibraryTile(e, capsules, first, navigation, choose, onOpen, showSource = source == LIBRARY_ALL)
    }
    LazyVerticalGrid(
        columns = if (listMode) GridCells.Fixed(1) else GridCells.Adaptive(tileWidth), state = grid,
        modifier = Modifier.fillMaxSize().onFocusChanged { onFocus(it.hasFocus) }.focusGroup().bumpers(onPrevious = { onCycle(-1) }, onNext = { onCycle(1) }).onSizeChanged {
            val gap = with(density) { 10.dp.roundToPx() }
            val minimum = with(density) { (if (listMode) CardWidth * 2 else tileWidth).roundToPx() }
            columns = ((it.width + gap) / (minimum + gap)).coerceAtLeast(1)
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Rise(0) { LibraryHeader(s, a, visible.size, searchCleared) }
        }
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            Rise(1) { LibraryFilters(sources, source, onSource) }
        }
        if (sizesPending) item(key = "size-sort-progress", span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.weight(1f).height(3.dp))
                Text(stringResource(R.string.store_section_loading), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        libraryGrid(visible, R.string.stores_nothing_matches, tile)
    }
}

internal suspend fun LazyGridState.revealRow(currentIndex: Int, targetIndex: Int, direction: Int, gapPx: Float) {
    val current = layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
    if (current != null) {
        val row = current.size.height + gapPx
        val delta = if (direction > 0) {
            current.offset.y + 2 * row - gapPx - (layoutInfo.viewportSize.height - layoutInfo.afterContentPadding)
        } else {
            current.offset.y - row - layoutInfo.beforeContentPadding
        }
        if (direction > 0 && delta > 0f || direction < 0 && delta < 0f) animateScrollBy(delta, tween(durationMillis = 160, easing = FastOutSlowInEasing))
    }
    if (layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) scrollToItem(targetIndex)
}

private fun LazyGridScope.libraryGrid(entries: List<LibraryEntry>, empty: Int, tile: @Composable (LibraryEntry) -> Unit) {
    if (entries.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.padding(vertical = 32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    else items(entries, key = { it.key }, contentType = { "game" }) { tile(it) }
}

@Composable
private fun LibraryHeader(
    s: FrontEndState, a: FrontEndActions, total: Int, searchCleared: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    ModeHeader(
        stringResource(R.string.rail_games), LibraryView.query, { LibraryView.query = it },
        placeholder = stringResource(R.string.stores_search_library),
        clearSearchFocus = searchCleared, inlineWhenStacked = true,
        lead = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 2.dp, end = 4.dp)) {
                Text(stringResource(R.string.rail_games), fontSize = if (narrow) 20.sp else 24.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
                Text(pluralStringResource(R.plurals.mode_added_count, total, total), fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1)
                if (s.steamGamesX64 && !narrow) ClientTag()
            }
        },
    ) {
        DisplayButton()
        SortButton(s, a)
        ViewButton(s, a)
        GameFileFolderActions(s, a)
        if (!s.shortcutPicker) AddGameButton { GamesDialogState.adding = true }
    }
    if (s.steamGamesX64 && narrow) Box(Modifier.padding(top = 4.dp)) { ClientTag() }
}

@Composable
private fun LibraryFilters(sources: List<String>, source: String, onSource: (String) -> Unit) {
    if (sources.size < 2) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).horizontalScroll(rememberScrollState()),
    ) {
        SourceFilterChip(LIBRARY_ALL, stringResource(R.string.stores_tab_all), source == LIBRARY_ALL, dot = null) { onSource(LIBRARY_ALL) }
        for (src in sources) {
            val dot = if (src == SOURCE_STEAM) sourceColours(src).dot else null
            SourceFilterChip(src, if (src == Library.ADDED) stringResource(R.string.library_filter_added) else sourceLabel(src), source == src, dot) { onSource(src) }
        }
    }
}

@Composable
private fun SourceFilterChip(key: String, label: String, on: Boolean, dot: Color?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.paneItem("libchip:$key").testTag("library-source-$key").heightIn(min = 40.dp).clip(shape)
            .background(if (on) pal.signal.copy(alpha = 0.16f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, if (on) pal.signal else pal.line)
            .hoverable(src).selectable(selected = on, interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (dot != null) Box(Modifier.size(9.dp).background(dot, CircleShape))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on || hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1)
    }
}

private fun entryArt(e: LibraryEntry, tall: Boolean): Any? {
    val wide: Any? = e.game.hero
    val portrait: Any? = e.game.art
    return if (tall) portrait ?: wide else wide ?: portrait
}

@Composable
private fun LibraryTile(e: LibraryEntry, capsule: Boolean, first: Boolean, navigation: Modifier, choose: (() -> Unit)?, onOpen: (String) -> Unit, showSource: Boolean) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "tileScale")
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val wide = entryArt(e, tall = false)
    val tall = entryArt(e, tall = true)
    RequestSize(e)
    val open = {
        val art = if (capsule) tall else wide
        placed[0]?.takeIf { it.isAttached }?.let { CardMotion.markCard(CardMark(e.key, it.boundsInRoot(), corner, (art as? java.io.File)?.path ?: art as? String)) }
        onOpen(e.key)
    }
    Column(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { placed[0] = it }
            .then(navigation).paneItem("card:${e.key}").then(if (first) Modifier.firstTile().paneEntry() else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = open)
            .controllerConfirm(onClick = choose ?: open),
    ) {
        LibraryArt(e, wide, tall, hot, capsule, showSource)
    }
}

internal val LibraryCardWidth = 150.dp
private val CardWidth = 112.dp
private val CapsuleWidth = 92.dp

internal fun libraryTileWidth() = LibraryCardWidth * LibraryDisplay.scales[LibraryDisplay.level]

@Composable
private fun LibraryArt(e: LibraryEntry, wide: Any?, tall: Any?, hot: Boolean, capsule: Boolean, showSource: Boolean) {
    ArtCard(
        e.title, wide, tall, hot, capsule,
        badge = if (showSource) ({ SourceChip(e.source, small = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) }) else null,
    ) { LibrarySize(e) }
}

@Composable
internal fun ArtCard(
    title: String, wide: Any?, tall: Any?, hot: Boolean, capsule: Boolean,
    badge: (@Composable BoxScope.() -> Unit)? = null, detail: @Composable () -> Unit,
) {
    val url = if (capsule) tall ?: wide else wide ?: tall
    val art = cardArtRequest(url, tall)
    val ratio = if (capsule) 5f / 7f else 16f / 9f
    var offRatio by remember(url) { mutableStateOf(false) }
    var trim by remember(url) { mutableStateOf(false) }
    val blurs = android.os.Build.VERSION.SDK_INT >= 31
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xFF101318))
            .then(if (url == null) Modifier.background(artBrush(hueOf(title))) else Modifier),
    ) {
        if (art != null && offRatio) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(32.dp))
            Spacer(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (blurs) 0.18f else 0.6f)))
        }
        if (art != null) AsyncImage(
            model = art, contentDescription = null, contentScale = if (trim) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.matchParentSize(),
            onSuccess = { r ->
                val d = r.result.drawable
                if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                    val shape = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                    trim = !capsule && shape > ratio * 1.25f
                    offRatio = !trim && kotlin.math.abs(shape / ratio - 1f) > 0.03f
                }
            },
        )
        badge?.invoke(this)
        val showName = LibraryDisplay.title || hot
        val showDetail = LibraryDisplay.size || hot
        if (showName || showDetail) Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Black.copy(alpha = 0.78f), 1f to Color.Black.copy(alpha = 0.92f)))
                .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 6.dp),
        ) {
            if (showName) Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = if (hot) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
            if (showDetail) detail()
        }
    }
}

@Composable
private fun RequestSize(e: LibraryEntry) {
    LaunchedEffect(e.key) { e.game.gameFiles?.let { FolderSizes.request(it) } }
}

@Composable
private fun LibrarySize(e: LibraryEntry) {
    val size = e.game.gameFiles?.let { FolderSizes.known[it.path] }?.takeIf { it > 0 } ?: return
    Text(formatBytes(size), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
}

@Composable
private fun LibraryRow(e: LibraryEntry, first: Boolean, navigation: Modifier, choose: (() -> Unit)?, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val wide = entryArt(e, tall = false)
    val open = {
        placed[0]?.takeIf { it.isAttached }?.let { CardMotion.markCard(CardMark(e.key, it.boundsInRoot(), corner, (wide as? java.io.File)?.path ?: wide as? String)) }
        onOpen(e.key)
    }
    val size = e.game.gameFiles?.let { diskSize(it) }
    val status = playedSpan(e.game.lastPlayed)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.games_never_played)
    val meta = listOfNotNull(status, size?.let { formatBytes(it) }?.takeIf { it.isNotEmpty() })
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).onGloballyPositioned { placed[0] = it }.then(navigation).paneItem("card:${e.key}")
            .then(if (first) Modifier.firstTile().paneEntry() else Modifier)
            .clip(RoundedCornerShape(10.dp)).glideBorder(hot, RoundedCornerShape(10.dp), pal.signal, pal.line)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = open)
            .controllerConfirm(onClick = choose ?: open)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(artBrush(hueOf(e.title)))) {
            if (wide != null) AsyncImage(model = wide, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceChip(e.source, small = true)
                Text(meta.joinToString("  ·  "), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun HeaderIconButton(description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, key: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("btn:$key").size(32.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
}

@Composable
internal fun Modifier.paneEntry(): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    androidx.compose.runtime.DisposableEffect(ff) {
        ff.primaryAttached++
        onDispose { ff.primaryAttached-- }
    }
    return focusRequester(ff.primary)
}

@Composable
internal fun DisplayButton() {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val title = stringResource(R.string.library_display)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title, Icons.Filled.Visibility, "display") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = 4,
                handle = { Icon(Icons.Filled.Visibility, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    val last = LibraryDisplay.scales.lastIndex
                    StepChoice(
                        stringResource(R.string.library_display_larger), icon = Icons.Filled.Add, enabled = open && LibraryDisplay.level < last,
                        trailing = "${LibraryDisplay.level + 1}/${last + 1}",
                        modifier = Modifier.fillMaxWidth().stepItem(0).focusRequester(first),
                    ) { LibraryDisplay.step(ctx, 1) }
                    StepChoice(
                        stringResource(R.string.library_display_smaller), icon = Icons.Filled.Remove, enabled = open && LibraryDisplay.level > 0,
                        modifier = Modifier.fillMaxWidth().stepItem(1),
                    ) { LibraryDisplay.step(ctx, -1) }
                    StepChoice(
                        stringResource(R.string.library_display_title), selected = LibraryDisplay.title, enabled = open,
                        modifier = Modifier.fillMaxWidth().stepItem(2),
                    ) { LibraryDisplay.toggleTitle(ctx) }
                    StepChoice(
                        stringResource(R.string.library_display_size), selected = LibraryDisplay.size, enabled = open,
                        modifier = Modifier.fillMaxWidth().stepItem(3),
                    ) { LibraryDisplay.toggleSize(ctx) }
                }
            })
        }
    }
}

@Composable
private fun SortButton(s: FrontEndState, a: FrontEndActions) {
    val pal = LocalPalette.current
    val title = stringResource(R.string.stores_sort)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(title, Icons.Filled.Sort, "sort") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = SessionPrefs.STORES_SORTS.size,
                handle = { Icon(Icons.Filled.Sort, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    SessionPrefs.STORES_SORTS.forEachIndexed { i, sort ->
                        val label = when (sort) {
                            "lastPlayed" -> stringResource(R.string.stores_sort_last_played)
                            "recentlyInstalled" -> stringResource(R.string.stores_sort_recently_installed)
                            "titleAsc" -> stringResource(R.string.stores_sort_title_asc)
                            "titleDesc" -> stringResource(R.string.stores_sort_title_desc)
                            "sizeDesc" -> stringResource(R.string.stores_sort_size_desc)
                            else -> stringResource(R.string.stores_sort_size_asc)
                        }
                        StepChoice(
                            label, selected = sort == s.storesSort, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).then(if (sort == s.storesSort) Modifier.focusRequester(first) else Modifier),
                        ) {
                            a.onStoresSort(sort)
                            Steps.fold()
                        }
                    }
                }
            })
        }
    }
}

@Composable
private fun ViewButton(s: FrontEndState, a: FrontEndActions) {
    val views = SessionPrefs.STORES_VIEWS
    val next = views[(views.indexOf(s.storesView) + 1) % views.size]
    HeaderIconButton(
        stringResource(when (next) { "capsules" -> R.string.stores_view_capsules; "list" -> R.string.stores_view_list; else -> R.string.stores_view_cards }),
        when (next) { "capsules" -> Icons.Filled.ViewModule; "list" -> Icons.Filled.ViewList; else -> Icons.Filled.GridView },
        "view",
    ) { a.onStoresView(next) }
}
