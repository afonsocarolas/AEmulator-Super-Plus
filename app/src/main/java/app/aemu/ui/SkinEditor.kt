/* AEmulator Super Plus, 2026-10-13: theme editor with device skins. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.R
import app.aemu.core.DeviceSkin
import app.aemu.core.GlyphSet
import app.aemu.core.NavButton
import app.aemu.core.SkinKey
import app.aemu.core.SkinRect
import app.aemu.core.VmTheme
import app.aemu.core.VmThemes

/** Keys a skin can carry: the ones phones of the Android 2.x-4.x era had as real buttons. */
private val SKIN_KEYS = listOf(NavButton.BACK, NavButton.HOME, NavButton.MENU, NavButton.RECENTS, NavButton.SEARCH,
    NavButton.POWER, NavButton.VOLUME_UP, NavButton.VOLUME_DOWN, NavButton.CENTER)

/** The picture of a theme, decoded at most [maxWidth] wide; null when there is none or it cannot be read. */
private fun decode(path: String?, maxWidth: Int): Bitmap? = path?.let { p ->
    runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(p, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/** The screen of a phone picture, found on a small copy of it (see [DeviceSkin.findScreen]). */
private fun detectScreen(bitmap: Bitmap): SkinRect? {
    val w = 300.coerceAtMost(bitmap.width)
    val h = (bitmap.height.toLong() * w / bitmap.width).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
    val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    if (small !== bitmap) small.recycle()
    return DeviceSkin.findScreen(pixels, w, h)
}

/**
 * Full-screen theme editor. A phone picture added with "Add new PNG" becomes a device skin: the guest is shown in its
 * screen area (found automatically, then dragged or resized here) and keys drawn on the picture can work as real keys.
 * The bar, colours and corners are in the Style sheet.
 */
@Composable
internal fun ThemeEditor(initial: VmTheme, onCancel: () -> Unit, onSave: (VmTheme) -> Unit) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(-1) }     // -1 = the screen, else index in skinKeys
    var menu by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf(false) }
    var addKey by remember { mutableStateOf(false) }
    val bitmap = remember(t.frameImage) { decode(VmThemes.imageFile(ctx, t)?.path, 1440) }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    fun findScreen(announce: Boolean) {
        val b = bitmap ?: return
        val found = detectScreen(b)
        if (found != null) {
            t = t.copy(skinScreen = if (t.skinWidth > 0) DeviceSkin.withAspect(found, t.skinWidth, t.skinHeight, b.width, b.height) else found)
            if (announce) toast(ctx, ctx.getString(R.string.sk_found))
        } else {
            if (t.skinScreen == null) t = t.copy(skinScreen = SkinRect.DEFAULT_SCREEN)
            toast(ctx, ctx.getString(R.string.sk_not_found))
        }
    }

    var pendingDetect by remember { mutableStateOf(false) }
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read the picture")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a picture" }
            // a new phone: its screen and keys are somewhere else
            t = t.copy(frameImage = VmThemes.importImage(ctx, bytes), skinScreen = null, skinKeys = emptyList())
            selected = -1
            pendingDetect = true
        }.onFailure { toast(ctx, ctx.getString(R.string.th_failed, it.message)) }
    }
    // the new picture is decoded on the next composition; find its screen once it is there
    LaunchedEffect(bitmap, pendingDetect) { if (pendingDetect && bitmap != null) { pendingDetect = false; findScreen(announce = true) } }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = Color.White) }
                    Text(stringResource(R.string.th_editor), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { onSave(t.copy(name = VmTheme.cleanName(t.name).ifBlank { ctx.getString(R.string.th_new_name) })) }) {
                        Text(stringResource(R.string.save), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    val screen = t.skinScreen
                    if (image != null && screen != null) {
                        SkinCanvas(image, t, selected, onSelect = { selected = it }, onChange = { t = it })
                    } else {
                        ThemePreview(t, image)
                    }
                }

                if (t.isSkin) Text(stringResource(R.string.sk_hint), style = MaterialTheme.typography.bodySmall, color = Color(0xFFBDBDBD),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)

                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { resolution = true }.padding(4.dp)) {
                        Text(stringResource(R.string.sk_pick_resolution), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(if (t.skinWidth > 0) "${t.skinWidth}x${t.skinHeight}" else stringResource(R.string.sk_any),
                            fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.sk_add_png), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { pngPicker.launch(arrayOf("image/png", "image/*")) }.padding(8.dp))
                        Row {
                            IconButton(onClick = { style = true }) { Icon(Icons.Rounded.Tune, stringResource(R.string.sk_style), tint = Color.White) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.sk_more), tint = Color.White) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (bitmap != null) DropdownMenuItem(text = { Text(stringResource(R.string.sk_find_screen)) },
                                        onClick = { menu = false; findScreen(announce = true) })
                                    if (t.isSkin) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.sk_add_key)) }, onClick = { menu = false; addKey = true })
                                        if (selected >= 0) DropdownMenuItem(text = { Text(stringResource(R.string.sk_remove_key)) }, onClick = {
                                            menu = false; t = t.copy(skinKeys = t.skinKeys.filterIndexed { i, _ -> i != selected }); selected = -1
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.sk_reset_screen)) },
                                            onClick = { menu = false; t = t.copy(skinScreen = SkinRect.DEFAULT_SCREEN); selected = -1 })
                                    }
                                    if (t.frameImage != null) DropdownMenuItem(text = { Text(stringResource(R.string.sk_remove_png)) }, onClick = {
                                        menu = false; t = t.copy(frameImage = null, skinScreen = null, skinKeys = emptyList()); selected = -1
                                    })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text(stringResource(R.string.sk_style)) }, onClick = { menu = false; style = true })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (style) StyleSheet(t, initial, onChange = { t = it }, onDismiss = { style = false })
    if (resolution) ResolutionDialog(t.skinWidth, t.skinHeight, onDismiss = { resolution = false }) { w, h ->
        resolution = false
        val b = bitmap
        val screen = t.skinScreen
        t = t.copy(skinWidth = w, skinHeight = h,
            skinScreen = if (screen != null && b != null && w > 0) DeviceSkin.withAspect(screen, w, h, b.width, b.height) else screen)
    }
    if (addKey) AddKeyDialog(onDismiss = { addKey = false }) { button ->
        addKey = false
        val s = t.skinScreen ?: SkinRect.DEFAULT_SCREEN
        // a new key starts just below the screen, centred; the user drags it onto the drawn button
        val top = (s.b + 0.02f).coerceAtMost(0.9f)
        t = t.copy(skinKeys = t.skinKeys + SkinKey(button, SkinRect(0.42f, top, 0.58f, (top + 0.06f).coerceAtMost(1f))))
        selected = t.skinKeys.lastIndex
    }
}

private enum class Grab { MOVE, TL, TR, BL, BR }

/** The skin with its screen area and key zones on top; drag an area to move it, a corner dot to resize it. */
@Composable
private fun SkinCanvas(image: ImageBitmap, t: VmTheme, selected: Int, onSelect: (Int) -> Unit, onChange: (VmTheme) -> Unit) {
    val theme by rememberUpdatedState(t)
    val sel by rememberUpdatedState(selected)
    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val keyColor = Color(0xFFFFC107)
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    val keyNames = SKIN_KEYS.associateWith { stringResource(it.labelRes()) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = image.width.toFloat() / image.height
        Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = maxWidth / maxHeight > ratio)) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val x = p.x / size.width; val y = p.y / size.height
                        val key = theme.skinKeys.indexOfLast { it.area.contains(x, y) }
                        onSelect(if (key >= 0) key else -1)
                    }
                }
                .pointerInput(Unit) {
                    var target = -1
                    var grab = Grab.MOVE
                    detectDragGestures(
                        onDragStart = { p ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            fun corner(r: SkinRect): Grab? {
                                val pts = listOf(Grab.TL to Offset(r.l * w, r.t * h), Grab.TR to Offset(r.r * w, r.t * h),
                                    Grab.BL to Offset(r.l * w, r.b * h), Grab.BR to Offset(r.r * w, r.b * h))
                                return pts.firstOrNull { (it.second - p).getDistance() < handle }?.first
                            }
                            val keys = theme.skinKeys
                            val screen = theme.skinScreen ?: return@detectDragGestures
                            val x = p.x / w; val y = p.y / h
                            // the selected item's corners first, then any key, then the screen
                            val selArea = if (sel >= 0) keys.getOrNull(sel)?.area else screen
                            val selCorner = selArea?.let(::corner)
                            when {
                                selCorner != null -> { target = sel; grab = selCorner }
                                keys.indexOfLast { it.area.contains(x, y) } >= 0 -> { target = keys.indexOfLast { it.area.contains(x, y) }; grab = Grab.MOVE }
                                corner(screen) != null -> { target = -1; grab = corner(screen)!! }
                                else -> { target = -1; grab = Grab.MOVE }
                            }
                            onSelect(target)
                        },
                        onDrag = { change, d ->
                            change.consume()
                            val dx = d.x / size.width; val dy = d.y / size.height
                            val cur = theme
                            val area = (if (target >= 0) cur.skinKeys.getOrNull(target)?.area else cur.skinScreen) ?: return@detectDragGestures
                            var next = when (grab) {
                                Grab.MOVE -> area.moved(dx, dy)
                                Grab.TL -> area.copy(l = area.l + dx, t = area.t + dy)
                                Grab.TR -> area.copy(r = area.r + dx, t = area.t + dy)
                                Grab.BL -> area.copy(l = area.l + dx, b = area.b + dy)
                                Grab.BR -> area.copy(r = area.r + dx, b = area.b + dy)
                            }.normalized()
                            // a screen with a chosen resolution keeps its shape: the height follows the width
                            if (target < 0 && grab != Grab.MOVE && cur.skinWidth > 0) {
                                val hgt = (next.width * image.width * cur.skinHeight / cur.skinWidth / image.height).coerceAtMost(1f)
                                next = if (grab == Grab.TL || grab == Grab.TR) next.copy(t = (next.b - hgt).coerceAtLeast(0f))
                                    else next.copy(b = (next.t + hgt).coerceAtMost(1f))
                            }
                            onChange(if (target >= 0) cur.copy(skinKeys = cur.skinKeys.mapIndexed { i, k -> if (i == target) k.copy(area = next) else k })
                                else cur.copy(skinScreen = next))
                        },
                    )
                }) {
                val w = size.width; val h = size.height
                fun rect(r: SkinRect) = Offset(r.l * w, r.t * h) to Size(r.width * w, r.height * h)
                val screen = theme.skinScreen ?: return@Canvas
                val (so, ss) = rect(screen)
                val corner = CornerRadius(theme.screenCorner.dp.toPx() / 2)
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF33B5E5), Color(0xFF0D2B66)), so.y, so.y + ss.height), so, ss, corner, alpha = 0.85f)
                val label = if (theme.skinWidth > 0) "${theme.skinWidth}x${theme.skinHeight}" else "${(screen.width * image.width).toInt()}x${(screen.height * image.height).toInt()} px"
                val text = measurer.measure(label, TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                drawText(text, topLeft = Offset(so.x + (ss.width - text.size.width) / 2, so.y + (ss.height - text.size.height) / 2))
                drawRoundRect(if (sel < 0) primary else Color.White.copy(alpha = 0.7f), so, ss, corner, style = Stroke(if (sel < 0) 3.dp.toPx() else 1.5f.dp.toPx()))
                theme.skinKeys.forEachIndexed { i, k ->
                    val (ko, ks) = rect(k.area)
                    drawRect(keyColor.copy(alpha = if (i == sel) 0.35f else 0.18f), ko, ks)
                    drawRect(keyColor, ko, ks, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
                    val kt = measurer.measure(keyNames[k.button] ?: k.button.id, TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    drawText(kt, topLeft = Offset(ko.x + (ks.width - kt.size.width) / 2, ko.y + (ks.height - kt.size.height) / 2))
                }
                // corner dots of the selected area
                val active = if (sel >= 0) theme.skinKeys.getOrNull(sel)?.area else screen
                active?.let { a ->
                    for (pt in listOf(Offset(a.l * w, a.t * h), Offset(a.r * w, a.t * h), Offset(a.l * w, a.b * h), Offset(a.r * w, a.b * h))) {
                        drawCircle(Color.White, 9.dp.toPx(), pt)
                        drawCircle(if (sel >= 0) keyColor else primary, 9.dp.toPx(), pt, style = Stroke(3.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** Preview without a skin: frame colour or background picture, the guest screen with the theme's corners, the bar. */
@Composable
private fun ThemePreview(t: VmTheme, picture: ImageBitmap?) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(t.frameColor)), contentAlignment = Alignment.Center) {
        picture?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(150.dp).aspectRatio(0.6f).clip(RoundedCornerShape(t.screenCorner.dp / 2))
                .background(Brush.verticalGradient(listOf(Color(0xFF33B5E5), Color(0xFF0D2B66)))))
            Spacer(Modifier.height(8.dp))
            MiniBar(t, Modifier.width(220.dp), pressedKey = NavButton.HOME)
        }
        if (picture == null) Text(stringResource(R.string.sk_empty), style = MaterialTheme.typography.bodySmall, color = Color(0xFFBDBDBD),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

/** Name, the on-screen bar, colours and screen corners: everything that was in the editor before skins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleSheet(t: VmTheme, initial: VmTheme, onChange: (VmTheme) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf<String?>(null) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read the picture")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a picture" }
            // a background fills the screen behind the guest: not a phone skin
            onChange(t.copy(frameImage = VmThemes.importImage(ctx, bytes), skinScreen = null, skinKeys = emptyList()))
        }.onFailure { toast(ctx, ctx.getString(R.string.th_failed, it.message)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.sk_style), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = t.name, onValueChange = { onChange(t.copy(name = it.take(VmTheme.NAME_MAX))) },
                label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MiniBar(t, Modifier.fillMaxWidth().padding(top = 12.dp), pressedKey = NavButton.HOME)
            if (t.isSkin && t.skinKeys.isNotEmpty()) Text(stringResource(R.string.sk_bar_hidden), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

            ThemeLabel(R.string.th_glyphs)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in GlyphSet.entries) FilterChip(selected = t.glyphs == g, onClick = { onChange(t.copy(glyphs = g)) }, label = { Text(glyphLabel(g)) })
            }
            ThemeLabel(R.string.th_icon_size)
            Slider(value = t.iconScale, onValueChange = { onChange(t.copy(iconScale = it)) }, valueRange = 0.7f..1.5f)

            ThemeLabel(R.string.th_colours)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch(R.string.th_bar, t.barColor) { picking = "bar" }
                Swatch(R.string.th_icons, t.iconColor) { picking = "icons" }
                Swatch(R.string.th_pressed, t.pressColor) { picking = "pressed" }
                Swatch(R.string.th_frame, t.frameColor) { picking = "frame" }
            }
            Toggle(stringResource(R.string.th_glow), stringResource(R.string.th_glow_sub), t.pressGlow) { onChange(t.copy(pressGlow = it)) }
            Toggle(stringResource(R.string.th_halo), stringResource(R.string.th_halo_sub), t.halo) { onChange(t.copy(halo = it)) }
            Toggle(stringResource(R.string.th_rounded_bar), stringResource(R.string.th_rounded_bar_sub), t.roundedBar) { onChange(t.copy(roundedBar = it)) }

            ThemeLabel(R.string.th_frame_section)
            Text(stringResource(R.string.th_corner, t.screenCorner), style = MaterialTheme.typography.bodyMedium)
            Slider(value = t.screenCorner.toFloat(), onValueChange = { onChange(t.copy(screenCorner = it.toInt())) }, valueRange = 0f..40f)
            if (!t.isSkin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backgroundPicker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.th_pick_picture)) }
                if (t.frameImage != null) TextButton(onClick = { onChange(t.copy(frameImage = null)) }) { Text(stringResource(R.string.th_remove_picture)) }
            }
            Text(stringResource(R.string.th_corner_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
    picking?.let { which ->
        val current = when (which) { "bar" -> t.barColor; "icons" -> t.iconColor; "pressed" -> t.pressColor; else -> t.frameColor }
        ColorPickerDialog(Color(current ?: 0xFF000000.toInt()), onDismiss = { picking = null },
            onReset = {
                // back to the value of the theme this one started from
                onChange(when (which) { "bar" -> t.copy(barColor = initial.barColor); "icons" -> t.copy(iconColor = initial.iconColor)
                    "pressed" -> t.copy(pressColor = initial.pressColor); else -> t.copy(frameColor = initial.frameColor) })
                picking = null
            },
            onApply = { c ->
                val v = c.toArgb()
                onChange(when (which) { "bar" -> t.copy(barColor = v); "icons" -> t.copy(iconColor = v)
                    "pressed" -> t.copy(pressColor = v); else -> t.copy(frameColor = v) })
                picking = null
            })
    }
}

@Composable
private fun ResolutionDialog(width: Int, height: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    var w by remember { mutableStateOf(if (width > 0) width.toString() else "") }
    var h by remember { mutableStateOf(if (height > 0) height.toString() else "") }
    val custom = w.toIntOrNull()?.takeIf { it in 120..8192 }?.let { cw -> h.toIntOrNull()?.takeIf { it in 120..8192 }?.let { cw to it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sk_pick_resolution)) },
        text = {
            Column {
                Text(stringResource(R.string.sk_resolution_sub), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.height(260.dp).padding(top = 8.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().clickable { onPick(0, 0) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = width == 0, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(stringResource(R.string.sk_any))
                        }
                    }
                    items(DeviceSkin.RESOLUTIONS) { (rw, rh) ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(rw, rh) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = width == rw && height == rh, onClick = null, modifier = Modifier.padding(12.dp))
                            Text("${rw}x${rh}")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                        label = { Text(stringResource(R.string.sk_width)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("x")
                    OutlinedTextField(h, { h = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                        label = { Text(stringResource(R.string.sk_height)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
        confirmButton = { TextButton(onClick = { custom?.let { onPick(it.first, it.second) } }, enabled = custom != null) { Text(stringResource(R.string.sk_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AddKeyDialog(onDismiss: () -> Unit, onPick: (NavButton) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sk_add_key)) },
        text = {
            Column {
                Text(stringResource(R.string.sk_add_key_sub), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                for (b in SKIN_KEYS) Text(stringResource(b.labelRes()), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(b) }.padding(12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
