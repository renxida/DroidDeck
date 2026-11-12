package com.droiddeck.launcher.ui

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.ui.GlideDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.files.InAppFilePicker
import com.droiddeck.launcher.runtime.LinuxFex
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.store.UserAppsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val ICON_TYPES = listOf("png", "jpg", "jpeg", "webp")
private val FieldShape = RoundedCornerShape(8.dp)

@Composable
internal fun AddAppDialog(runtimeReady: Boolean, onDismiss: () -> Unit, onAdd: (UserApps.Request, String) -> Unit) {
    val ctx = LocalContext.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }

    var scriptPath by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var iconPath by rememberSaveable { mutableStateOf<String?>(null) }

    val pickScript = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { scriptPath = it.path }
    }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { iconPath = it.path }
    }

    val custom = name.trim().takeIf { it.isNotEmpty() }
    val request: Pair<UserApps.Request, String>? = scriptPath?.takeIf { it.endsWith(".sh", ignoreCase = true) }?.let { File(it) }
        ?.let { UserApps.Request(it, custom, iconPath) to (custom ?: it.nameWithoutExtension) }
    val busy = UserAppsState.working

    AppDialog(shown, close, "addApp", wide = true) {
        DialogHeader(stringResource(R.string.user_apps_back), stringResource(R.string.add_app_title))
        Columns(
            first = {
                Panel(stringResource(R.string.add_app_source)) {
                    Small(stringResource(R.string.add_app_script_hint))
                    FileRow(scriptPath) { pickScript.launch(InAppFilePicker.buildIntent(ctx, listOf("sh"), ctx.getString(R.string.add_app_pick_script))) }
                    scriptPath?.let { FolderNote(it) }
                }
            },
            second = {
                Panel(stringResource(R.string.add_app_appearance)) {
                    AppPreview(iconPath?.let(::File), Icons.Outlined.Terminal, request?.second, stringResource(R.string.add_app_tab_script))
                    NameField(name, { name = it }, stringResource(R.string.add_app_name_placeholder))
                    IconActions(
                        onChoose = { pickIcon.launch(InAppFilePicker.buildIntent(ctx, ICON_TYPES, ctx.getString(R.string.add_app_pick_icon))) },
                        clear = iconPath?.let { R.string.add_app_clear_icon to { iconPath = null } },
                    )
                }
            },
        )
        DialogFooter(
            when {
                !runtimeReady -> stringResource(R.string.add_app_runtime_required)
                busy != null -> stringResource(R.string.add_app_busy, busy)
                else -> null
            },
            stringResource(R.string.add_app_confirm), request != null && runtimeReady && busy == null,
            onCancel = close,
        ) { request?.let { (r, label) -> onAdd(r, label); close() } }
    }
}

@Composable
internal fun EditAppDialog(app: UserApps.App, onDismiss: () -> Unit, onSave: (String, String?, String?) -> Unit) {
    val ctx = LocalContext.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var name by rememberSaveable(app.key) { mutableStateOf(app.name) }
    var icon by rememberSaveable(app.key) { mutableStateOf<String?>(null) }
    var fex by rememberSaveable(app.key) { mutableStateOf(app.fex) }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedFile(r.data)?.let { icon = it.path }
    }
    val preview: Any? = when (val i = icon) {
        null -> app.icon
        "" -> null
        else -> File(i)
    }
    val busy = UserAppsState.working
    val changed = name.trim() != app.name || icon != null || fex != app.fex
    AppDialog(shown, close, "editApp", wide = false) {
        DialogHeader(stringResource(R.string.user_apps_kind_script), stringResource(R.string.edit_app_title, app.name))
        Rise(1) {
            Panel(stringResource(R.string.add_app_appearance)) {
                AppPreview(preview, Icons.Outlined.Terminal, name.trim().ifEmpty { null }, stringResource(R.string.user_apps_kind_script))
                NameField(name, { name = it }, app.name)
                IconActions(
                    onChoose = { pickIcon.launch(InAppFilePicker.buildIntent(ctx, ICON_TYPES, ctx.getString(R.string.add_app_pick_icon))) },
                    clear = if (preview == null) null else R.string.add_app_icon_reset to { icon = "" },
                )
                Text(stringResource(R.string.app_fex_title).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SegmentedTabs(
                    listOf(
                        LinuxFex.AUTO to stringResource(R.string.app_fex_auto),
                        LinuxFex.ON to stringResource(R.string.app_fex_on),
                        LinuxFex.OFF to stringResource(R.string.app_fex_off),
                    ),
                    fex,
                ) { fex = it }
                Text(stringResource(R.string.app_fex_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DialogFooter(
            busy?.let { stringResource(R.string.add_app_busy, it) },
            stringResource(R.string.edit_app_save), name.isNotBlank() && changed && busy == null,
            onCancel = close,
        ) { onSave(name.trim(), icon, fex.takeIf { it != app.fex }); close() }
    }
}

@Composable
internal fun rememberShown(onDismiss: () -> Unit): MutableTransitionState<Boolean> {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onDismiss() }
    return shown
}

@Composable
internal fun AppDialog(
    shown: MutableTransitionState<Boolean>, close: () -> Unit, label: String, wide: Boolean,
    modifier: Modifier = Modifier, maxWidth: Dp? = null,
    width: Dp? = null, fitContent: Boolean = false,
    back: (() -> Unit)? = null,
    scrollable: Boolean = true,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPalette.current
    VeilRing()
    Dialog(onDismissRequest = back ?: close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      CompositionLocalProvider(LocalFrontFocus provides null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().imePadding()
                .tapOutside(close),
        ) {
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.sp(0.7f), initialScale = 0.94f),
                exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.96f),
                label = label,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .padding(12.dp)
                        .widthIn(max = maxWidth ?: if (wide) 860.dp else 560.dp)
                        .then(
                            when {
                                width != null -> Modifier.width(width)
                                fitContent -> Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                                else -> Modifier.fillMaxWidth()
                            },
                        )
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp - 24).dp)
                        .shadow(24.dp, Shape16, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(Shape16)
                        .background(pal.surfaceVariant.copy(alpha = 0.97f))
                        .border(1.dp, pal.signal.copy(alpha = 0.22f), Shape16)
                        .blockTouches()
                        .controllerBack(back ?: close)
                        .then(modifier)
                        .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    content = content,
                )
            }
        }
      }
    }
}

@Composable
internal fun DialogHeader(eyebrow: String, title: String, trailing: (@Composable () -> Unit)? = null) {
    val heading = @Composable {
        Column {
            Eyebrow(eyebrow)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Rise(0) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            when {
                trailing == null -> heading()
                maxWidth >= 600.dp -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { heading() }
                    trailing()
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { heading(); trailing() }
            }
        }
    }
}

@Composable
private fun Columns(first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    Rise(1) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                Column(Modifier.weight(1.15f).fillMaxHeight(), content = first)
                Column(Modifier.weight(1f).fillMaxHeight(), content = second)
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first()
                second()
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().fillMaxHeight().clip(Shape14)
            .background(colors.surface.copy(alpha = 0.6f)).border(1.dp, LocalPalette.current.line, Shape14).padding(14.dp),
    ) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
        content()
    }
}

@Composable
internal fun DialogFooter(note: String?, confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val lineColor = LocalPalette.current.line
    Rise(2) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.drawBehind { drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 14.dp),
        ) {
            Box(Modifier.weight(1f)) { if (note != null) Small(note) }
            SecondaryButton(stringResource(R.string.add_app_cancel), onClick = onCancel)
            PrimaryButton(confirm, enabled = enabled, onClick = onConfirm)
        }
    }
}

@Composable
internal fun Small(text: String, error: Boolean = false) =
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)

private fun hideKeyboard(view: View) {
    (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(view.windowToken, 0)
}

@Composable
private fun NameField(name: String, onName: (String) -> Unit, placeholder: String) {
    val view = LocalView.current
    AdbTextField(
        name, { onName(it.take(80)) }, stringResource(R.string.add_app_name), KeyboardType.Text, ImeAction.Done,
        placeholder = placeholder, compact = true, onDone = { hideKeyboard(view) },
    )
}

@Composable
private fun AppPreview(icon: Any?, glyph: ImageVector, name: String?, kind: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape12).background(colors.surfaceVariant).border(1.dp, pal.line2, Shape12).padding(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(Shape12).background(colors.surface)) {
            AnimatedContent(icon, transitionSpec = { (fadeIn(Motion.tw(260)) + scaleIn(Motion.sp(0.6f), 0.85f)) togetherWith fadeOut(Motion.tw(160)) }, label = "appIcon") { model ->
                if (model != null) AsyncImage(model = model, contentDescription = stringResource(R.string.add_app_icon), contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
                else Icon(glyph, stringResource(R.string.add_app_icon_default), tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                name ?: stringResource(R.string.add_app_preview_name), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (name != null) colors.onBackground else colors.onSurfaceVariant,
            )
            Text(kind, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun IconActions(onChoose: () -> Unit, clear: Pair<Int, () -> Unit>?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.add_app_choose_icon), compact = true, onClick = onChoose)
        AnimatedVisibility(clear != null, enter = fadeIn(Motion.tw(180)) + expandHorizontally(Motion.tw(220)), exit = fadeOut(Motion.tw(120)) + shrinkHorizontally(Motion.tw(180))) {
            clear?.let { (label, action) -> SecondaryButton(stringResource(label), compact = true, onClick = action) }
        }
    }
}

@Composable
private fun FileRow(path: String?, onChoose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column {
        Text(stringResource(R.string.add_app_file), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f).height(44.dp).background(colors.surfaceVariant, FieldShape).border(1.dp, pal.line2, FieldShape).padding(horizontal = 12.dp),
            ) {
                Text(
                    path?.substringAfterLast('/') ?: stringResource(R.string.add_app_no_file), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (path != null) colors.onBackground else colors.onSurfaceVariant,
                )
            }
            SecondaryButton(stringResource(if (path == null) R.string.add_app_choose_file else R.string.add_app_change_file), compact = true, onClick = onChoose)
        }
    }
}

@Composable
private fun FolderNote(scriptPath: String) {
    var plan by remember(scriptPath) { mutableStateOf<UserApps.ScriptPlan?>(null) }
    LaunchedEffect(scriptPath) {
        plan = withContext(Dispatchers.IO) { runCatching { UserApps.planScript(File(scriptPath)) }.getOrNull() }
    }
    val p = plan
    Small(
        when {
            p == null -> stringResource(R.string.add_app_checking_folder)
            p.copy -> stringResource(R.string.add_app_folder_copied, FileUtils.sizeToString(p.bytes))
            else -> stringResource(R.string.add_app_folder_linked, p.folder.path)
        },
    )
}
