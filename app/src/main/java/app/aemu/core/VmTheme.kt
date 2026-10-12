/* AEmulator Super Plus, 2026-10-12: VM screen themes. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Shapes of the navigation keys: the app's own sets and one per Android era. */
enum class GlyphSet { MATERIAL, SUNSET, GINGERBREAD, HONEYCOMB, HOLO, LOLLIPOP }

/**
 * Look of the VM screen around the guest: the navigation bar (key shapes, colours, press feedback, size) and the
 * frame (colour or picture behind the guest screen, rounded screen corners). Colours are ARGB; a null bar or icon
 * colour means the app theme's own colour (the "Original" look). Never changes anything inside the guest.
 */
data class VmTheme(
    val id: String,
    val name: String,
    val glyphs: GlyphSet = GlyphSet.SUNSET,
    val barColor: Int? = 0xF20B0B0B.toInt(),
    val iconColor: Int? = 0xFFFFFFFF.toInt(),
    val pressColor: Int = 0x2EFFFFFF,
    /** pressed key: a radial glow (Honeycomb/Holo/Lollipop) instead of a filled rounded box */
    val pressGlow: Boolean = false,
    /** soft halo under each key line (Honeycomb/Holo) */
    val halo: Boolean = false,
    /** floating rounded bar (Original) instead of a flat edge-to-edge one */
    val roundedBar: Boolean = false,
    /** key size, 0.7..1.5 of the normal */
    val iconScale: Float = 1f,
    val frameColor: Int = 0xFF000000.toInt(),
    /** picture behind the guest screen: file name in [VmThemes.dir], null = plain [frameColor] */
    val frameImage: String? = null,
    /** rounded corners of the guest screen, dp */
    val screenCorner: Int = 0,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("glyphs", glyphs.name)
        .put("barColor", barColor ?: JSONObject.NULL).put("iconColor", iconColor ?: JSONObject.NULL)
        .put("pressColor", pressColor).put("pressGlow", pressGlow).put("halo", halo).put("roundedBar", roundedBar)
        .put("iconScale", iconScale.toDouble()).put("frameColor", frameColor)
        .put("frameImage", frameImage ?: JSONObject.NULL).put("screenCorner", screenCorner)

    companion object {
        const val NAME_MAX = 32
        fun cleanName(v: String) = v.filter { !it.isISOControl() }.trim().take(NAME_MAX)

        fun fromJson(o: JSONObject): VmTheme {
            fun color(k: String): Int? = if (o.isNull(k) || !o.has(k)) null else o.optLong(k).toInt()
            return VmTheme(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = cleanName(o.optString("name")).ifBlank { "Theme" },
                glyphs = runCatching { GlyphSet.valueOf(o.optString("glyphs")) }.getOrDefault(GlyphSet.SUNSET),
                barColor = color("barColor"), iconColor = color("iconColor"),
                pressColor = o.optLong("pressColor", 0x2EFFFFFF).toInt(),
                pressGlow = o.optBoolean("pressGlow"), halo = o.optBoolean("halo"), roundedBar = o.optBoolean("roundedBar"),
                iconScale = o.optDouble("iconScale", 1.0).toFloat().coerceIn(0.7f, 1.5f),
                frameColor = o.optLong("frameColor", 0xFF000000).toInt(),
                frameImage = if (o.isNull("frameImage")) null else o.optString("frameImage").takeIf { safeFileName(it) },
                screenCorner = o.optInt("screenCorner").coerceIn(0, 40),
            )
        }

        /** only names this app wrote ("<id>.img"): never a path */
        fun safeFileName(n: String) = n.isNotEmpty() && n.length <= 64 && n.all { it.isLetterOrDigit() || it == '-' || it == '.' } && !n.startsWith(".")
    }
}

object VmThemes {
    private const val FILE = "themes.json"
    const val EXTENSION = "aetheme"
    private const val FORMAT = "aemu-theme"

    /** Themes that come with the app, one per Android era plus the app's two own looks. */
    val BUILT_IN = listOf(
        VmTheme("original", "Original", GlyphSet.MATERIAL, barColor = null, iconColor = null, pressColor = 0x2EFFFFFF, roundedBar = true, builtIn = true),
        VmTheme("sunset", "Sunset", GlyphSet.SUNSET, builtIn = true),
        VmTheme("gingerbread", "Gingerbread 2.x", GlyphSet.GINGERBREAD, barColor = 0xFF262626.toInt(), iconColor = 0xFFE6E6E6.toInt(),
            pressColor = 0xCCFF9900.toInt(), frameColor = 0xFF101010.toInt(), builtIn = true),
        VmTheme("honeycomb", "Honeycomb 3.x", GlyphSet.HONEYCOMB, barColor = 0xFF000000.toInt(), iconColor = 0xFFBFE9FA.toInt(),
            pressColor = 0xCC33B5E5.toInt(), pressGlow = true, halo = true, frameColor = 0xFF03141C.toInt(), builtIn = true),
        VmTheme("holo", "Holo 4.x", GlyphSet.HOLO, barColor = 0xFF000000.toInt(), iconColor = 0xFFFFFFFF.toInt(),
            pressColor = 0xA633B5E5.toInt(), pressGlow = true, halo = true, builtIn = true),
        VmTheme("lollipop", "Lollipop 5.x", GlyphSet.LOLLIPOP, barColor = 0xFF000000.toInt(), iconColor = 0xFFFFFFFF.toInt(),
            pressColor = 0x40FFFFFF, pressGlow = true, builtIn = true),
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "themes").apply { mkdirs() }
    private fun store(ctx: Context) = AtomicFile(File(dir(ctx), FILE))

    fun custom(ctx: Context): List<VmTheme> = runCatching {
        val a = JSONArray(store(ctx).openRead().bufferedReader().use { it.readText() })
        List(a.length()) { VmTheme.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun all(ctx: Context) = BUILT_IN + custom(ctx)

    /**
     * The choice lives in its own file: the VM runs in another process (read on resume, like host-ui.json), and the
     * settings screen rewrites host-ui.json from its own copy, which would otherwise undo a theme picked meanwhile.
     */
    private fun choice(ctx: Context) = AtomicFile(File(dir(ctx), "selected"))

    /** The selected theme; before any choice, the old navigation bar setting decides (Original / Sunset / Holo). */
    fun selected(ctx: Context): VmTheme {
        val id = runCatching { String(choice(ctx).readFully()).trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
            val ui = app.aemu.AppPrefs.hostUiOptions(ctx)
            if (ui.holoNavbar) "holo" else if (ui.sunsetNavbar) "sunset" else "original"
        }
        return all(ctx).firstOrNull { it.id == id } ?: BUILT_IN[1]
    }

    fun select(ctx: Context, id: String) {
        val f = choice(ctx)
        val out = f.startWrite()
        try { out.write(id.toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

    private fun write(ctx: Context, list: List<VmTheme>) {
        val f = store(ctx)
        val out = f.startWrite()
        try {
            out.write(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString().toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) { f.failWrite(out); throw e }
    }

    /** Adds or replaces a custom theme (built-ins are never overwritten: edit a copy). */
    fun save(ctx: Context, t: VmTheme): VmTheme {
        val theme = if (t.builtIn || BUILT_IN.any { it.id == t.id }) t.copy(id = UUID.randomUUID().toString(), builtIn = false) else t
        val list = custom(ctx).filter { it.id != theme.id } + theme.copy(name = VmTheme.cleanName(theme.name).ifBlank { "Theme" })
        write(ctx, list)
        // pictures no theme uses any more
        val used = list.mapNotNull { it.frameImage }.toSet()
        dir(ctx).listFiles()?.filter { it.name.endsWith(".img") && it.name !in used }?.forEach { it.delete() }
        return theme
    }

    fun delete(ctx: Context, id: String) {
        val list = custom(ctx)
        val gone = list.firstOrNull { it.id == id } ?: return
        write(ctx, list.filter { it.id != id })
        gone.frameImage?.let { File(dir(ctx), it).delete() }
        if (runCatching { String(choice(ctx).readFully()).trim() }.getOrNull() == id) select(ctx, "sunset")
    }

    /** Copies a picked picture into the themes folder; returns its file name. */
    fun importImage(ctx: Context, bytes: ByteArray): String {
        require(bytes.size in 1..(8 shl 20)) { "picture too large" }
        val name = UUID.randomUUID().toString() + ".img"
        File(dir(ctx), name).writeBytes(bytes)
        return name
    }

    fun imageFile(ctx: Context, t: VmTheme): File? = t.frameImage?.let { File(dir(ctx), it) }?.takeIf { it.isFile }

    /** A shareable .aetheme file: the theme as JSON, its frame picture embedded. */
    fun export(ctx: Context, t: VmTheme): ByteArray {
        val o = JSONObject().put("format", FORMAT).put("version", 1).put("theme", t.copy(builtIn = false).toJson())
        imageFile(ctx, t)?.let { o.put("image", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
        return o.toString(2).toByteArray()
    }

    /** Reads a shared theme; it becomes a new custom theme (new id, so it never replaces one already here). */
    fun import(ctx: Context, bytes: ByteArray): VmTheme {
        require(bytes.size <= (12 shl 20)) { "file too large" }
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        require(o.optString("format") == FORMAT) { "not an AEmulator theme" }
        var t = VmTheme.fromJson(o.getJSONObject("theme")).copy(id = UUID.randomUUID().toString(), builtIn = false, frameImage = null)
        if (o.has("image")) t = t.copy(frameImage = importImage(ctx, Base64.decode(o.getString("image"), Base64.DEFAULT)))
        return save(ctx, t)
    }
}
