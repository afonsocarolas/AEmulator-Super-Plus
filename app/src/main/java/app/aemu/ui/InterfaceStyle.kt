/* AEmulator Super Plus, 2026-10-12: interface style - pure black, corners, font, text size, motion. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R
import kotlin.math.roundToInt

/** App settings row: a summary of the interface style; opens the editor. */
@Composable
internal fun UiStyleRow() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // AemuTheme recomposes on every preference change, so this summary stays current
    val style = AppPrefs.uiStyle(ctx)
    val summary = listOf(
        stringResource(cornerLabel(style.corners)),
        stringResource(fontLabel(style.font)),
        "${(style.textScale * 100).roundToInt()}%",
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(stringResource(R.string.ui_title)) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(Icons.Rounded.Tune, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true },
    )
    if (open) UiStyleSheet(onDismiss = { open = false })
}

private fun cornerLabel(corners: Int) = when (corners) {
    AppPrefs.CORNERS_SHARP -> R.string.ui_corners_sharp
    AppPrefs.CORNERS_SOFT -> R.string.ui_corners_soft
    AppPrefs.CORNERS_EXTRA -> R.string.ui_corners_extra
    else -> R.string.ui_corners_round
}

private fun fontLabel(font: Int) = when (font) {
    AppPrefs.FONT_SERIF -> R.string.ui_font_serif
    AppPrefs.FONT_MONO -> R.string.ui_font_mono
    else -> R.string.ui_font_default
}

/** Every change is saved at once; the whole app, this sheet included, restyles live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UiStyleSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(AppPrefs.uiStyle(ctx)) }
    fun set(s: AppPrefs.UiStyle) { style = s; AppPrefs.setUiStyle(ctx, s) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.ui_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.ui_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Preview()

            Label(R.string.ui_corners)
            Segmented(
                listOf(R.string.ui_corners_sharp, R.string.ui_corners_soft, R.string.ui_corners_round, R.string.ui_corners_extra),
                style.corners,
            ) { set(style.copy(corners = it)) }

            Label(R.string.ui_font)
            Segmented(listOf(R.string.ui_font_default, R.string.ui_font_serif, R.string.ui_font_mono), style.font) {
                set(style.copy(font = it))
            }

            Label(R.string.ui_text_size)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // only stored when the finger lifts: restyling the app on every slider frame stutters
                var scale by remember(style.textScale) { mutableStateOf(style.textScale) }
                Slider(value = scale, onValueChange = { scale = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { set(style.copy(textScale = scale)) },
                    valueRange = AppPrefs.TEXT_SCALE, steps = 8, modifier = Modifier.weight(1f))
                Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 12.dp))
            }

            Spacer(Modifier.height(8.dp))
            SwitchRow(R.string.ui_pure_black, R.string.ui_pure_black_sub, style.pureBlack) { set(style.copy(pureBlack = it)) }
            SwitchRow(R.string.ui_motion, R.string.ui_motion_sub, style.expressiveMotion) { set(style.copy(expressiveMotion = it)) }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { set(AppPrefs.UiStyle()) }, enabled = style != AppPrefs.UiStyle(),
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_reset)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A small card built from the current theme: shows corners, fonts and colours as they now look. */
@Composable
private fun Preview() {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ui_preview_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.ui_preview_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {}) { Text(stringResource(R.string.ui_preview_start)) }
                AssistChip(onClick = {}, label = { Text("Android 4.4") })
                Spacer(Modifier.weight(1f))
                var on by remember { mutableStateOf(true) }
                Switch(checked = on, onCheckedChange = { on = it })
            }
        }
    }
}

@Composable
private fun Label(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
internal fun Segmented(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, res ->
            ToggleButton(
                checked = selected == i,
                onCheckedChange = { onSelect(i) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(stringResource(res), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: Int, sub: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(sub)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
