/* Modified for AEmulator Sunset through 2026-10-03: rounded highlights, icon rotation,
 * held guest keys.
 * Modified for AEmulator Super Plus, 2026-10-12: Holo (Android 4.x) style. Theme glyph sets (2.x-5.x), colours, size. GPL-3.0; original attribution is preserved in NOTICE.md. */
package app.aemu.ui

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import app.aemu.core.NavButton
import app.aemu.core.TrackballMotion
import app.aemu.core.GlyphSet
import app.aemu.core.VmTheme
import app.aemu.core.VmThemes
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon

/**
 * One key of the VM's navigation bar, drawn in the [theme]'s glyph set (the app's own Material and Sunset looks, or
 * Gingerbread 2.x, Honeycomb 3.x, Holo 4.x, Lollipop 5.x) with its colours, size and press feedback.
 */
@Composable
internal fun HoloNavButton(button: NavButton, label: String, onClick: () -> Unit, onDown: () -> Unit, onUp: () -> Unit,
                           iconRotation: Float = 0f, theme: VmTheme = VmThemes.BUILT_IN[1]) {
    val hostView = androidx.compose.ui.platform.LocalView.current
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { up() } }
    val press = Color(theme.pressColor)
    // a pressed key: a soft glow (Honeycomb, Holo, Lollipop) or a filled rounded box
    val pressBackground = when {
        !pressed -> Modifier
        theme.pressGlow -> Modifier.background(Brush.radialGradient(listOf(press, press.copy(alpha = press.alpha * 0.28f), Color.Transparent)), CircleShape)
        else -> Modifier.background(press, RoundedCornerShape(8.dp))
    }
    val keySize = 48.dp * theme.iconScale
    Box(Modifier.size(keySize).then(pressBackground).semantics {
        contentDescription = label
        role = Role.Button
        // Accessibility activation remains one tap, without duplicating physical touches.
        onClick { hostView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onClick(); true }
    }.onKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode !in listOf(android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_SPACE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER)) false
        else when (native.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                if (native.repeatCount == 0) { pressed = true; hostView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); down() }
                true
            }
            android.view.KeyEvent.ACTION_UP -> { pressed = false; up(); true }
            else -> false
        }
    }.onFocusChanged { if (!it.hasFocus && pressed) { pressed = false; up() } }
        .focusable().pointerInput(button) {
        detectTapGestures(onPress = {
            val release = up
            pressed = true
            hostView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            down()
            try { tryAwaitRelease() } finally { pressed = false; release() }
        })
    }, contentAlignment = Alignment.Center) {
        if (theme.glyphs == GlyphSet.MATERIAL) Icon(when (button) {
            NavButton.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
            NavButton.HOME -> Icons.Rounded.Circle
            NavButton.RECENTS -> Icons.Rounded.CropSquare
            NavButton.MENU -> Icons.Rounded.Menu
            NavButton.SEARCH -> Icons.Rounded.Search
            NavButton.POWER -> Icons.Rounded.PowerSettingsNew
            NavButton.VOLUME_DOWN -> Icons.Rounded.VolumeDown
            NavButton.VOLUME_UP -> Icons.Rounded.VolumeUp
            NavButton.UP -> Icons.Rounded.KeyboardArrowUp
            NavButton.DOWN -> Icons.Rounded.KeyboardArrowDown
            NavButton.LEFT -> Icons.Rounded.KeyboardArrowLeft
            NavButton.RIGHT -> Icons.Rounded.KeyboardArrowRight
            NavButton.CENTER -> Icons.Rounded.RadioButtonChecked
        }, null, Modifier.size(24.dp * theme.iconScale).graphicsLayer { rotationZ = iconRotation },
            tint = theme.iconColor?.let { Color(it) } ?: androidx.compose.material3.LocalContentColor.current)
        else Canvas(Modifier.size(30.dp * theme.iconScale).graphicsLayer { rotationZ = iconRotation }) {
            NavGlyphs.draw(this, button, theme, pressed)
        }
    }
}

/** The line drawings of the keys on a 32x32 grid, per glyph set; keys a set has no own shape for use the Sunset ones. */
internal object NavGlyphs {
    fun draw(scope: DrawScope, button: NavButton, theme: VmTheme, pressed: Boolean) = with(scope) {
        val s = size.width / 32f
        val ink = theme.iconColor?.let { Color(it) } ?: Color.White
        val width = when (theme.glyphs) { GlyphSet.GINGERBREAD -> 2.6f; GlyphSet.HONEYCOMB -> 2.2f; GlyphSet.HOLO -> 1.7f; else -> 2f }
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
            moveTo(pts[0].first * s, pts[0].second * s)
            pts.drop(1).forEach { lineTo(it.first * s, it.second * s) }
            if (close) close()
        }
        // a line, with the theme's soft halo under it (Honeycomb, Holo)
        fun stroke(pathToDraw: Path) {
            if (theme.halo) drawPath(pathToDraw, ink.copy(alpha = if (pressed) 0.35f else 0.16f), style = Stroke(5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(pathToDraw, ink.copy(alpha = if (theme.halo) 0.92f else 1f), style = Stroke(width * s, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
        fun lines(vararg pts: Pair<Float, Float>, close: Boolean = false) = stroke(path(*pts, close = close))
        fun fill(pathToFill: Path) = drawPath(pathToFill, ink)
        fun ring(cx: Float, cy: Float, r: Float) = stroke(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx * s, cy * s), r * s)) })
        fun magnifier() { ring(13f, 13f, 7f); lines(18.5f to 18.5f, 26f to 26f) }
        // U-turn arrow: head pointing left, shaft along the top, half circle down the right (Gingerbread key, Holo bar)
        fun uTurn() {
            lines(11f to 6.5f, 5.5f to 12f, 11f to 17.5f)
            stroke(Path().apply {
                moveTo(5.5f * s, 12f * s); lineTo(19.5f * s, 12f * s)
                arcTo(androidx.compose.ui.geometry.Rect(13f * s, 12f * s, 26f * s, 25f * s), -90f, 180f, false)
                lineTo(9f * s, 25f * s)
            })
        }
        fun windows() {
            lines(6f to 11f, 21f to 11f, 21f to 26f, 6f to 26f, close = true)
            lines(10.5f to 11f, 10.5f to 6f, 26f to 6f, 26f to 21.5f, 21f to 21.5f)
        }
        val own: Boolean = when (theme.glyphs) {
            // Gingerbread 2.x: the hardware key icons - curved back, solid house, three menu bars, magnifier
            GlyphSet.GINGERBREAD -> when (button) {
                NavButton.BACK -> { uTurn(); true }
                NavButton.HOME -> { fill(path(4f to 16f, 16f to 6f, 28f to 16f, 25f to 16f, 25f to 26f, 7f to 26f, 7f to 16f, close = true)); true }
                NavButton.MENU -> { for (y in listOf(8f, 14.5f, 21f)) drawRect(ink, p(7f, y), androidx.compose.ui.geometry.Size(18f * s, 3.5f * s)); true }
                NavButton.SEARCH -> { magnifier(); true }
                else -> false
            }
            // Honeycomb 3.x: the angular "Tron" keys - hooked back arrow, tall house, stacked windows
            GlyphSet.HONEYCOMB -> when (button) {
                NavButton.BACK -> { lines(12f to 9f, 5f to 16f, 12f to 23f); lines(5f to 16f, 25f to 16f, 25f to 7f); true }
                NavButton.HOME -> { lines(6f to 14f, 16f to 5f, 26f to 14f); lines(9f to 12f, 9f to 27f, 23f to 27f, 23f to 12f); true }
                NavButton.RECENTS -> { windows(); true }
                else -> false
            }
            // Holo 4.x: the ICS/JB system bar
            GlyphSet.HOLO -> when (button) {
                NavButton.BACK -> { uTurn(); true }
                NavButton.HOME -> { lines(4.5f to 16f, 16f to 8f, 27.5f to 16f); lines(8f to 13.5f, 8f to 25.5f, 24f to 25.5f, 24f to 13.5f); true }
                NavButton.RECENTS -> { windows(); true }
                else -> false
            }
            // Lollipop 5.x: triangle, circle, square; menu as three dots
            GlyphSet.LOLLIPOP -> when (button) {
                NavButton.BACK -> { lines(22f to 7f, 8f to 16f, 22f to 25f, close = true); true }
                NavButton.HOME -> { ring(16f, 16f, 8f); true }
                NavButton.RECENTS -> { lines(8.5f to 8.5f, 23.5f to 8.5f, 23.5f to 23.5f, 8.5f to 23.5f, close = true); true }
                NavButton.MENU -> { for (y in listOf(9f, 16f, 23f)) drawCircle(ink, 2.2f * s, p(16f, y)); true }
                NavButton.SEARCH -> { magnifier(); true }
                else -> false
            }
            else -> false
        }
        if (own) return@with
        // Sunset outlines (also every key an era set leaves to them)
        when (button) {
            NavButton.BACK -> { lines(13f to 9f, 6f to 16f, 13f to 23f); lines(6f to 16f, 24f to 16f, 24f to 23f) }
            NavButton.HOME -> lines(6f to 16f, 16f to 8f, 26f to 16f, 26f to 25f, 6f to 25f, close = true)
            NavButton.RECENTS -> { lines(10f to 10f, 10f to 6f, 26f to 6f, 26f to 21f, 22f to 21f); lines(6f to 11f, 21f to 11f, 21f to 26f, 6f to 26f, close = true) }
            NavButton.MENU -> for (y in listOf(7f, 14f, 21f)) drawRect(ink, p(14f, y), androidx.compose.ui.geometry.Size(4f * s, 4f * s))
            NavButton.SEARCH -> magnifier()
            NavButton.POWER -> {
                stroke(Path().apply { arcTo(androidx.compose.ui.geometry.Rect(6f * s, 6f * s, 26f * s, 26f * s), -55f, 290f, true) })
                lines(16f to 3f, 16f to 15f)
            }
            NavButton.VOLUME_DOWN, NavButton.VOLUME_UP -> {
                lines(5f to 13f, 10f to 13f, 16f to 8f, 16f to 24f, 10f to 19f, 5f to 19f, close = true)
                lines(21f to 16f, 29f to 16f)
                if (button == NavButton.VOLUME_UP) lines(25f to 12f, 25f to 20f)
            }
            NavButton.UP -> lines(8f to 21f, 16f to 12f, 24f to 21f)
            NavButton.DOWN -> lines(8f to 11f, 16f to 20f, 24f to 11f)
            NavButton.LEFT -> lines(21f to 8f, 12f to 16f, 21f to 24f)
            NavButton.RIGHT -> lines(11f to 8f, 20f to 16f, 11f to 24f)
            NavButton.CENTER -> ring(16f, 16f, 7f)
        }
    }
}

/** Single-finger rolling surface; cancelling/multitouch never generates a click.
 * It lives above the nav/inset area and consumes its own stream, not guest touch. */
internal class TrackballView(context: Context) : View(context) {
    var motion = TrackballMotion(18f)
    var dpad = false
    var onRoll: (Int, Int) -> Unit = { _, _ -> }
    var onSelect: () -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointer = -1
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var rolling = false
    init { isClickable = true; isFocusable = true }
    override fun onDraw(canvas: AndroidCanvas) {
        val radius = minOf(width, height) / 2f - 5f * resources.displayMetrics.density
        paint.color = if (isPressed) 0xff555555.toInt() else 0xff222222.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        paint.color = 0xffdddddd.toInt(); paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
    override fun performClick(): Boolean { super.performClick(); onSelect(); return true }
    private fun roll(x: Float, y: Float) {
        if (!rolling && kotlin.math.hypot(x - startX, y - startY) > slop) rolling = true
        if (rolling) {
            val density = resources.displayMetrics.density
            val (dx, dy) = motion.move((x - lastX) / density, (y - lastY) / density, dpad)
            if (dx != 0 || dy != 0) onRoll(dx, dy)
            lastX = x; lastY = y
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                pointer = event.getPointerId(0)
                startX = event.x; startY = event.y; lastX = event.x; lastY = event.y
                rolling = false; isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) return true
                for (h in 0 until event.historySize) roll(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                roll(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP -> {
                if (pointer >= 0) roll(event.x, event.y)
                if (pointer >= 0 && !rolling) performClick()
                pointer = -1; isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                pointer = -1; rolling = true; isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate()
        return true
    }
}
