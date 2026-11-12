package com.droiddeck.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

internal val LocalDetailFloor = compositionLocalOf<() -> Float> { { Float.MAX_VALUE } }

internal class DetailInset(val horizontal: Dp, val top: Dp)
internal val LocalDetailInset = compositionLocalOf { DetailInset(0.dp, 0.dp) }

private fun Modifier.bleed(horizontal: Dp, top: Dp): Modifier =
    if (horizontal == 0.dp && top == 0.dp) this else layout { measurable, constraints ->
        val h = horizontal.roundToPx()
        val t = top.roundToPx()
        val placeable = measurable.measure(
            Constraints.fixed(constraints.maxWidth + h * 2, if (constraints.hasBoundedHeight) constraints.maxHeight + t else constraints.minHeight),
        )
        layout(constraints.maxWidth, placeable.height - t) { placeable.place(-h, -t) }
    }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CardDetailHost(
    open: String?,
    detailScroll: ScrollState,
    gridScrolled: () -> Boolean,
    onDetailFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    bleedHorizontal: Dp = 0.dp,
    bleedTop: Dp = 0.dp,
    grid: @Composable () -> Unit,
    detail: @Composable (key: String) -> Unit,
) {
    val ff = LocalFrontFocus.current
    val density = LocalDensity.current
    val shown = open
    var viewportAt by remember { mutableStateOf(Offset.Zero) }
    var viewportH by remember { mutableStateOf(0) }
    LaunchedEffect(shown) { detailScroll.scrollTo(0) }
    val covering = shown != null
    val coveringNow = rememberUpdatedState(covering)
    Box(
        modifier.bleed(bleedHorizontal, bleedTop).clipToBounds()
            .onGloballyPositioned { viewportAt = it.positionInRoot(); viewportH = it.size.height },
    ) {
        CompositionLocalProvider(LocalFrontFocus provides if (covering) null else ff) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = bleedHorizontal).padding(top = bleedTop)
                    .graphicsLayer { alpha = if (coveringNow.value) 0f else 1f }
                    .focusProperties { enter = { if (coveringNow.value) FocusRequester.Cancel else FocusRequester.Default } }
                    .focusGroup(),
            ) { grid() }
        }
        shown?.let { key ->
            val body: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }
                        .verticalScroll(detailScroll).padding(bottom = 16.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .onFocusChanged { onDetailFocus(it.hasFocus) }
                            .focusProperties { exit = { if (coveringNow.value && Steps.step == null) FocusRequester.Cancel else FocusRequester.Default } }
                            .focusGroup(),
                    ) {
                        val floor = { viewportAt.y + viewportH - with(density) { 16.dp.toPx() } - detailScroll.value }
                        CompositionLocalProvider(LocalDetailFloor provides floor, LocalDetailInset provides DetailInset(bleedHorizontal, bleedTop + 4.dp)) { detail(key) }
                    }
                }
            }
            body()
        }
    }
}
