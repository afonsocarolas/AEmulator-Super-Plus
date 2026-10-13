/* AEmulator Super Plus, 2026-10-13: device skins for VM screen themes. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** A rectangle on a skin picture, in fractions of the picture's width and height (0..1), so it survives any scaling. */
data class SkinRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width get() = r - l
    val height get() = b - t
    fun contains(x: Float, y: Float) = x in l..r && y in t..b
    fun normalized(): SkinRect {
        val x0 = minOf(l, r).coerceIn(0f, 1f); val x1 = maxOf(l, r).coerceIn(0f, 1f)
        val y0 = minOf(t, b).coerceIn(0f, 1f); val y1 = maxOf(t, b).coerceIn(0f, 1f)
        return SkinRect(x0, y0, maxOf(x1, x0 + MIN), maxOf(y1, y0 + MIN))
    }
    fun moved(dx: Float, dy: Float): SkinRect {
        val x = (l + dx).coerceIn(0f, 1f - width); val y = (t + dy).coerceIn(0f, 1f - height)
        return SkinRect(x, y, x + width, y + height)
    }
    fun toJson(): JSONArray = JSONArray().put(l.toDouble()).put(t.toDouble()).put(r.toDouble()).put(b.toDouble())

    companion object {
        const val MIN = 0.02f
        /** where a new screen area starts: a typical phone screen inside a typical phone picture */
        val DEFAULT_SCREEN = SkinRect(0.12f, 0.14f, 0.88f, 0.84f)
        fun fromJson(a: JSONArray?): SkinRect? = a?.takeIf { it.length() == 4 }?.let {
            SkinRect(it.optDouble(0).toFloat(), it.optDouble(1).toFloat(), it.optDouble(2).toFloat(), it.optDouble(3).toFloat())
                .takeIf { r -> listOf(r.l, r.t, r.r, r.b).all { v -> !v.isNaN() } }?.normalized()
        }
    }
}

/** A hardware key drawn on the skin (Back, Home, Menu...): touching that part of the picture presses the key. */
data class SkinKey(val button: NavButton, val area: SkinRect) {
    fun toJson(): JSONObject = JSONObject().put("key", button.id).put("area", area.toJson())

    companion object {
        fun fromJson(o: JSONObject): SkinKey? {
            val button = NavButton.entries.firstOrNull { it.id == o.optString("key") } ?: return null
            return SkinRect.fromJson(o.optJSONArray("area"))?.let { SkinKey(button, it) }
        }
    }
}

object DeviceSkin {
    /** Screen sizes offered by "Pick resolution": what real phones of each Android era shipped with. */
    val RESOLUTIONS = listOf(240 to 320, 320 to 480, 480 to 800, 480 to 854, 540 to 960, 720 to 1280, 768 to 1280, 800 to 1280, 1080 to 1920)

    /** Pixel rectangles of one layout, in the coordinates of the area the skin is drawn in. */
    data class Layout(val image: Box, val screen: Box, val guest: Box, val keys: List<Pair<NavButton, Box>>)
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
    }

    /**
     * Fits the skin picture ([imageW] x [imageH]) into [areaW] x [areaH] without cropping, then fits the guest screen
     * ([guestW] x [guestH], aspect kept) into the skin's [screen] rectangle. [offsetX]/[offsetY] shift everything (room
     * kept for the on-screen bar).
     */
    fun layout(areaW: Int, areaH: Int, imageW: Int, imageH: Int, screen: SkinRect, keys: List<SkinKey>,
               guestW: Int, guestH: Int, offsetX: Int = 0, offsetY: Int = 0): Layout {
        val scale = minOf(areaW.toFloat() / imageW.coerceAtLeast(1), areaH.toFloat() / imageH.coerceAtLeast(1))
        val iw = imageW * scale; val ih = imageH * scale
        val ix = offsetX + (areaW - iw) / 2f; val iy = offsetY + (areaH - ih) / 2f
        fun box(r: SkinRect) = Box((ix + r.l * iw).roundToInt(), (iy + r.t * ih).roundToInt(),
            (r.width * iw).roundToInt().coerceAtLeast(1), (r.height * ih).roundToInt().coerceAtLeast(1))
        val s = box(screen)
        val g = minOf(s.width.toFloat() / guestW.coerceAtLeast(1), s.height.toFloat() / guestH.coerceAtLeast(1))
        val gw = (guestW * g).roundToInt().coerceAtLeast(1); val gh = (guestH * g).roundToInt().coerceAtLeast(1)
        return Layout(Box(ix.roundToInt(), iy.roundToInt(), iw.roundToInt(), ih.roundToInt()), s,
            Box(s.left + (s.width - gw) / 2, s.top + (s.height - gh) / 2, gw, gh), keys.map { it.button to box(it.area) })
    }

    /**
     * Finds the screen of a phone picture: the region connected to the picture's centre that is see-through (most
     * emulator skins cut the screen out) or else one flat colour (a dark glass screen). [pixels] are ARGB, row by row;
     * pass a downscaled copy, a few hundred pixels wide is plenty. Returns null when no such region covers at least
     * 5% of the picture.
     */
    fun findScreen(pixels: IntArray, width: Int, height: Int): SkinRect? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val center = pixels[(height / 2) * width + width / 2]
        val transparent = (center ushr 24) < 48
        fun inside(p: Int): Boolean =
            if (transparent) (p ushr 24) < 48
            else (p ushr 24) > 200 && abs(((p shr 16) and 255) - ((center shr 16) and 255)) +
                abs(((p shr 8) and 255) - ((center shr 8) and 255)) + abs((p and 255) - (center and 255)) < 48
        val seen = BooleanArray(width * height)
        val stack = IntArray(width * height)
        var top = 0
        stack[top++] = (height / 2) * width + width / 2
        seen[stack[0]] = true
        var minX = width; var minY = height; var maxX = -1; var maxY = -1; var count = 0
        while (top > 0) {
            val i = stack[--top]
            val x = i % width; val y = i / width
            count++
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            fun visit(j: Int) { if (!seen[j] && inside(pixels[j])) { seen[j] = true; stack[top++] = j } }
            if (x > 0) visit(i - 1); if (x < width - 1) visit(i + 1)
            if (y > 0) visit(i - width); if (y < height - 1) visit(i + width)
        }
        if (count < width * height / 20) return null
        // the flood reached the picture's edge: that's the background around the phone, not its screen
        if (minX == 0 && maxX == width - 1 || minY == 0 && maxY == height - 1) return null
        return SkinRect(minX.toFloat() / width, minY.toFloat() / height, (maxX + 1f) / width, (maxY + 1f) / height)
    }

    /** Keeps [rect]'s centre and width, changes its height so the screen has the aspect of [w] x [h] on this picture. */
    fun withAspect(rect: SkinRect, w: Int, h: Int, imageW: Int, imageH: Int): SkinRect {
        if (w <= 0 || h <= 0 || imageW <= 0 || imageH <= 0) return rect
        val height = (rect.width * imageW * h / w / imageH).coerceAtMost(1f)
        val cy = (rect.t + rect.b) / 2
        val t = (cy - height / 2).coerceIn(0f, 1f - height)
        return SkinRect(rect.l, t, rect.r, t + height)
    }
}
