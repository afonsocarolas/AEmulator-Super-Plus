/* AEmulator Super Plus, 2026-10-14: boot sound picker. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.BootSound

private fun soundLabel(s: BootSound.Sound) = when (s) {
    BootSound.Sound.OFF -> R.string.bs_off
    BootSound.Sound.CHIME -> R.string.bs_chime
    BootSound.Sound.RETRO -> R.string.bs_retro
    BootSound.Sound.RISE -> R.string.bs_rise
    BootSound.Sound.CUSTOM -> R.string.bs_custom
}

/** App settings row: the boot sound in use; opens the picker. */
@Composable
internal fun BootSoundRow() {
    val ctx = LocalContext.current
    var choice by remember { mutableStateOf(BootSound.load(ctx)) }
    var open by remember { mutableStateOf(false) }
    val summary = if (choice.sound == BootSound.Sound.OFF) stringResource(R.string.bs_off)
        else (if (choice.sound == BootSound.Sound.CUSTOM) choice.customName.ifBlank { stringResource(R.string.bs_custom) } else stringResource(soundLabel(choice.sound))) +
            " · " + stringResource(if (choice.moment == BootSound.Moment.START) R.string.bs_when_start else R.string.bs_when_finished)
    ListItem(
        headlineContent = { Text(stringResource(R.string.bs_title)) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(Icons.Rounded.MusicNote, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true },
    )
    if (open) BootSoundSheet(choice, onChange = { choice = it; BootSound.save(ctx, it) }, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BootSoundSheet(choice: BootSound.Choice, onChange: (BootSound.Choice) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read the file")
            BootSound.importCustom(ctx, bytes)
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
            onChange(choice.copy(sound = BootSound.Sound.CUSTOM, customName = name))
            BootSound.play(ctx, BootSound.Sound.CUSTOM)
        }.onFailure { toast(ctx, ctx.getString(R.string.th_failed, it.message)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.bs_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.bs_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            for (s in BootSound.Sound.entries) {
                val custom = s == BootSound.Sound.CUSTOM
                val label = if (custom && choice.customName.isNotBlank()) "${stringResource(R.string.bs_custom)}: ${choice.customName}"
                    else stringResource(soundLabel(s))
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = if (custom) ({ Text(stringResource(R.string.bs_custom_sub)) }) else null,
                    leadingContent = { RadioButton(selected = choice.sound == s, onClick = null) },
                    trailingContent = if (s == BootSound.Sound.OFF || (custom && !BootSound.customFile(ctx).isFile)) null else ({
                        IconButton(onClick = { BootSound.play(ctx, s) }) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.bs_preview)) }
                    }),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable {
                        // a custom sound needs a file first; picking one again replaces it
                        if (custom && (choice.sound == s || !BootSound.customFile(ctx).isFile)) picker.launch(arrayOf("audio/*"))
                        else { onChange(choice.copy(sound = s)); if (s != BootSound.Sound.OFF) BootSound.play(ctx, s) }
                    },
                )
            }
            ThemeLabel(R.string.bs_when)
            Segmented(listOf(R.string.bs_when_start, R.string.bs_when_finished), if (choice.moment == BootSound.Moment.START) 0 else 1) {
                onChange(choice.copy(moment = if (it == 0) BootSound.Moment.START else BootSound.Moment.FINISHED))
            }
            Text(stringResource(R.string.bs_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        }
    }
}
