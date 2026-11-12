package com.droiddeck.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

internal class StepAsk(
    val anchor: Rect,
    val side: StepSide,
    val accent: Color,
    val pillCorner: androidx.compose.ui.unit.Dp? = null,
    val items: Int = 4,
    val onDismiss: () -> Unit = {},
    val maxWidth: androidx.compose.ui.unit.Dp = 300.dp,
    val handle: @Composable () -> Unit,
    val content: @Composable StepOutScope.() -> Unit,
)
