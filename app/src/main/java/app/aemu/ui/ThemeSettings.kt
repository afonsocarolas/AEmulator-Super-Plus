/* AEmulator Super Plus, 2026-10-12: VM screen theme picker and editor. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.R
import app.aemu.core.GlyphSet
import app.aemu.core.NavButton
import app.aemu.core.VmTheme
import app.aemu.core.VmThemes
import java.util.UUID

private val PREVIEW_KEYS = listOf(NavButton.BACK, NavButton.HOME, NavButton.RECENTS)

/** App settings row: the VM screen theme in use; opens the theme list. */
@Composable
internal fun VmThemeRow() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val selected = remember(version) { VmThemes.selected(ctx) }
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.th_title)) },
        supportingContent = { Text(selected.name) },
        leadingContent = { Icon(Icons.Rounded.Palette, null) },
        trailingContent = { MiniBar(selected, Modifier.width(120.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true },
    )
    if (open) ThemeSheet(onDismiss = { open = false; version++ })
}

/** Back, Home and Recents of [theme] on its bar: a small, non-interactive preview. */
@Composable
internal fun MiniBar(theme: VmTheme, modifier: Modifier = Modifier, pressedKey: NavButton? = null) {
    val bar = theme.barColor?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer
    val ink = theme.iconColor?.let { Color(it) } ?: MaterialTheme.colorScheme.onSecondaryContainer
    Row(modifier.height(40.dp).background(bar, if (theme.roundedBar) RoundedCornerShape(20.dp) else RectangleShape),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        for (key in PREVIEW_KEYS) {
            val pressed = key == pressedKey
            val press = Color(theme.pressColor)
            Box(Modifier.size(36.dp).then(when {
                !pressed -> Modifier
                theme.pressGlow -> Modifier.background(Brush.radialGradient(listOf(press, press.copy(alpha = press.alpha * 0.28f), Color.Transparent)), CircleShape)
                else -> Modifier.background(press, RoundedCornerShape(6.dp))
            }), contentAlignment = Alignment.Center) {
                if (theme.glyphs == GlyphSet.MATERIAL) Text(when (key) { NavButton.BACK -> "←"; NavButton.HOME -> "●"; else -> "■" }, color = ink)
                else Canvas(Modifier.size(22.dp * theme.iconScale.coerceAtMost(1.3f))) {
                    NavGlyphs.draw(this, key, theme.copy(iconColor = ink.toArgb()), pressed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val themes = remember(version) { VmThemes.all(ctx) }
    val selectedId = remember(version) { VmThemes.selected(ctx).id }
    var editing by remember { mutableStateOf<VmTheme?>(null) }
    var exporting by remember { mutableStateOf<VmTheme?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = exporting; exporting = null
        if (uri != null && t != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(VmThemes.export(ctx, t)) }
        }.onSuccess { toast(ctx, ctx.getString(R.string.th_exported)) }.onFailure { toast(ctx, ctx.getString(R.string.th_failed, it.message)) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read the file")
            VmThemes.select(ctx, VmThemes.import(ctx, bytes).id)
            version++
        }.onSuccess { toast(ctx, ctx.getString(R.string.th_imported)) }.onFailure { toast(ctx, ctx.getString(R.string.th_failed, it.message)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.th_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.th_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            for (t in themes) Row(Modifier.fillMaxWidth().clickable { VmThemes.select(ctx, t.id); version++ }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = t.id == selectedId, onClick = { VmThemes.select(ctx, t.id); version++ })
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleSmall)
                    MiniBar(t, Modifier.fillMaxWidth(0.85f).padding(top = 4.dp))
                }
                // built-in themes are copied before editing; only own themes can be deleted
                IconButton(onClick = { editing = if (t.builtIn) t.copy(id = UUID.randomUUID().toString(), name = ctx.getString(R.string.th_copy_of, t.name), builtIn = false) else t }) {
                    Icon(if (t.builtIn) Icons.Rounded.ContentCopy else Icons.Rounded.Edit, stringResource(if (t.builtIn) R.string.th_duplicate else R.string.th_edit))
                }
                IconButton(onClick = { exporting = t; exportLauncher.launch("${t.name}.${VmThemes.EXTENSION}") }) {
                    Icon(Icons.Rounded.IosShare, stringResource(R.string.th_export))
                }
                if (!t.builtIn) IconButton(onClick = { VmThemes.delete(ctx, t.id); version++ }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val base = VmThemes.selected(ctx)
                    editing = base.copy(id = UUID.randomUUID().toString(), name = ctx.getString(R.string.th_new_name), builtIn = false)
                }) { Text(stringResource(R.string.th_new)) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.th_import)) }
            }
            Text(stringResource(R.string.th_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
    editing?.let { t ->
        ThemeEditor(t, onCancel = { editing = null }, onSave = { saved ->
            VmThemes.select(ctx, VmThemes.save(ctx, saved).id); editing = null; version++
        })
    }
}

/** Full-screen editor with a live preview of the VM screen: frame, rounded guest screen and the bar. */
@Composable
private fun ThemeEditor(initial: VmTheme, onCancel: () -> Unit, onSave: (VmTheme) -> Unit) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) }   // which colour the picker edits
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read the picture")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a picture" }
            t = t.copy(frameImage = VmThemes.importImage(ctx, bytes))
        }.onFailure { toast(ctx, ctx.getString(R.string.th_failed, it.message)) }
    }
    val frameBitmap = remember(t.frameImage) {
        VmThemes.imageFile(ctx, t)?.let { f ->
            runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull()
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(R.string.th_editor), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                // live preview: frame (colour or picture), the guest screen with the theme's corners, the bar under it
                Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).background(Color(t.frameColor)), contentAlignment = Alignment.Center) {
                    frameBitmap?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(120.dp).aspectRatio(0.62f).clip(RoundedCornerShape(t.screenCorner.dp / 2))
                            .background(Brush.verticalGradient(listOf(Color(0xFF33B5E5), Color(0xFF0D2B66)))))
                        Spacer(Modifier.height(8.dp))
                        MiniBar(t, Modifier.width(200.dp), pressedKey = NavButton.HOME)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = t.name, onValueChange = { t = t.copy(name = it.take(VmTheme.NAME_MAX)) },
                    label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Label(R.string.th_glyphs)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (g in GlyphSet.entries) FilterChip(selected = t.glyphs == g, onClick = { t = t.copy(glyphs = g) },
                        label = { Text(glyphLabel(g)) })
                }
                Label(R.string.th_icon_size)
                Slider(value = t.iconScale, onValueChange = { t = t.copy(iconScale = it) }, valueRange = 0.7f..1.5f)

                Label(R.string.th_colours)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(R.string.th_bar, t.barColor) { picking = "bar" }
                    Swatch(R.string.th_icons, t.iconColor) { picking = "icons" }
                    Swatch(R.string.th_pressed, t.pressColor) { picking = "pressed" }
                    Swatch(R.string.th_frame, t.frameColor) { picking = "frame" }
                }
                Toggle(stringResource(R.string.th_glow), stringResource(R.string.th_glow_sub), t.pressGlow) { t = t.copy(pressGlow = it) }
                Toggle(stringResource(R.string.th_halo), stringResource(R.string.th_halo_sub), t.halo) { t = t.copy(halo = it) }
                Toggle(stringResource(R.string.th_rounded_bar), stringResource(R.string.th_rounded_bar_sub), t.roundedBar) { t = t.copy(roundedBar = it) }

                Label(R.string.th_frame_section)
                Text(stringResource(R.string.th_corner, t.screenCorner), style = MaterialTheme.typography.bodyMedium)
                Slider(value = t.screenCorner.toFloat(), onValueChange = { t = t.copy(screenCorner = it.toInt()) }, valueRange = 0f..40f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.th_pick_picture)) }
                    if (t.frameImage != null) TextButton(onClick = { t = t.copy(frameImage = null) }) { Text(stringResource(R.string.th_remove_picture)) }
                }
                Text(stringResource(R.string.th_corner_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(t.copy(name = VmTheme.cleanName(t.name).ifBlank { ctx.getString(R.string.th_new_name) })) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
    picking?.let { which ->
        val current = when (which) { "bar" -> t.barColor; "icons" -> t.iconColor; "pressed" -> t.pressColor; else -> t.frameColor }
        ColorPickerDialog(Color(current ?: 0xFF000000.toInt()), onDismiss = { picking = null },
            onReset = {
                // back to the value of the theme this one started from
                t = when (which) { "bar" -> t.copy(barColor = initial.barColor); "icons" -> t.copy(iconColor = initial.iconColor)
                    "pressed" -> t.copy(pressColor = initial.pressColor); else -> t.copy(frameColor = initial.frameColor) }
                picking = null
            },
            onApply = { c ->
                val v = c.toArgb()
                t = when (which) { "bar" -> t.copy(barColor = v); "icons" -> t.copy(iconColor = v)
                    "pressed" -> t.copy(pressColor = v); else -> t.copy(frameColor = v) }
                picking = null
            })
    }
}

@Composable
private fun Label(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

/** A colour swatch with its name; null = the app theme's own colour. */
@Composable
private fun Swatch(label: Int, color: Int?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun glyphLabel(g: GlyphSet) = when (g) {
    GlyphSet.MATERIAL -> stringResource(R.string.as_navbar_original)
    GlyphSet.SUNSET -> stringResource(R.string.as_navbar_sunset)
    GlyphSet.GINGERBREAD -> "2.x"
    GlyphSet.HONEYCOMB -> "3.x"
    GlyphSet.HOLO -> "4.x"
    GlyphSet.LOLLIPOP -> "5.x"
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
