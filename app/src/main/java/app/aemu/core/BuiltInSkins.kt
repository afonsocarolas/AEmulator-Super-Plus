/* AEmulator Super Plus, 2026-10-14: built-in device skins. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.File

/**
 * Device skins that come with the app: original artwork drawn here in code (no photos, no logos or brand names),
 * styled after the eras of Android phones. Each one knows its screen area, the resolution it was drawn for and its
 * hardware keys, so picking it in the theme list is all it takes. The picture is rendered once into filesDir/skins.
 */
object BuiltInSkins {
    enum class Glyph { CALL, END, HOME, MENU, BACK, SEARCH, TRACKBALL, OVAL_HOME }

    /** a key on the picture, in pixels of the picture; [button] null = drawn only (Call) */
    class Key(val glyph: Glyph, val l: Int, val t: Int, val r: Int, val b: Int, val button: NavButton?)

    class Art(
        val id: String, val name: String, val w: Int, val h: Int,
        /** the guest screen in pixels of the picture */
        val sl: Int, val st: Int, val sr: Int, val sb: Int,
        val resW: Int, val resH: Int,
        val body: Int, val bodyDark: Int, val corner: Float,
        val keys: List<Key>,
        /** a slider: the lower part (chin) tilts back, as on the first Android phone */
        val chinTop: Int = 0,
    ) {
        val file get() = "builtin-$id-v$VERSION.png"
        val screen get() = SkinRect(sl.toFloat() / w, st.toFloat() / h, sr.toFloat() / w, sb.toFloat() / h)
        fun skinKeys() = keys.filter { it.button != null }.map {
            SkinKey(it.button!!, SkinRect(it.l.toFloat() / w, it.t.toFloat() / h, it.r.toFloat() / w, it.b.toFloat() / h))
        }
    }

    /** bump when the drawings change: the file name changes, so old renders are not reused */
    const val VERSION = 1

    val ART = listOf(
        // 2008: HVGA under a tall screen, a tilted chin with Call / Home / Menu / Back / End and a trackball
        Art("slider2008", "Slider 2008", 760, 1500, 76, 150, 684, 1062, 320, 480,
            0xFF2B2B2E.toInt(), 0xFF141416.toInt(), 70f, chinTop = 1110, keys = listOf(
                Key(Glyph.CALL, 70, 1160, 190, 1250, null),
                Key(Glyph.HOME, 200, 1160, 310, 1250, NavButton.HOME),
                Key(Glyph.MENU, 450, 1160, 560, 1250, NavButton.MENU),
                Key(Glyph.BACK, 200, 1290, 310, 1380, NavButton.BACK),
                Key(Glyph.END, 570, 1160, 690, 1250, NavButton.POWER),
                Key(Glyph.TRACKBALL, 325, 1230, 435, 1340, NavButton.CENTER),
                Key(Glyph.SEARCH, 450, 1290, 560, 1380, NavButton.SEARCH),
            )),
        // 2010: WVGA, four touch keys under the glass
        Art("candybar2010", "Candybar 2010", 760, 1460, 61, 175, 699, 1239, 480, 800,
            0xFF1E1F22.toInt(), 0xFF0B0B0D.toInt(), 80f, keys = listOf(
                Key(Glyph.BACK, 70, 1290, 200, 1380, NavButton.BACK),
                Key(Glyph.MENU, 230, 1290, 360, 1380, NavButton.MENU),
                Key(Glyph.HOME, 400, 1290, 530, 1380, NavButton.HOME),
                Key(Glyph.SEARCH, 560, 1290, 690, 1380, NavButton.SEARCH),
            )),
        // 2011: WVGA, an oval physical Home key between Menu and Back touch keys
        Art("homekey2011", "Home button 2011", 760, 1480, 53, 170, 707, 1260, 480, 800,
            0xFF15161A.toInt(), 0xFF050507.toInt(), 90f, keys = listOf(
                Key(Glyph.MENU, 90, 1320, 210, 1420, NavButton.MENU),
                Key(Glyph.OVAL_HOME, 280, 1335, 480, 1405, NavButton.HOME),
                Key(Glyph.BACK, 550, 1320, 670, 1420, NavButton.BACK),
            )),
        // 2013: 720p, thin bezels and no keys: the on-screen bar stays
        Art("modern2013", "Modern 2013", 760, 1420, 38, 100, 722, 1316, 720, 1280,
            0xFF0F1012.toInt(), 0xFF000000.toInt(), 96f, keys = emptyList()),
    )

    /** the theme-list entries; their pictures are rendered on first use */
    fun themes(): List<VmTheme> = ART.map { a ->
        VmTheme("skin-${a.id}", a.name, GlyphSet.LOLLIPOP, barColor = 0xFF000000.toInt(), iconColor = 0xFFFFFFFF.toInt(),
            pressColor = 0x40FFFFFF, pressGlow = true, frameColor = 0xFF101114.toInt(), frameImage = a.file,
            skinScreen = a.screen, skinWidth = a.resW, skinHeight = a.resH, skinKeys = a.skinKeys(), builtIn = true)
    }

    fun isBuiltInFile(name: String) = name.startsWith("builtin-") && name.endsWith(".png")

    /** the rendered picture for a built-in skin file name, drawn now if it is not there yet */
    fun file(dir: File, name: String): File? {
        val art = ART.firstOrNull { it.file == name } ?: return null
        val f = File(dir, name)
        if (f.isFile && f.length() > 0) return f
        return runCatching {
            dir.mkdirs()
            val bmp = render(art)
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            check(tmp.renameTo(f))
            f
        }.getOrNull()
    }

    // ---------------------------------------------------------------- drawing

    fun render(a: Art): Bitmap {
        val bmp = Bitmap.createBitmap(a.w, a.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = a.w.toFloat(); val h = a.h.toFloat()
        // body: a soft vertical gradient, a thin light rim
        p.shader = LinearGradient(0f, 0f, w, h, a.body, a.bodyDark, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(6f, 6f, w - 6f, h - 6f), a.corner, a.corner, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = 0x55FFFFFF
        c.drawRoundRect(RectF(8f, 8f, w - 8f, h - 8f), a.corner - 2, a.corner - 2, p)
        p.style = Paint.Style.FILL
        // a slider's chin: a darker, slightly lifted band with its own rim
        if (a.chinTop > 0) {
            p.shader = LinearGradient(0f, a.chinTop.toFloat(), 0f, h, 0xFF38383C.toInt(), 0xFF1C1C1F.toInt(), Shader.TileMode.CLAMP)
            val chin = Path().apply {
                moveTo(10f, a.chinTop.toFloat()); lineTo(w - 10f, a.chinTop.toFloat())
                lineTo(w - 10f, h - a.corner); quadTo(w - 10f, h - 10f, w - a.corner, h - 10f)
                lineTo(a.corner, h - 10f); quadTo(10f, h - 10f, 10f, h - a.corner); close()
            }
            c.drawPath(chin, p)
            p.shader = null
            p.color = 0x66000000; c.drawRect(10f, a.chinTop - 6f, w - 10f, a.chinTop.toFloat(), p)
        }
        // glass around the screen, the screen itself (the guest covers it), earpiece and camera
        p.color = 0xFF050506.toInt()
        c.drawRoundRect(RectF(a.sl - 16f, a.st - 16f, a.sr + 16f, a.sb + 16f), 18f, 18f, p)
        p.color = 0xFF000000.toInt()
        c.drawRect(a.sl.toFloat(), a.st.toFloat(), a.sr.toFloat(), a.sb.toFloat(), p)
        val cx = w / 2
        p.color = 0xFF3A3B40.toInt()
        c.drawRoundRect(RectF(cx - 70f, a.st / 2f - 8f, cx + 70f, a.st / 2f + 8f), 8f, 8f, p)
        p.color = 0xFF202128.toInt(); c.drawCircle(cx + 130f, a.st / 2f, 13f, p)
        p.color = 0xFF3A4A66.toInt(); c.drawCircle(cx + 130f, a.st / 2f, 6f, p)
        for (k in a.keys) drawKey(c, k)
        return bmp
    }

    private fun drawKey(c: Canvas, k: Key) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF(k.l.toFloat(), k.t.toFloat(), k.r.toFloat(), k.b.toFloat())
        val cx = r.centerX(); val cy = r.centerY()
        val s = minOf(r.width(), r.height()) * 0.32f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s * 0.22f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = 0xFFE8E8EC.toInt()
        }
        when (k.glyph) {
            Glyph.TRACKBALL -> {
                val rad = minOf(r.width(), r.height()) / 2f
                p.shader = RadialGradient(cx - rad * 0.3f, cy - rad * 0.3f, rad * 1.3f,
                    intArrayOf(0xFFF4F4F6.toInt(), 0xFF8E9096.toInt(), 0xFF3C3D42.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, rad, p)
                return
            }
            Glyph.OVAL_HOME -> {
                p.color = 0xFF2A2B30.toInt(); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF55565C.toInt()
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
                return
            }
            Glyph.CALL, Glyph.END -> {
                p.color = if (k.glyph == Glyph.CALL) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
                c.drawRoundRect(r, 18f, 18f, p)
                ink.color = 0xFFFFFFFF.toInt()
                // a handset: an arc with two ends
                val rr = RectF(cx - s, cy - s * 0.6f, cx + s, cy + s * 1.2f)
                c.drawArc(rr, if (k.glyph == Glyph.CALL) 200f else 20f, 140f, false, ink)
                return
            }
            else -> {}
        }
        // touch / chin keys: a faint key plate, then the symbol
        if (k.l >= 0) {
            p.color = 0x22FFFFFF
            c.drawRoundRect(r, 16f, 16f, p)
        }
        when (k.glyph) {
            Glyph.HOME -> {
                val path = Path().apply {
                    moveTo(cx - s, cy); lineTo(cx, cy - s); lineTo(cx + s, cy)
                    moveTo(cx - s * 0.7f, cy - s * 0.2f); lineTo(cx - s * 0.7f, cy + s * 0.8f); lineTo(cx + s * 0.7f, cy + s * 0.8f); lineTo(cx + s * 0.7f, cy - s * 0.2f)
                }
                c.drawPath(path, ink)
            }
            Glyph.MENU -> for (i in -1..1) c.drawLine(cx - s, cy + i * s * 0.6f, cx + s, cy + i * s * 0.6f, ink)
            Glyph.BACK -> {
                val path = Path().apply {
                    moveTo(cx - s * 0.3f, cy - s * 0.9f); lineTo(cx - s, cy - s * 0.3f); lineTo(cx - s * 0.3f, cy + s * 0.3f)
                    moveTo(cx - s, cy - s * 0.3f); lineTo(cx + s * 0.4f, cy - s * 0.3f)
                    quadTo(cx + s, cy - s * 0.3f, cx + s, cy + s * 0.3f); quadTo(cx + s, cy + s * 0.9f, cx + s * 0.4f, cy + s * 0.9f)
                    lineTo(cx - s * 0.2f, cy + s * 0.9f)
                }
                c.drawPath(path, ink)
            }
            Glyph.SEARCH -> {
                c.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.6f, ink)
                c.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s, cy + s, ink)
            }
            else -> {}
        }
    }
}
