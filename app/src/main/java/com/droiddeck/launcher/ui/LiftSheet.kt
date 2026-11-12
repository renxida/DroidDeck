package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.Job
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.droiddeck.launcher.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


internal object SheetTab {
    const val DISPLAY = "display"
    const val CONTROLS = "controls"
    const val AUDIO = "audio"
    const val SESSION = "session"
    const val LIBRARY = "library"
    const val STEAM = "steam"
    const val FILES = "files"
}

private val ChipGap = 6.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SheetChips(
    labels: List<String>,
    selected: Int,
    focus: List<FocusRequester>? = null,
    width: Dp = Dp.Unspecified,
    onPick: (Int) -> Unit,
) {
    val pal = LocalPalette.current
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val tint = rememberUpdatedState(pal.signal)
    val chips = focus ?: remember(labels.size) { List(labels.size) { FocusRequester() } }
    var within by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { if (within) runCatching { chips[selected].requestFocus() } }
    BoxWithConstraints(
        Modifier.fillMaxWidth().onFocusChanged { within = it.hasFocus },
        contentAlignment = Alignment.CenterStart,
    ) {
        val equal = !width.isSpecified && maxWidth / labels.size.coerceAtLeast(1) >= 96.dp
        val least = if (width.isSpecified) maxOf(width, 64.dp) else 64.dp
        val scroll = rememberScrollState()
        Box(if (equal) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll).padding(3.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ChipGap),
                modifier = (if (equal) Modifier.fillMaxWidth() else Modifier)
                    .focusProperties { enter = { chips.getOrNull(selected) ?: FocusRequester.Default } }
                    .focusGroup().selectableGroup(),
            ) {
                labels.forEachIndexed { i, label ->
                    val m = (if (equal) Modifier.weight(1f) else Modifier.widthIn(min = least))
                        .onGloballyPositioned { bounds[i] = it.boundsInParent() }
                        .focusRequester(chips[i])
                    SheetChip(label, i == selected, m.onFocusChanged { if (it.isFocused && i != selected) onPick(i) }) { onPick(i) }
                }
            }
            SelectionMark(bounds[selected], tint)
        }
    }
}

@Composable
private fun SheetChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    val under = on && LocalInputModeManager.current.inputMode != InputMode.Keyboard
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (under) Color.Transparent else colors.surfaceVariant.copy(alpha = 0.5f))
            .shownBar(on, pal.signal)
            .glideBorder(hot, shape, pal.signal, if (under) Color.Transparent else pal.line)
            .hoverable(src).selectable(selected = on, interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on || hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LinkRow(title: String, value: String, link: String, id: String, arrow: String = "↗", onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().paneItem(id).hopSource(id).heightIn(min = 56.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            Text(value, fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$link $arrow", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1)
    }
}
