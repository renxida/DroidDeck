package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.Experiments

/** Settings > Advanced > Experiments: one switch per perf change, all off by default. */
@Composable
internal fun ExperimentsGroup(host: MenuHost) {
    val ctx = LocalContext.current.applicationContext
    var on by remember { mutableStateOf(Experiments.enabled(ctx)) }
    val save: (Set<String>) -> Unit = { next -> Experiments.set(ctx, next); on = Experiments.enabled(ctx) }
    SettingsGroup(stringResource(R.string.exp_group)) {
        Text(stringResource(R.string.exp_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        for (switch in Experiments.toggles) {
            ToggleRow(host, "exp-${switch.id}", stringResource(switch.label), switch.id, switch.id in on) {
                save(Experiments.with(on, switch.id, it))
            }
        }
        ChoiceRow(
            host, "exp-present", stringResource(R.string.exp_present), Experiments.present.joinToString(" / ") { it.id },
            listOf("" to stringResource(R.string.exp_present_off)) + Experiments.present.map { it.id to stringResource(it.label) },
            Experiments.present.firstOrNull { it.id in on }?.id ?: "",
        ) { picked -> save(if (picked.isEmpty()) on - Experiments.present.map { it.id }.toSet() else Experiments.with(on, picked, true)) }
        ChoiceRow(
            host, "exp-fex-order", stringResource(R.string.exp_fex_order), Experiments.fexOrder.joinToString(" / ") { it.id },
            listOf("" to stringResource(R.string.exp_fex_default)) + Experiments.fexOrder.map { it.id to stringResource(it.label) },
            Experiments.fexOrder.firstOrNull { it.id in on }?.id ?: "",
        ) { picked -> save(if (picked.isEmpty()) on - Experiments.fexOrder.map { it.id }.toSet() else Experiments.with(on, picked, true)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.exp_safe_on), compact = true, modifier = Modifier.weight(1f)) { save(Experiments.safeOn(on)) }
            SecondaryButton(stringResource(R.string.exp_reset), compact = true, modifier = Modifier.weight(1f)) { save(emptySet()) }
        }
    }
}
