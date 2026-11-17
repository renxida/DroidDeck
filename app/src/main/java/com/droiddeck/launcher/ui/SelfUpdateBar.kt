package com.droiddeck.launcher.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.update.SelfUpdate

/**
 * The foot of the front screen: always the installed build (version, commit, date, engine), and under it one line for
 * the update when there is one, naming the build it would install. Only one update state shows at a time.
 */
@Composable
internal fun SelfUpdateBar() {
    val state = SelfUpdate.ui
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val installed = remember { VersionLine.forThisApp(ctx) }
    val waiting = state.available?.let { VersionLine.build(it.commit, it.commitTimeMs) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.phase != SelfUpdate.Phase.IDLE) Row(
                Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant)
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
            Text(stringResource(R.string.version_line_installed, installed), fontSize = 10.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

private tailrec fun activityOf(context: android.content.Context): Activity? = when (context) {
    is Activity -> context
    is android.content.ContextWrapper -> activityOf(context.baseContext)
    else -> null
}
