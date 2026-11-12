package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

@Composable
internal fun SectionLabel(title: String) {
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp)) {
        Text(headingUppercase(title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(pal.line))
    }
}

@Composable
internal fun ModeHeader(
    title: String, query: String, onQuery: (String) -> Unit, placeholder: String,
    clearSearchFocus: Boolean = false,
    lead: (@Composable () -> Unit)? = null, inlineWhenStacked: Boolean = false, trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    var focusOnOpen by remember { mutableStateOf(false) }
    val open by animateFloatAsState(if (searching) 1f else 0f, Motion.sp(0.75f, 420f), label = "searchOpen")
    val close = {
        searching = false
        focusOnOpen = false
        if (query.isNotEmpty()) onQuery("")
    }
    BackHandler(enabled = searching && !clearSearchFocus) { close() }
    val heading: @Composable () -> Unit = {
        if (lead != null) lead()
        else Text(
            title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
    }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
            val closable = maxWidth >= 80.dp
            val w = androidx.compose.ui.unit.lerp(44.dp, (maxWidth - if (closable) 36.dp else 0.dp).coerceIn(44.dp, 520.dp), open)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(w).graphicsLayer { alpha = (open * 1.6f).coerceAtMost(1f) }) {
                    AdbTextField(
                        value = query, onValueChange = onQuery, label = "", keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                        placeholder = placeholder, compact = true, focusOnShow = focusOnOpen,
                        clearFocus = clearSearchFocus || !searching,
                        onFocusChange = { focused -> if (!focused && query.isEmpty()) { searching = false; focusOnOpen = false } },
                    )
                }
                if (searching && closable) SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = colors.onSurfaceVariant, onClick = close)
            }
        }
    }
    val searchButton: @Composable () -> Unit = {
        SearchIcon(stringResource(R.string.stores_search_open)) { searching = true; focusOnOpen = true }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < NarrowPaneWidth
        val inline = stacked && inlineWhenStacked && maxWidth >= 440.dp
        Column(Modifier.fillMaxWidth()) {
            if (inline && !(searching || open > 0.01f)) Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                heading()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        searchButton()
                        trailing()
                    }
                }
            } else if (stacked) {
                Box(Modifier.padding(vertical = 4.dp)) { heading() }
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 52.dp).padding(vertical = 4.dp),
                ) {
                    searchButton()
                    trailing()
                }
                if (searching || open > 0.01f) searchField(Modifier.fillMaxWidth().padding(bottom = 6.dp))
            } else Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp),
            ) {
                heading()
                if (searching || open > 0.01f) searchField(Modifier.weight(1f))
                else Box(Modifier.weight(1f)) { searchButton() }
                trailing()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
}

@Composable
internal fun SearchIcon(description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("storesearch").size(40.dp).clip(CircleShape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) { Icon(Icons.Filled.Search, description, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
}

internal fun openUrl(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
