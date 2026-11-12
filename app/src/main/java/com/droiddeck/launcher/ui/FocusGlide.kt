package com.droiddeck.launcher.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun Modifier.glideBorder(
    hot: Boolean,
    shape: Shape,
    hotColor: Color,
    restColor: Color = Color.Transparent,
    hotWidth: Dp = 2.dp,
    restWidth: Dp = 1.dp,
    @Suppress("UNUSED_PARAMETER") hotFillAlpha: Float = 0f,
): Modifier = when {
    hot -> border(hotWidth, hotColor, shape)
    restColor.alpha > 0f -> border(restWidth, restColor, shape)
    else -> this
}

@Composable
internal fun Modifier.focusGlide(
    shape: Shape = Shape12,
    color: Color = LocalPalette.current.signal,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return glideBorder(focused, shape, color).onFocusChanged { focused = it.isFocused }
}

@Composable
internal fun FocusGlideHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

@Composable
internal fun FocusGlideWindow(content: @Composable (Modifier) -> Unit) {
    content(Modifier)
}
