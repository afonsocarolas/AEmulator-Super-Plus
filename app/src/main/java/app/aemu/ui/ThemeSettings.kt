/* AEmulator Super Plus, 2026-10-12: VM screen theme picker and editor; 2026-10-13: editor moved to SkinEditor.kt. GPL-3.0; see LICENSE and NOTICE.md. */
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

/** a device skin's picture, small, in the theme list (decoded off the main thread, cached per theme) */
@Composable
private fun SkinThumb(t: VmTheme) {
    val ctx = LocalContext.current
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, t.frameImage) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            VmThemes.imageFile(ctx, t)?.let { f ->
                runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(Modifier.padding(end = 10.dp).width(40.dp).height(76.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
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
                if (t.isSkin) SkinThumb(t)
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleSmall)
                    if (t.isSkin) Text(stringResource(R.string.th_skin_line, t.skinWidth, t.skinHeight, t.skinKeys.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else MiniBar(t, Modifier.fillMaxWidth(0.85f).padding(top = 4.dp))
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

@Composable
internal fun ThemeLabel(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

/** A colour swatch with its name; null = the app theme's own colour. */
@Composable
internal fun Swatch(label: Int, color: Int?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun glyphLabel(g: GlyphSet) = when (g) {
    GlyphSet.MATERIAL -> stringResource(R.string.as_navbar_original)
    GlyphSet.SUNSET -> stringResource(R.string.as_navbar_sunset)
    GlyphSet.GINGERBREAD -> "2.x"
    GlyphSet.HONEYCOMB -> "3.x"
    GlyphSet.HOLO -> "4.x"
    GlyphSet.LOLLIPOP -> "5.x"
}

internal fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
