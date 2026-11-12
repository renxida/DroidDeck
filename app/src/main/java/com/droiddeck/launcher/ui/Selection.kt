package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun BoxScope.SelectionMark(
    at: Rect?,
    tint: State<Color>,
    corner: Dp? = null,
    fill: Float = 0.16f,
    outline: Float = 0.7f,
) {
    if (at == null || LocalInputModeManager.current.inputMode == InputMode.Keyboard) return
    Canvas(Modifier.matchParentSize()) {
        val round = minOf(at.width, at.height) / 2f
        val r = CornerRadius(corner?.let { minOf(it.toPx(), round) } ?: round)
        drawRoundRect(tint.value.copy(alpha = fill), at.topLeft, at.size, r)
        if (outline > 0f) {
            val w = 1.dp.toPx()
            val inset = w / 2f
            drawRoundRect(
                tint.value.copy(alpha = outline), at.topLeft + Offset(inset, inset), Size(at.width - w, at.height - w),
                CornerRadius((r.x - inset).coerceAtLeast(0f)), style = Stroke(w),
            )
        }
    }
}

internal fun Modifier.shownBar(on: Boolean, color: Color, vertical: Boolean = false): Modifier =
    if (!on) this else drawWithContent {
        drawContent()
        val t = 3.dp.toPx()
        if (vertical) {
            val l = size.height * 0.5f
            drawRect(color, Offset(0f, (size.height - l) / 2f), Size(t, l))
        } else {
            val w = size.width * 0.36f
            drawRect(color, Offset((size.width - w) / 2f, size.height - t), Size(w, t))
        }
    }
