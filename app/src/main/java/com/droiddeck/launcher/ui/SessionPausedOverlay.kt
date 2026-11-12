package com.droiddeck.launcher.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class PausedFrame(val image: ImageBitmap, val bounds: android.graphics.Rect)

private const val CARD_SCALE = 0.82f
private const val FRAME_WAIT_MS = 600L

private fun tone(saturation: Float, brightness: Float): ColorMatrix =
    ColorMatrix().apply {
        setToSaturation(saturation)
        timesAssign(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) })
    }

@Composable
fun SessionPausedOverlay(
    suspended: Boolean,
    engaged: Boolean,
    failures: Int,
    frame: PausedFrame?,
    title: String,
    onResume: () -> Unit,
    onFrameNeeded: () -> Unit,
    onHidden: () -> Unit,
) {
    val currentFrame by rememberUpdatedState(frame)
    var shown by remember { mutableStateOf(suspended) }

    LaunchedEffect(suspended) {
        if (suspended) {
            if (!shown) {
                if (currentFrame == null) {
                    onFrameNeeded()
                    withTimeoutOrNull(FRAME_WAIT_MS) { snapshotFlow { currentFrame }.first { it != null } }
                }
                shown = true
            }
        } else if (shown) {
            shown = false
            onHidden()
        }
    }

    if (!shown) return

    val idle = suspended && !engaged
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    val resumeLabel = stringResource(R.string.resume_session)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = title }
            .focusRequester(focus)
            .controllerConfirm(enabled = idle, onClick = onResume)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = resumeLabel,
            ) { if (idle) onResume() },
    ) {
        if (frame != null) {
            PausedCard(frame)
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }
        ResumeButton(busy = !idle, focused = focused)
    }
}

@Composable
private fun BoxScope.PausedCard(frame: PausedFrame) {
    val density = LocalDensity.current
    val bounds = frame.bounds
    val ambient = remember { ColorFilter.colorMatrix(tone(0.1f, 0.3f)) }
    val card = remember { ColorFilter.colorMatrix(tone(0.08f, 0.55f)) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            frame.image, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = ambient,
            modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(32.dp) else Modifier),
        )
    }
    Image(
        frame.image, contentDescription = null, contentScale = ContentScale.FillBounds, colorFilter = card,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset(bounds.left, bounds.top) }
            .size(with(density) { bounds.width().toDp() }, with(density) { bounds.height().toDp() })
            .graphicsLayer {
                scaleX = CARD_SCALE
                scaleY = CARD_SCALE
                shape = RoundedCornerShape(20.dp)
                clip = true
            },
    )
}

@Composable
private fun ResumeButton(busy: Boolean, focused: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .glideBorder(focused, CircleShape, Color.White, hotFillAlpha = 0f),
    ) {
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(56.dp))
        else Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
    }
}
