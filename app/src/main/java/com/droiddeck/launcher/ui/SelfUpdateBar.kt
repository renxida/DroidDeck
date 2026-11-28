package com.droiddeck.launcher.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.update.SelfUpdate

/**
 * The update line at the foot of the front screen's pane, above the navigation: shown only while there is an update
 * to check, download or install, naming the build it would install. The installed build is in Settings (About).
 */
@Composable
internal fun SelfUpdateBar() {
    val state = SelfUpdate.ui
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val waiting = state.available?.let { VersionLine.build(it.commit, it.commitTimeMs) }
    if (state.phase == SelfUpdate.Phase.IDLE) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.padding(bottom = 10.dp).testTag("self-update").clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = when (state.phase) {
                SelfUpdate.Phase.CHECKING -> stringResource(R.string.self_update_checking)
                SelfUpdate.Phase.DOWNLOADING -> stringResource(R.string.self_update_downloading, state.percent.coerceAtLeast(0))
                SelfUpdate.Phase.READY ->
                    if (waiting != null) stringResource(R.string.self_update_ready_named, waiting) else stringResource(R.string.self_update_ready)
                SelfUpdate.Phase.NEEDS_PERMISSION -> stringResource(R.string.self_update_needs_permission)
                else ->
                    if (waiting != null) stringResource(R.string.self_update_installing_named, waiting) else stringResource(R.string.self_update_installing)
            }
            val action = state.phase == SelfUpdate.Phase.READY || state.phase == SelfUpdate.Phase.NEEDS_PERMISSION
            Text(text, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.padding(end = 8.dp, top = if (action) 0.dp else 8.dp, bottom = if (action) 0.dp else 8.dp))
            when (state.phase) {
                SelfUpdate.Phase.READY -> TextButton(onClick = { SelfUpdate.installNow(ctx) }, modifier = Modifier.focusGlide(CircleShape)) {
                    Text(stringResource(R.string.self_update_install), fontSize = 12.sp)
                }
                SelfUpdate.Phase.NEEDS_PERMISSION -> TextButton(
                    onClick = { activityOf(ctx)?.let { SelfUpdate.openInstallSettings(it) } },
                    modifier = Modifier.focusGlide(CircleShape),
                ) { Text(stringResource(R.string.self_update_settings), fontSize = 12.sp) }
                else -> Unit
            }
        }
    }
}

private tailrec fun activityOf(context: android.content.Context): Activity? = when (context) {
    is Activity -> context
    is android.content.ContextWrapper -> activityOf(context.baseContext)
    else -> null
}
