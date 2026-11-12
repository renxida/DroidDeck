package com.droiddeck.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.store.UserAppsState
import java.io.File


@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun Pane(
    s: FrontEndState, selected: String, a: FrontEndActions, page: (@Composable () -> Unit)?, modifier: Modifier,
    onSelect: (String) -> Unit,
    onOpenDeveloperOptions: () -> Unit, onRequestWirelessAdb: (Boolean) -> Unit, onPairWirelessDebugging: () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
      CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
        val backdropArt: File? = when {
            s.pageKey != null && page != null -> null
            selected.startsWith("app:") -> s.steamGames.firstOrNull { "app:${it.appId}" == selected }?.art
            selected == "games" -> s.steamGames.maxByOrNull { it.lastPlayed }?.art
            else -> null
        }
        Backdrop(backdropArt)
        val pal = LocalPalette.current
        val pages = remember { HashMap<String, @Composable () -> Unit>() }
        val origins = remember { HashMap<String, Origin>() }
        val paneAt = remember { arrayOf(Offset.Zero) }
        val livePage by rememberUpdatedState(s.pageKey)
        val railContent = if (selected.startsWith("app:")) "games" else selected
        val target = if (page != null && s.pageKey != null) s.pageKey else railContent
        val gameShown = remember { arrayOf("games") }
        if (selected == "games" || selected.startsWith("app:")) gameShown[0] = selected
        val before = remember { arrayOf(target) }
        if (page != null && s.pageKey != null) {
            pages[s.pageKey] = page
            if (s.pageKey !in origins) PageOrigin.take()?.let { o ->
                origins[s.pageKey] = inPane(o.translate(-paneAt[0])).let { if (it.owner == null) it.ownedBy(railContent) else it }
            }
        } else if (target != before[0] && target !in origins) {
            PageOrigin.take()?.let { o -> origins[target] = inPane(o.translate(-paneAt[0])).let { if (it.owner == null) it.ownedBy(before[0]) else it } }
        }
        before[0] = target
        val targetNow by rememberUpdatedState(target)
        val leave = if (Hops.linking) PageLeave.Link else PageLeave.Ordinary
        LaunchedEffect(target) {
            Hops.linking = false
            PageSwipe.direction = 0
        }
        AnimatedContent(
            targetState = target,
            modifier = Modifier.onGloballyPositioned { paneAt[0] = it.positionInRoot() },
            transitionSpec = {
                val swipe = PageSwipe.direction
                when {
                    swipe != 0 -> (fadeIn(Motion.tw(260, 40)) + slideInHorizontally(Motion.tw(360)) { it * swipe / 8 })
                        .togetherWith(fadeOut(Motion.tw(160)) + slideOutHorizontally(Motion.tw(220)) { -it * swipe / 8 })
                        .apply { targetContentZIndex = 1f }
                    targetState in origins -> (fadeIn(Motion.tw(120)))
                        .togetherWith(fadeOut(Motion.tw(320, 80)) + scaleOut(Motion.tw(480), targetScale = 0.95f))
                        .apply { targetContentZIndex = 1f }
                    initialState in origins && pageExit(origins[initialState]?.owner, targetState, leave) == PageExit.Drain ->
                        (fadeIn(Motion.tw(300, 100)) + scaleIn(Motion.tw(460, 40), initialScale = 0.95f))
                            .togetherWith(ExitTransition.None)
                            .apply { targetContentZIndex = -1f }
                    initialState in origins -> (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                        .togetherWith(fadeOut(Motion.tw(170)) + scaleOut(Motion.tw(170), targetScale = 0.95f))
                        .apply { targetContentZIndex = 1f }
                    else -> (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                        .togetherWith(fadeOut(Motion.tw(170)) + slideOutVertically(Motion.tw(170)) { -it / 40 })
                        .apply { targetContentZIndex = 1f }
                }
            },
            label = "pane",
        ) { key ->
            val drawn = (if (page != null && key == s.pageKey) page else pages[key])
                ?: { Content(s, if (key == "games") gameShown[0] else key, a, Modifier.fillMaxSize(), onSelect, onOpenDeveloperOptions, onRequestWirelessAdb, onPairWirelessDebugging) }
            val hopSink by androidx.compose.animation.core.animateFloatAsState(
                if (Hops.sinking == key) 1f else 0f, Motion.tw(260), label = "hopSink",
            )
            val shown: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().graphicsLayer { val k = 1f - 0.05f * hopSink; scaleX = k; scaleY = k; alpha = 1f - 0.35f * hopSink }) { drawn() }
            }
            run {
                DisposableEffect(key) { onDispose { if (key != livePage && key != targetNow) { pages.remove(key); origins.remove(key) } } }
                val from = origins[key]
                if (from == null) shown()
                else {
                    val art = when (val m = from.art) {
                        null -> null
                        is Int -> androidx.compose.ui.res.painterResource(m)
                        else -> coil.compose.rememberAsyncImagePainter(m)
                    }
                    val leaving = transition.targetState == EnterExitState.PostExit
                    val drains = remember(leaving) { !leaving || pageExit(from.owner, target, leave) == PageExit.Drain }
                    transition.animateFloat(
                        transitionSpec = { if (targetState == EnterExitState.PostExit && drains) Motion.tw(PAGE_RETURN_MS) else snap() },
                        label = "pageReturn",
                    ) { if (it == EnterExitState.PostExit) 1f else 0f }
                    PageFlood(from, leaving = leaving, art = art, sinks = !drains) { shown() }
                }
            }
        }
      }
    }
}

internal fun inPane(o: Origin): Origin =
    if (o.bounds.right > 0f) o else o.translate(Offset(-o.bounds.right + o.bounds.width / 2f, 0f))

@Composable
private fun Backdrop(art: File?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Crossfade(targetState = art, animationSpec = Motion.tw(700), label = "backdrop") { a ->
        if (a != null && Build.VERSION.SDK_INT >= 31) {
            Box(modifier = Modifier.fillMaxSize().alpha(0.26f).blur(70.dp)) {
                AsyncImage(model = a, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(pal.signal.copy(alpha = 0.07f), Color.Transparent), center = Offset.Zero, radius = 1400f)))
        }
    }
    Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.35f)))))
}

@Composable
private fun Content(
    s: FrontEndState, selected: String, a: FrontEndActions, modifier: Modifier,
    onSelect: (String) -> Unit,
    onOpenDeveloperOptions: () -> Unit, onRequestWirelessAdb: (Boolean) -> Unit, onPairWirelessDebugging: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 16.dp else 22.dp
    val padV = if (narrow) 12.dp else 18.dp
    if (selected == "setup") {
        Column(modifier = modifier.padding(horizontal = padH).padding(top = padV)) {
            SetupPanel(s, a, onOpenDeveloperOptions, onRequestWirelessAdb, onPairWirelessDebugging)
        }
        return
    }
    if (selected == "steam") {
        SteamHome(s, a, modifier)
        return
    }
    if (selected == "games" || selected.startsWith("app:")) {
        LibraryPage(s, a, selected, onSelect, modifier)
        return
    }
    if (selected == "desktop") {
        DesktopPage(s, a, modifier, onSelect)
        return
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = padH, vertical = padV)) {
        when {
            selected.startsWith("user:") -> {
                val app = UserAppsState.items.firstOrNull { "user:${it.key}" == selected }
                if (app == null) Note(stringResource(R.string.user_apps_gone)) else UserAppPage(app, s, a, onSelect)
            }
            else -> Note(stringResource(R.string.content_page_gone))
        }
    }
}

@Composable
private fun UserAppIcon(app: UserApps.App, size: Int) {
    val shape = RoundedCornerShape((size / 5).dp)
    if (app.icon != null) AsyncImage(
        model = app.icon, contentDescription = app.name, contentScale = ContentScale.Fit,
        modifier = Modifier.size(size.dp).clip(shape),
    )
    else Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
private fun UserAppsProgress() {
    val colors = MaterialTheme.colorScheme
    val working = UserAppsState.working
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (working != null) {
            val stage = UserAppsState.stage ?: stringResource(R.string.user_apps_starting)
            val percent = UserAppsState.percent
            Text(
                if (percent >= 0) stringResource(R.string.user_apps_progress_percent, working, stage, percent) else stringResource(R.string.user_apps_progress, working, stage),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        } else UserAppsState.lastError?.let { Note(it) }
    }
}

@Composable
private fun UserAppPage(app: UserApps.App, s: FrontEndState, a: FrontEndActions, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    var editing by rememberSaveable(app.key) { mutableStateOf(false) }
    Rise(1) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        ) {
            UserAppIcon(app, 52)
            Column {
                Text(app.name, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text(stringResource(R.string.user_apps_kind_script), fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
        }
    }
    Rise(3) {
        Actions {
            PrimaryButton(stringResource(R.string.user_apps_open, app.name), enabled = !s.busy, main = true) { a.onUserApp(app) }
            BusyChip(s)
            SecondaryButton(stringResource(R.string.user_apps_edit), enabled = UserAppsState.working == null) { editing = true }
            RemoveAppButton(app, enabled = !s.busy && !s.sessionRunning && UserAppsState.working == null) {
                UserAppsState.remove(ctx, app); onSelect("desktop")
            }
            if (s.sessionRunning) ActionChip(stringResource(R.string.user_apps_stop_session), ok = false)
        }
    }
    if (UserAppsState.working != null || UserAppsState.lastError != null) Rise(4) { UserAppsProgress() }
    if (editing) EditAppDialog(app, onDismiss = { editing = false }) { name, icon, fex -> UserAppsState.edit(ctx, app, name, icon, fex) }
    val detail = app.detail
    if (detail != null) Rise(4) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Note(stringResource(if (app.copied) R.string.user_apps_script_copied else R.string.user_apps_script_linked, detail))
        }
    }
}

@Composable
private fun RemoveAppButton(app: UserApps.App, enabled: Boolean, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.user_apps_remove)
    val title = stringResource(R.string.flathub_remove_title, app.name)
    val keep = stringResource(R.string.flathub_keep)
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SecondaryButton(label, enabled = enabled) {
            val from = at ?: return@SecondaryButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Right, accent = colors.error, pillCorner = 12.dp, items = 2,
                handle = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1) },
            ) {
                StepTitle(title)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.stepItem(1)) {
                    StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(label, danger = true, enabled = open) { Steps.fold(); onRemove() }
                }
            })
        }
    }
}
