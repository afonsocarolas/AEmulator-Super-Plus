/* Modified for AEmulator Sunset, 2026-10-03: cross-process display options.
 * Modified for AEmulator Super Plus, 2026-10-11: 3D library preference; 2026-10-12: Holo navigation bar, VM themes, interface style, Android color theme, AIemulator server. GPL-3.0. */
package app.aemu

import android.content.Context
import android.content.res.Configuration
import app.aemu.core.VmSettings
import java.util.Locale

/** Настройки самого приложения (не отдельной системы): язык, тема, умолчания для новых систем. */
object AppPrefs {
    private const val FILE = "app"

    /** Языки интерфейса: код BCP-47 → самоназвание. Пустой код — как в системе. */
    val LANGUAGES = listOf(
        "" to "",
        "en" to "English",
        "ru" to "Русский",
        "uk" to "Українська",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "pt-BR" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "it" to "Italiano",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "fa" to "فارسی",
        "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia",
        "vi" to "Tiếng Việt",
        "zh-CN" to "简体中文",
        "ja" to "日本語",
        "ko" to "한국어",
    )

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun prefs(ctx: Context) = sp(ctx)

    /** [holoNavbar] (Super Plus): the Android 4.x Holo bar - curved back arrow, Holo-blue press glow; implies the flat black bar of [sunsetNavbar]. */
    data class HostUiOptions(val cutoutBarrier: Boolean = false, val sunsetNavbar: Boolean = true,
        val tabletNavbarRotation: Boolean = true, val holoNavbar: Boolean = false)
    // The VM runs in another process: read an atomic file on resume, not cached SharedPreferences.
    fun hostUiOptions(ctx: Context): HostUiOptions = runCatching {
        val file = android.util.AtomicFile(java.io.File(ctx.filesDir, "host-ui.json"))
        val json = org.json.JSONObject(file.openRead().bufferedReader().use { it.readText() })
        HostUiOptions(json.optBoolean("cutoutBarrier", false), json.optBoolean("sunsetNavbar", true),
            json.optBoolean("tabletNavbarRotation", true), json.optBoolean("holoNavbar", false))
    }.getOrDefault(HostUiOptions())

    fun setHostUiOptions(ctx: Context, options: HostUiOptions) {
        val file = android.util.AtomicFile(java.io.File(ctx.filesDir, "host-ui.json"))
        val stream = file.startWrite()
        try {
            stream.write(org.json.JSONObject().put("cutoutBarrier", options.cutoutBarrier)
                .put("sunsetNavbar", options.sunsetNavbar).put("tabletNavbarRotation", options.tabletNavbarRotation).put("holoNavbar", options.holoNavbar)
                .toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    fun language(ctx: Context): String = sp(ctx).getString("lang", "") ?: ""
    fun setLanguage(ctx: Context, tag: String) = sp(ctx).edit().putString("lang", tag).apply()

    fun theme(ctx: Context): Int = sp(ctx).getInt("theme", THEME_SYSTEM)
    fun setTheme(ctx: Context, v: Int) = sp(ctx).edit().putInt("theme", v).apply()

    /** Custom accent color (ARGB) chosen with the color picker; 0 = use the built-in Sunset palette. */
    fun accentColor(ctx: Context): Int = sp(ctx).getInt("accent_color", 0)
    fun setAccentColor(ctx: Context, argb: Int) = sp(ctx).edit().putInt("accent_color", argb).apply()

    /** Interface style (Super Plus): how the app itself looks, on top of the theme and accent. Applied live. */
    data class UiStyle(val pureBlack: Boolean = false, val corners: Int = CORNERS_ROUND, val font: Int = FONT_DEFAULT,
        val textScale: Float = 1f, val expressiveMotion: Boolean = true)

    const val CORNERS_SHARP = 0
    const val CORNERS_SOFT = 1
    const val CORNERS_ROUND = 2
    const val CORNERS_EXTRA = 3
    const val FONT_DEFAULT = 0
    const val FONT_SERIF = 1
    const val FONT_MONO = 2
    val TEXT_SCALE = 0.85f..1.3f

    fun uiStyle(ctx: Context): UiStyle {
        val p = sp(ctx)
        return UiStyle(
            pureBlack = p.getBoolean("ui_pure_black", false),
            corners = p.getInt("ui_corners", CORNERS_ROUND).coerceIn(CORNERS_SHARP, CORNERS_EXTRA),
            font = p.getInt("ui_font", FONT_DEFAULT).coerceIn(FONT_DEFAULT, FONT_MONO),
            textScale = p.getFloat("ui_text_scale", 1f).coerceIn(TEXT_SCALE),
            expressiveMotion = p.getBoolean("ui_expressive_motion", true),
        )
    }

    fun setUiStyle(ctx: Context, s: UiStyle) = sp(ctx).edit()
        .putBoolean("ui_pure_black", s.pureBlack).putInt("ui_corners", s.corners).putInt("ui_font", s.font)
        .putFloat("ui_text_scale", s.textScale.coerceIn(TEXT_SCALE)).putBoolean("ui_expressive_motion", s.expressiveMotion).apply()

    const val PALETTE_SUNSET = 0
    const val PALETTE_ANDROID = 1

    /** Built-in color theme used when no custom accent is set: Sunset (orange) or Android (brand green and navy). */
    fun palette(ctx: Context): Int = sp(ctx).getInt("palette", PALETTE_SUNSET).coerceIn(PALETTE_SUNSET, PALETTE_ANDROID)
    fun setPalette(ctx: Context, v: Int) = sp(ctx).edit().putInt("palette", v).apply()

    /** AIemulator: the owner's proxy server (holds the Anthropic API key) and the access code it asks for. */
    fun aiServerUrl(ctx: Context): String = sp(ctx).getString("ai_server_url", null) ?: app.aemu.BuildConfig.AIEMU_SERVER_URL
    fun setAiServerUrl(ctx: Context, v: String) = sp(ctx).edit().putString("ai_server_url", v.trim()).apply()
    fun aiAccessCode(ctx: Context): String = sp(ctx).getString("ai_access_code", "") ?: ""
    fun setAiAccessCode(ctx: Context, v: String) = sp(ctx).edit().putString("ai_access_code", v.trim()).apply()

    fun dynamicColor(ctx: Context): Boolean = sp(ctx).getBoolean("dynamic", true)
    fun setDynamicColor(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("dynamic", v).apply()

    fun autoCheckUpdates(ctx: Context): Boolean = sp(ctx).getBoolean("auto_check_updates", true)
    fun setAutoCheckUpdates(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_check_updates", v).apply()

    /** Library shown as the 3D phone carousel (Super Plus) instead of the card list. */
    fun library3d(ctx: Context): Boolean = sp(ctx).getBoolean("library_3d", true)
    fun setLibrary3d(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("library_3d", v).apply()

    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(ctx: Context, v: Long) = sp(ctx).edit().putLong("last_update_check", v).apply()

    fun catalogUrl(ctx: Context): String = sp(ctx).getString("catalog_url", app.aemu.catalog.CatalogUrls.DEFAULT)
        ?: app.aemu.catalog.CatalogUrls.DEFAULT
    fun setCatalogUrl(ctx: Context, url: String) = sp(ctx).edit().putString("catalog_url", url).apply()
    /** Умолчания, которые получает только что импортированная система. */
    fun defaults(ctx: Context): VmSettings {
        val p = sp(ctx)
        val d = VmSettings()
        return d.copy(
            gpu = p.getBoolean("def_gpu", d.gpu),
            jit = p.getBoolean("def_jit", d.jit),
            netProxy = p.getBoolean("def_proxy", d.netProxy),
            showNavBar = p.getBoolean("def_nav", d.showNavBar),
            navButtons = p.getString("def_nav_buttons", d.navButtons) ?: d.navButtons,
            trackball = p.getBoolean("def_trackball", false),
            trackballDpad = p.getBoolean("def_trackball_dpad", false),
            trackballStepDp = p.getInt("def_trackball_step", 18).coerceIn(4, 48),
            keepScreenOn = p.getBoolean("def_awake", d.keepScreenOn),
        )
    }

    fun setDefaults(ctx: Context, s: VmSettings) = sp(ctx).edit()
        .putBoolean("def_gpu", s.gpu).putBoolean("def_jit", s.jit).putBoolean("def_proxy", s.netProxy)
        .putBoolean("def_nav", s.showNavBar).putBoolean("def_awake", s.keepScreenOn)
        .putString("def_nav_buttons", s.navButtons).putBoolean("def_trackball", s.trackball)
        .putBoolean("def_trackball_dpad", s.trackballDpad).putInt("def_trackball_step", s.trackballStepDp).apply()

    /** Контекст с выбранным языком — для attachBaseContext каждой активности. */
    fun wrap(base: Context): Context {
        val tag = language(base)
        if (tag.isEmpty()) return base
        val loc = Locale.forLanguageTag(tag)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        return base.createConfigurationContext(cfg)
    }
}
