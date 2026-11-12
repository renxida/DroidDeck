package com.droiddeck.launcher.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

internal class HopOrigin(
    val rail: String,
    val surface: String,
    val tab: String?,
    val control: String,
    val bounds: Rect,
    val back: String,
    val dest: String,
    val page: String,
)

internal const val HOP_SURFACE_GAMES = "games"

internal object Hops {
    var origin by mutableStateOf<HopOrigin?>(null)

    var restore by mutableStateOf<HopOrigin?>(null)

    val sources = HashMap<String, Rect>()

    fun go(origin: HopOrigin, go: () -> Unit) {
        this.origin = origin
        go()
    }

    fun back(close: () -> Unit): Boolean {
        val o = origin ?: return false
        origin = null
        restore = o
        close()
        return true
    }

    fun forget(pageKey: String?) {
        val o = origin ?: return
        if (pageKey != o.page) origin = null
    }
}

internal val LocalShowSection = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

internal fun Modifier.hopSource(key: String): Modifier = onGloballyPositioned { Hops.sources[key] = it.boundsInRoot() }

@Composable
internal fun HopBackChip(onBack: () -> Unit) {
    val o = Hops.origin ?: return
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Text(
        stringResource(R.string.hop_back, o.back), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (hot) colors.onBackground else colors.onSurfaceVariant,
        modifier = Modifier.heightIn(min = 44.dp).padding(vertical = 6.dp).clip(shape)
            .background(pal.signal.copy(alpha = if (hot) 0.16f else 0.08f))
            .glideBorder(hot, shape, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onBack)
            .controllerConfirm(onClick = onBack)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
