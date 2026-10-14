/* AEmulator Super Plus, 2026-10-11: 3D firmware library. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aemu.R
import app.aemu.core.GuestImage
import app.aemu.core.Screenshot
import app.aemu.core.VmPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The library as a carousel of 3D phones. Every firmware is a phone of its era (2.x hardware keys, 4.x capacitive
 * keys, 5.0+ on-screen navigation) showing its real last screen (thumbnail saved when leaving the VM) or, before the
 * first boot, a screen in its skin's colours. Neighbours turn away in perspective with a visible metal edge; the front
 * phone leans with the device, catches a moving glare, stands on a reflective floor, glows when it is running and
 * flips over on a tap to show its back (camera, brand, specifications). Ambient light in the firmware's colours fills
 * the stage. Motion follows the system setting: with animations off nothing tilts or animates.
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
    onDismissWarnings: (GuestImage) -> Unit = {},
) {
    if (images.isEmpty()) return
    val ctx = LocalContext.current
    val motion = remember { animationsOn(ctx) }
    val pager = rememberPagerState { images.size }
    val tilt = rememberDeviceTilt(motion)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val refresh = rememberResumeCount()
    val flipped = remember { mutableStateMapOf<String, Boolean>() }
    // the phone that was started zooms towards the viewer before the VM opens
    val launch = remember { Animatable(0f) }
    var launching by remember { mutableIntStateOf(-1) }
    // phones rise into place when the library opens
    val entrance = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) entrance.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)) }
    LaunchedEffect(pager.settledPage) { if (motion) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    val current = images.getOrNull(pager.currentPage) ?: images.first()
    val glow by animateColorAsState(skinColors(current).first(), tween(if (motion) 500 else 0), label = "ambient")

    fun start(img: GuestImage, lowPower: Boolean) {
        if (!motion) { onStart(img, lowPower); return }
        scope.launch {
            launching = images.indexOf(img)
            launch.snapTo(0f)
            launch.animateTo(1f, tween(260))
            onStart(img, lowPower)
            launch.snapTo(0f)
            launching = -1
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val phoneW = min(maxWidth * 0.52f, 230.dp)
        val side = (maxWidth - phoneW) / 2
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                // ambient light behind the stage, in the colours of the firmware in front, drifting with the tilt
                Canvas(Modifier.matchParentSize()) {
                    val c = Offset(size.width / 2 + tilt.y * 6f, size.height * 0.42f - tilt.x * 6f)
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), glow.copy(alpha = 0.12f), Color.Transparent),
                        center = c, radius = size.width * 0.62f), radius = size.width * 0.62f, center = c)
                }
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = side),
                    pageSpacing = 4.dp,
                    beyondViewportPageCount = 2,
                    key = { images[it].id },
                    modifier = Modifier.fillMaxWidth(),
                ) { page ->
                    val img = images[page]
                    val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1.5f, 1.5f)
                    val front = 1f - abs(offset).coerceAtMost(1f)
                    val isFlipped = flipped[img.id] == true
                    val flip by animateFloatAsState(if (isFlipped) 180f else 0f,
                        if (motion) spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow) else tween(0), label = "flip")
                    val zoom = if (page == launching) launch.value else 0f
                    // staggered rise: the phones further from the front arrive a little later
                    val rise = (entrance.value * 1.25f - abs(page - pager.currentPage) * 0.12f).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), contentAlignment = Alignment.TopCenter) {
                        Phone3D(
                            img, phoneW,
                            thumb = rememberThumbnail(img, refresh),
                            running = img.id == activeVmId,
                            isContainer = img.baseId.isNotEmpty(),
                            rotationY = offset * 38f + tilt.y * front + flip,
                            rotationX = tilt.x * front,
                            scale = (0.78f + 0.22f * front) * (1f + 0.25f * zoom),
                            alpha = (0.45f + 0.55f * front) * (1f - zoom * 0.6f) * rise,
                            lift = (1f - rise) * 60f,
                            glare = tilt.y * 0.04f + offset * 0.5f,
                            reflection = front,
                            onTap = if (front > 0.9f) ({ flipped[img.id] = !isFlipped }) else null,
                        )
                    }
                }
            }
            PageDots(images.size, pager.currentPage)
            Details(current, current.id == activeVmId, baseNameOf(current), ::start, onSettings, onRename, onDelete, onDismissWarnings)
        }
    }
}

// ------------------------------------------------------------------ the phone

private class Era(val corner: Dp, val aspect: Float, val keys: List<String>, val onScreenNav: Boolean, val lenses: Int)

private fun eraOf(api: Int) = when {
    api < 14 -> Era(26.dp, 1.72f, listOf("☰", "⌂", "↩", "⌕"), false, 1)       // 2.x: four hardware keys
    api < 21 -> Era(22.dp, 1.86f, listOf("↩", "⌂", "▭"), false, 1)            // 4.x: capacitive keys
    else -> Era(30.dp, 2.02f, emptyList(), true, 2)                           // 5.0+: navigation bar on screen
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

private val BODY = Brush.linearGradient(listOf(Color(0xFF3A3F47), Color(0xFF15181D), Color(0xFF2B3036)))
private val METAL = Brush.verticalGradient(listOf(Color(0xFF5C636D), Color(0xFFB8BEC6), Color(0xFF6B727C), Color(0xFF2A2E34)))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Phone3D(img: GuestImage, width: Dp, thumb: ImageBitmap?, running: Boolean, isContainer: Boolean,
                    rotationY: Float, rotationX: Float, scale: Float, alpha: Float, lift: Float,
                    glare: Float, reflection: Float, onTap: (() -> Unit)?) {
    val era = eraOf(img.api)
    val height = width * era.aspect
    val density = LocalDensity.current
    val shape = RoundedCornerShape(era.corner)
    // which face is towards the viewer, and how much of the edge shows (sin of the turn)
    val turn = ((rotationY % 360f) + 360f) % 360f
    val back = turn in 90f..270f
    val edge = sin(turn * PI.toFloat() / 180f) * 9f
    val label = "${img.name}, Android ${img.release}"
    val flipLabel = stringResource(if (back) R.string.lib3d_show_front else R.string.lib3d_show_back)
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset(y = lift.dp).semantics { contentDescription = label }) {
        Box(Modifier
            .graphicsLayer {
                this.rotationY = rotationY
                this.rotationX = rotationX
                scaleX = scale; scaleY = scale
                this.alpha = alpha
                cameraDistance = 14f * density.density
            }
            .then(if (onTap != null) Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClickLabel = flipLabel, onClick = onTap) else Modifier)) {
            if (running) RunningGlow(width, height, era.corner)
            // the metal side band, towards the far side of the turn
            Box(Modifier.offset(x = (-edge).dp, y = 2.dp).size(width, height).background(METAL, shape))
            Box(Modifier.size(width, height).clip(shape).background(BODY).border(1.dp, Color(0x66FFFFFF), shape)) {
                if (back) Box(Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }) { PhoneBack(img, era, width) }
                else PhoneFace(img, era, width, thumb, running, isContainer, glare)
            }
        }
        Reflection(width, height, shape, scale, alpha * reflection, back, thumb, img)
    }
}

/** A soft green halo pulsing around a phone whose VM is running. */
@Composable
private fun RunningGlow(width: Dp, height: Dp, corner: Dp) {
    val t = rememberInfiniteTransition(label = "running")
    val a by t.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "halo")
    Box(Modifier.offset((-6).dp, (-6).dp).size(width + 12.dp, height + 12.dp)
        .border(6.dp, Color(0xFF69F0AE).copy(alpha = a), RoundedCornerShape(corner + 6.dp)))
}

/** The floor below the phone: a faded, mirrored silhouette and a contact shadow. */
@Composable
private fun Reflection(width: Dp, height: Dp, shape: RoundedCornerShape, scale: Float, alpha: Float, back: Boolean,
                       thumb: ImageBitmap?, img: GuestImage) {
    Box(Modifier.width(width).height(height * 0.32f)) {
        Box(Modifier.matchParentSize().graphicsLayer {
            scaleX = scale; scaleY = -scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            this.alpha = alpha * 0.32f
            compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), endY = size.height), blendMode = BlendMode.DstIn)
        }) {
            Box(Modifier.size(width, height).clip(shape).background(BODY)) {
                if (!back && thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(10.dp))
                else if (!back) Box(Modifier.fillMaxSize().padding(10.dp).background(Brush.verticalGradient(skinColors(img))))
            }
        }
        Canvas(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha }) {
            val h = 26.dp.toPx()
            drawOval(Brush.radialGradient(listOf(Color(0x77000000), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width / 2),
                topLeft = Offset(size.width * 0.05f, -h / 2), size = Size(size.width * 0.9f, h))
        }
    }
}

@Composable
private fun PhoneFace(img: GuestImage, era: Era, width: Dp, thumb: ImageBitmap?, running: Boolean, isContainer: Boolean, glare: Float) {
    val top = if (era.onScreenNav) width * 0.07f else width * 0.16f
    val bottom = if (era.onScreenNav) width * 0.07f else width * 0.20f
    val side = if (era.onScreenNav) width * 0.035f else width * 0.06f
    val screenShape = RoundedCornerShape(if (era.onScreenNav) era.corner - 6.dp else 4.dp)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(top), contentAlignment = Alignment.Center) {
            Box(Modifier.width(width * 0.22f).height(4.dp).background(Color(0xFF0A0C0F), CircleShape))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = side).clip(screenShape)
            .background(Brush.verticalGradient(skinColors(img)))) {
            if (thumb != null) {
                Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Badges(running, isContainer, Modifier.align(Alignment.TopStart).padding(6.dp))
            } else StyledScreen(img, era, running, isContainer)
            // glass glare: a soft diagonal highlight that slides with the turn and the device tilt
            Canvas(Modifier.fillMaxSize()) {
                val shift = glare.coerceIn(-1f, 1f) * size.width
                drawRect(Brush.linearGradient(
                    0f to Color.Transparent, 0.45f to Color.White.copy(alpha = 0.16f), 0.55f to Color.White.copy(alpha = 0.04f), 1f to Color.Transparent,
                    start = Offset(-size.width + shift, 0f), end = Offset(size.width + shift, size.height)))
            }
        }
        Box(Modifier.height(bottom).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (era.keys.isNotEmpty()) Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                era.keys.forEach { Text(it, color = Color(0x99FFFFFF), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun Badges(running: Boolean, isContainer: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (running) RunningDot()
        if (isContainer) Text(stringResource(R.string.lib3d_container), color = Color.White, fontSize = 9.sp,
            modifier = Modifier.padding(start = 4.dp).background(Color(0x88000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Screen of a firmware that has never been seen running: version, name and skin in the skin's colours. */
@Composable
private fun StyledScreen(img: GuestImage, era: Era, running: Boolean, isContainer: Boolean) {
    Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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

/** The back cover: camera module of the era, brand, and the firmware's specifications. */
@Composable
private fun PhoneBack(img: GuestImage, era: Era, width: Dp) {
    val ctx = LocalContext.current
    val accent = skinColors(img).first()
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2E333A), Color(0xFF16191E)))).padding(14.dp)) {
        // camera module: one lens + flash on older phones, two lenses in a raised island on newer ones
        Box(Modifier.background(Color(0xFF0B0D10), RoundedCornerShape(if (era.lenses > 1) 14.dp else 22.dp)).padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(era.lenses) {
                    Box(Modifier.padding(2.dp).size(width * 0.13f).background(
                        Brush.radialGradient(listOf(Color(0xFF3D5A80), Color(0xFF0A0F18), Color(0xFF30343A))), CircleShape)
                        .border(2.dp, Color(0xFF50565F), CircleShape))
                }
                Box(Modifier.padding(start = 4.dp).size(width * 0.05f).background(Color(0xFFFFF3C4), CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(img.brand.ifBlank { "Android" }.uppercase(), color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().background(Color(0x22000000), RoundedCornerShape(10.dp)).padding(10.dp)) {
            Spec("Android", "${img.release} · API ${img.api}", accent)
            Spec("Skin", LibraryLabels.compactSkin(img.skin), accent)
            Spec("Screen", "${img.settings.width}×${img.settings.height}", accent)
            Spec("Size", Formatter.formatShortFileSize(ctx, img.sizeBytes), accent)
            if (img.model.isNotBlank()) Spec("Model", img.model, accent)
        }
    }
}

@Composable
private fun Spec(name: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(name, color = accent, fontSize = 9.sp, modifier = Modifier.width(46.dp))
        Text(value, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            val w by animateFloatAsState(if (on) 18f else 6f, label = "dot")
            Box(Modifier.padding(3.dp).height(6.dp).width(w.dp)
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

// ------------------------------------------------------------------ the firmware in front

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Details(img: GuestImage, active: Boolean, baseName: String?, onStart: (GuestImage, Boolean) -> Unit,
                    onSettings: (GuestImage) -> Unit, onRename: (GuestImage) -> Unit, onDelete: (GuestImage) -> Unit,
                    onDismissWarnings: (GuestImage) -> Unit) {
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
        img.warnings.firstOrNull()?.let { WarningLine(it, { onDismissWarnings(img) }, center = true) }
        Text(stringResource(R.string.lib3d_tap_hint), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
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

// ------------------------------------------------------------------ data and sensors

/** Grows every time the library comes back to the screen (e.g. from a VM), so new thumbnails are picked up. */
@Composable
private fun rememberResumeCount(): Int {
    val owner = LocalLifecycleOwner.current
    var n by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) n++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return n
}

/** The firmware's last screen (Screenshot.THUMB in its folder), decoded off the main thread; null before the first boot. */
@Composable
private fun rememberThumbnail(img: GuestImage, refresh: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, img.id, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(VmPaths(ctx, img.id).dir, Screenshot.THUMB)
                if (f.isFile) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
            }.getOrNull()
        } ?: value
    }
    return thumb
}

private fun animationsOn(ctx: Context): Boolean = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}.getOrDefault(true)

private class Tilt { var x by mutableFloatStateOf(0f); var y by mutableFloatStateOf(0f) }

/** Degrees the front phone leans, from the accelerometer (low-pass filtered); stays zero without [motion]. */
@Composable
private fun rememberDeviceTilt(motion: Boolean): Tilt {
    val ctx = LocalContext.current
    val tilt = remember { Tilt() }
    DisposableEffect(ctx, motion) {
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
        if (motion && acc != null) sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}

