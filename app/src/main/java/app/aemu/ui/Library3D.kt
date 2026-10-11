/* AEmulator Super Plus, 2026-10-11: 3D firmware library. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.aemu.R
import app.aemu.core.GuestImage
import kotlin.math.abs

/**
 * The library as a carousel of 3D phones: every firmware is drawn as a phone of its era (2.x hardware keys,
 * 4.x capacitive keys, 5.0+ on-screen navigation) in the colours of its skin. Swiping turns the neighbours away
 * in perspective; the phone in front tilts with the device (accelerometer), unless the system has animations off.
 * Below it, the details and actions of the firmware in front.
 */
@Composable
internal fun Library3D(
    images: List<GuestImage>,
    activeVmId: String?,
    baseNameOf: (GuestImage) -> String?,
    onStart: (GuestImage, Boolean) -> Unit,
    onSettings: (GuestImage) -> Unit,
    onRename: (GuestImage) -> Unit,
    onDelete: (GuestImage) -> Unit,
) {
    if (images.isEmpty()) return
    val pager = rememberPagerState { images.size }
    val tilt = rememberDeviceTilt()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val phoneW = min(maxWidth * 0.52f, 230.dp)
        val side = (maxWidth - phoneW) / 2
        Column(Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = side),
                pageSpacing = 4.dp,
                beyondViewportPageCount = 2,
                key = { images[it].id },
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val img = images[page]
                // -1 .. 1 around the page in front
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1.5f, 1.5f)
                val front = 1f - abs(offset).coerceAtMost(1f)
                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.TopCenter) {
                    Phone3D(
                        img, phoneW, running = img.id == activeVmId, isContainer = img.baseId.isNotEmpty(),
                        rotationY = offset * 38f + tilt.y * front,
                        rotationX = tilt.x * front,
                        scale = 0.78f + 0.22f * front,
                        alpha = 0.45f + 0.55f * front,
                    )
                }
            }
            PageDots(images.size, pager.currentPage)
            val current = images.getOrNull(pager.currentPage) ?: images.first()
            Details(current, current.id == activeVmId, baseNameOf(current), onStart, onSettings, onRename, onDelete)
        }
    }
}

// ------------------------------------------------------------------ the phone

private class Era(val corner: Dp, val aspect: Float, val keys: List<String>, val onScreenNav: Boolean)

private fun eraOf(api: Int) = when {
    api < 14 -> Era(26.dp, 1.72f, listOf("☰", "⌂", "↩", "⌕"), false)       // 2.x: four hardware keys
    api < 21 -> Era(22.dp, 1.86f, listOf("↩", "⌂", "▭"), false)            // 4.x: capacitive keys
    else -> Era(30.dp, 2.02f, emptyList(), true)                           // 5.0+: navigation bar on screen
}

private fun skinColors(img: GuestImage): List<Color> {
    val s = (img.skin + " " + img.brand).lowercase()
    return when {
        "touchwiz" in s || "samsung" in s -> listOf(Color(0xFF1E88E5), Color(0xFF0D2B66))
        "sense" in s || "htc" in s -> listOf(Color(0xFF43A047), Color(0xFF0B3D16))
        "miui" in s || "xiaomi" in s -> listOf(Color(0xFFFF8F00), Color(0xFF6D2A00))
        "sony" in s || "xperia" in s -> listOf(Color(0xFF8E24AA), Color(0xFF2A0B3D))
        "motorola" in s || "moto" in s -> listOf(Color(0xFFE53935), Color(0xFF4A0A0A))
        "lg" in s -> listOf(Color(0xFFD81B60), Color(0xFF4A0A24))
        "glass" in s || "tv" in s || "wear" in s -> listOf(Color(0xFF78909C), Color(0xFF1C262B))
        img.api < 14 -> listOf(Color(0xFF7CB342), Color(0xFF111A08))           // Gingerbread green on black
        img.api < 21 -> listOf(Color(0xFF33B5E5), Color(0xFF02141B))           // Holo blue
        else -> listOf(Color(0xFF26A69A), Color(0xFF00251F))                   // Material teal
    }
}

@Composable
private fun Phone3D(img: GuestImage, width: Dp, running: Boolean, isContainer: Boolean,
                    rotationY: Float, rotationX: Float, scale: Float, alpha: Float) {
    val era = eraOf(img.api)
    val height = width * era.aspect
    val density = LocalDensity.current
    val label = "${img.name}, Android ${img.release}"
    // the body's edge, seen from the side it turns away from
    val edge = (rotationY / 38f).coerceIn(-1f, 1f) * 7f
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = label }) {
        Box(Modifier.graphicsLayer {
            this.rotationY = rotationY
            this.rotationX = rotationX
            scaleX = scale; scaleY = scale
            this.alpha = alpha
            cameraDistance = 14f * density.density
        }) {
            // thickness: a darker copy of the body, shifted towards the far side
            Box(Modifier.offset(x = (-edge).dp, y = 3.dp).size(width, height)
                .background(Color(0xFF07090C), RoundedCornerShape(era.corner)))
            Box(Modifier.size(width, height)
                .background(Brush.linearGradient(listOf(Color(0xFF3A3F47), Color(0xFF15181D), Color(0xFF2B3036)),
                    start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)), RoundedCornerShape(era.corner))
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(era.corner))) {
                PhoneFace(img, era, width, height, running, isContainer)
            }
        }
        // floor shadow, smaller and fainter for the phones turned away
        Canvas(Modifier.width(width).height(26.dp).graphicsLayer { scaleX = scale; this.alpha = alpha }) {
            drawOval(Brush.radialGradient(listOf(Color(0x66000000), Color.Transparent), center = Offset(size.width / 2, size.height / 2),
                radius = size.width / 2), topLeft = Offset(0f, 0f), size = Size(size.width, size.height))
        }
    }
}

@Composable
private fun PhoneFace(img: GuestImage, era: Era, width: Dp, height: Dp, running: Boolean, isContainer: Boolean) {
    val top = if (era.onScreenNav) width * 0.07f else width * 0.16f
    val bottom = if (era.onScreenNav) width * 0.07f else width * 0.20f
    val side = if (era.onScreenNav) width * 0.035f else width * 0.06f
    val colors = skinColors(img)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // earpiece
        Box(Modifier.height(top), contentAlignment = Alignment.Center) {
            Box(Modifier.width(width * 0.22f).height(4.dp).background(Color(0xFF0A0C0F), CircleShape))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = side)
            .background(Brush.verticalGradient(colors), RoundedCornerShape(if (era.onScreenNav) era.corner - 6.dp else 4.dp))) {
            ScreenContent(img, era, running, isContainer)
        }
        // hardware / capacitive keys below the screen on older phones
        Box(Modifier.height(bottom).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (era.keys.isNotEmpty()) Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                era.keys.forEach { Text(it, color = Color(0x99FFFFFF), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun ScreenContent(img: GuestImage, era: Era, running: Boolean, isContainer: Boolean) {
    Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // status bar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (running) RunningDot()
            Spacer(Modifier.weight(1f))
            Text("▂▄▆ 12:00", color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp)
        }
        Spacer(Modifier.weight(0.8f))
        Text(img.release.ifBlank { "?" }, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light, maxLines = 1)
        Text("Android", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Text(img.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(LibraryLabels.compactSkin(img.skin), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
        if (isContainer) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.lib3d_container), color = Color.White, fontSize = 9.sp,
                modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.weight(1f))
        if (era.onScreenNav) Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            listOf("◁", "○", "□").forEach { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun RunningDot() {
    val t = rememberInfiniteTransition(label = "running")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(7.dp).graphicsLayer { alpha = a }.background(Color(0xFF69F0AE), CircleShape))
}

@Composable
private fun PageDots(count: Int, current: Int) {
    if (count < 2) return
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
        for (i in 0 until count.coerceAtMost(12)) {
            val on = i == current.coerceAtMost(11)
            Box(Modifier.padding(3.dp).size(if (on) 8.dp else 6.dp)
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

// ------------------------------------------------------------------ the firmware in front

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Details(img: GuestImage, active: Boolean, baseName: String?, onStart: (GuestImage, Boolean) -> Unit,
                    onSettings: (GuestImage) -> Unit, onRename: (GuestImage) -> Unit, onDelete: (GuestImage) -> Unit) {
    val ctx = LocalContext.current
    val lowPowerLabel = stringResource(R.string.lpm_start)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(img.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(img.displayVersion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            AssistChip(onClick = {}, label = { Text(LibraryLabels.compactSkin(img.skin), maxLines = 1) })
            AssistChip(onClick = {}, label = { Text("${img.settings.width}×${img.settings.height}", maxLines = 1) })
            AssistChip(onClick = {}, label = { Text(Formatter.formatShortFileSize(ctx, img.sizeBytes), maxLines = 1) })
        }
        if (baseName != null) Text(stringResource(R.string.container_of, baseName), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (img.lastBootMs > 0) Text(stringResource(R.string.last_boot, (img.lastBootMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        img.warnings.firstOrNull()?.let {
            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = ButtonDefaults.shape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.height(48.dp).combinedClickable(role = Role.Button, onClick = { onStart(img, false) },
                    onLongClickLabel = if (!active) lowPowerLabel else null,
                    onLongClick = if (!active) ({ onStart(img, true) }) else null)) {
                Row(Modifier.padding(ButtonDefaults.ButtonWithIconContentPadding), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (active) R.string.open_vm else R.string.start))
                }
            }
            FilledTonalIconButton(onClick = { onSettings(img) }) { Icon(Icons.Rounded.Tune, stringResource(R.string.settings)) }
            FilledTonalIconButton(onClick = { onRename(img) }) { Icon(Icons.Rounded.Edit, stringResource(R.string.rename)) }
            FilledTonalIconButton(onClick = { onDelete(img) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
        }
    }
}

// ------------------------------------------------------------------ device tilt

private class Tilt { var x by mutableFloatStateOf(0f); var y by mutableFloatStateOf(0f) }

/** Degrees the front phone leans, from the accelerometer (low-pass filtered); zero when animations are off. */
@Composable
private fun rememberDeviceTilt(): Tilt {
    val ctx = LocalContext.current
    val tilt = remember { Tilt() }
    DisposableEffect(ctx) {
        val animationsOff = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // held upright and a little back, the phone reads about (0, 7, 7): lean relative to that
                val ty = (-e.values[0] * 1.6f).coerceIn(-12f, 12f)
                val tx = ((e.values[1] - 7f) * 1.4f).coerceIn(-10f, 10f)
                tilt.y += (ty - tilt.y) * 0.12f
                tilt.x += (tx - tilt.x) * 0.12f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (!animationsOff && acc != null) sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}
