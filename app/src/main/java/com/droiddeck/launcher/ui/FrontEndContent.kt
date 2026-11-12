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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import java.io.File


@Composable
internal fun Pane(
    s: FrontEndState, selected: String, a: FrontEndActions, page: (@Composable () -> Unit)?, modifier: Modifier,
    onSelect: (String) -> Unit,
    onOpenDeveloperOptions: () -> Unit, onRequestWirelessAdb: (Boolean) -> Unit, onPairWirelessDebugging: () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
            if (page != null && s.pageKey != null) page()
            else Content(s, selected, a, Modifier.fillMaxSize(), onSelect, onOpenDeveloperOptions, onRequestWirelessAdb, onPairWirelessDebugging)
        }
    }
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
        SteamHome(s, a, onSelect, modifier)
        return
    }
    if (selected == "games" || selected.startsWith("app:")) {
        LibraryPage(s, a, selected, onSelect, modifier)
        return
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = padH, vertical = padV)) {
        Note(stringResource(R.string.content_page_gone))
    }
}
