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

internal object CardMotion {
    var heroAt by mutableStateOf<Rect?>(null)

    var heroLands by mutableStateOf(false)

    private var card: CardMark? = null
    private var cardAt = 0L

    fun markCard(c: CardMark) {
        card = c
        cardAt = SystemClock.uptimeMillis()
    }

    fun takeCard(key: String): CardMark? = card?.takeIf { it.key == key && SystemClock.uptimeMillis() - cardAt < 1_000 }.also { card = null }
}

internal class CardMark(val key: String, val bounds: Rect, val corner: Float, val art: String?)

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

internal fun Modifier.landingPop(trigger: Int): Modifier = composed {
    val pal = LocalPalette.current
    val scale = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    val seen = remember { intArrayOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger == seen[0]) return@LaunchedEffect
        seen[0] = trigger
        coroutineScope {
            launch { scale.snapTo(1.3f); scale.animateTo(1f, Motion.sp(0.45f, 500f)) }
            launch { ring.snapTo(0f); ring.animateTo(1f, Motion.tw(400)) }
        }
    }
    this.drawBehind {
        if (ring.value < 1f) {
            val rr = max(size.width, size.height) / 2f * lerp(0.4f, 1.6f, ring.value)
            drawCircle(pal.signal.copy(alpha = 0.5f * (1f - ring.value)), rr, center, style = Stroke(1.5.dp.toPx()))
        }
    }.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

internal fun Modifier.pulseRing(trigger: Int, color: Color): Modifier = composed {
    val ring = remember { Animatable(1f) }
    val seen = remember { intArrayOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger == seen[0]) return@LaunchedEffect
        seen[0] = trigger
        if (Motion.scale == 0f) return@LaunchedEffect
        ring.snapTo(0f)
        ring.animateTo(1f, Motion.tw(600))
    }
    drawBehind {
        if (ring.value < 1f) drawCircle(
            color.copy(alpha = 0.6f * (1f - ring.value)), size.minDimension / 2f * lerp(0.4f, 1.6f, ring.value) * 1.4f, center,
            style = Stroke(1.5.dp.toPx()),
        )
    }
}
