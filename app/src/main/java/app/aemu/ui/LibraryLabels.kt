/* AEmulator Sunset addition, 2026-10-04; Super Plus, 2026-10-14: dismissible card notes. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.unit.dp

/** Compact card label only; persisted ROM identity stays unchanged. */
internal object LibraryLabels {
    fun compactSkin(skin: String): String {
        val match = Regex("^CyanogenMod(?:\\s+([0-9]+(?:\\.[0-9]+)*))?$").matchEntire(skin.trim())
        return if (match != null) "CM${match.groupValues[1]}" else skin
    }
}

/** An import note on a system's card, with a button that hides it for good. */
@androidx.compose.runtime.Composable
internal fun WarningLine(text: String, onDismiss: () -> Unit, center: Boolean = false) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Text("\u26a0 $text", style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
            textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
            modifier = androidx.compose.ui.Modifier.weight(1f))
        androidx.compose.material3.IconButton(onClick = onDismiss, modifier = androidx.compose.ui.Modifier.size(32.dp)) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close,
                androidx.compose.ui.res.stringResource(app.aemu.R.string.warning_dismiss),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = androidx.compose.ui.Modifier.size(18.dp))
        }
    }
}
