package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.telemetry.TelemetryUploadSettings

/** Setup > Diagnostics: send each session's telemetry to the user's own GitHub repository. */
@Composable
internal fun TelemetryUploadRows(host: MenuHost) {
    val ctx = LocalContext.current.applicationContext
    var enabled by remember { mutableStateOf(TelemetryUploadSettings.enabled(ctx)) }
    var hasToken by remember { mutableStateOf(TelemetryUploadSettings.hasToken(ctx)) }
    var repo by remember { mutableStateOf(TelemetryUploadSettings.repo(ctx)) }
    var tokenInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(TelemetryUploadSettings.lastResult(ctx)) }
    ToggleRow(host, "telemetry-upload", stringResource(R.string.setup_telemetry_upload),
        stringResource(R.string.setup_telemetry_upload_hint), enabled) {
        TelemetryUploadSettings.setEnabled(ctx, it)
        enabled = it
        if (it) com.droiddeck.launcher.telemetry.TelemetryUploader.settingsChanged(ctx)
    }
    if (!enabled) return
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            repo, { repo = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.setup_telemetry_repo)) },
            isError = !TelemetryUploadSettings.validRepo(repo.trim()),
        )
        OutlinedTextField(
            tokenInput, { tokenInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(if (hasToken) R.string.setup_telemetry_token_saved else R.string.setup_telemetry_token)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.setup_telemetry_save), compact = true, modifier = Modifier.weight(1f)) {
                val repoOk = TelemetryUploadSettings.setRepo(ctx, repo)
                val tokenOk = tokenInput.isBlank() || TelemetryUploadSettings.setToken(ctx, tokenInput)
                tokenInput = ""
                hasToken = TelemetryUploadSettings.hasToken(ctx)
                message = ctx.getString(if (repoOk && tokenOk) R.string.setup_telemetry_saved else R.string.setup_telemetry_invalid)
                if (repoOk && tokenOk) com.droiddeck.launcher.telemetry.TelemetryUploader.settingsChanged(ctx)
            }
            SecondaryButton(stringResource(R.string.setup_telemetry_forget), enabled = hasToken, compact = true, modifier = Modifier.weight(1f)) {
                TelemetryUploadSettings.clearToken(ctx)
                hasToken = false
                message = null
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = colors.onSurfaceVariant) }
    }
}
