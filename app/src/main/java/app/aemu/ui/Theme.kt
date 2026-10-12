package app.aemu.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Modified for AEmulator Sunset on 2026-09-30: sunset-orange Material 3 palette.
// Modified for AEmulator Super Plus on 2026-10-12: interface style (pure black, corners, font, text size, motion), Android color theme.
private val Light = lightColorScheme(
    primary = Color(0xFFF4511E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF), onPrimaryContainer = Color(0xFF3B0900),
    secondary = Color(0xFF77574D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF), onSecondaryContainer = Color(0xFF2C150F),
    tertiary = Color(0xFF6D5D2F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7E2A7), onTertiaryContainer = Color(0xFF231B00),
    background = Color(0xFFFFF8F5), surface = Color(0xFFFFF8F5),
    surfaceContainer = Color(0xFFF8ECE7), surfaceContainerHigh = Color(0xFFF2E6E1),
    surfaceContainerHighest = Color(0xFFECE0DB), surfaceContainerLow = Color(0xFFFFF1EC),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB59F), onPrimary = Color(0xFF5F1600),
    primaryContainer = Color(0xFF862200), onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0), onSecondary = Color(0xFF442A22),
    secondaryContainer = Color(0xFF5D4037), onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFDAC68D), onTertiary = Color(0xFF3B2F05),
    tertiaryContainer = Color(0xFF534619), onTertiaryContainer = Color(0xFFF7E2A7),
    background = Color(0xFF18120F), surface = Color(0xFF18120F),
    surfaceContainer = Color(0xFF251D1A), surfaceContainerHigh = Color(0xFF302825),
    surfaceContainerHighest = Color(0xFF3B322F), surfaceContainerLow = Color(0xFF211A17),
)

// Super Plus: Android color theme from the Android brand colors - green #3DDC84, navy #073042, light blue #D7EFFE.
val AndroidGreen = Color(0xFF3DDC84)

private val AndroidLight = lightColorScheme(
    primary = Color(0xFF006D3D), onPrimary = Color.White,
    primaryContainer = AndroidGreen, onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF073042), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7EFFE), onSecondaryContainer = Color(0xFF073042),
    tertiary = Color(0xFF1A73E8), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8E2FF), onTertiaryContainer = Color(0xFF001A41),
    background = Color(0xFFF6FBF4), surface = Color(0xFFF6FBF4),
    surfaceContainerLow = Color(0xFFF0F5EE), surfaceContainer = Color(0xFFEAF0E8),
    surfaceContainerHigh = Color(0xFFE4EAE3), surfaceContainerHighest = Color(0xFFDFE4DD),
    error = Color(0xFFBA1A1A),
)

private val AndroidDark = darkColorScheme(
    primary = AndroidGreen, onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF00522C), onPrimaryContainer = Color(0xFF8CF9B0),
    secondary = Color(0xFFAFCBDA), onSecondary = Color(0xFF073042),
    secondaryContainer = Color(0xFF1E4A5E), onSecondaryContainer = Color(0xFFD7EFFE),
    tertiary = Color(0xFFAEC6FF), onTertiary = Color(0xFF002E69),
    tertiaryContainer = Color(0xFF00458F), onTertiaryContainer = Color(0xFFD8E2FF),
    background = Color(0xFF06161E), surface = Color(0xFF06161E),
    surfaceContainerLow = Color(0xFF0B1F29), surfaceContainer = Color(0xFF0F2530),
    surfaceContainerHigh = Color(0xFF16303C), surfaceContainerHighest = Color(0xFF1E3A47),
)

/** Corner radii (extraSmall..extraLarge) for each interface corner style; Round is the original look. */
private fun appShapes(corners: Int): Shapes {
    val r = when (corners) {
        app.aemu.AppPrefs.CORNERS_SHARP -> listOf(2, 4, 6, 8, 10)
        app.aemu.AppPrefs.CORNERS_SOFT -> listOf(4, 8, 12, 16, 20)
        app.aemu.AppPrefs.CORNERS_EXTRA -> listOf(12, 18, 28, 36, 48)
        else -> listOf(8, 12, 20, 28, 36)
    }
    return Shapes(
        extraSmall = RoundedCornerShape(r[0].dp),
        small = RoundedCornerShape(r[1].dp),
        medium = RoundedCornerShape(r[2].dp),
        large = RoundedCornerShape(r[3].dp),
        extraLarge = RoundedCornerShape(r[4].dp),
    )
}

private val base = Typography()
private val AppType = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private fun TextStyle.styled(family: FontFamily?, scale: Float) = copy(
    fontFamily = family ?: fontFamily,
    fontSize = if (fontSize.isSp) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight,
)

/** [AppType] in the chosen interface font, every style scaled by the chosen text size. */
private fun appTypography(font: Int, scale: Float): Typography {
    val family = when (font) {
        app.aemu.AppPrefs.FONT_SERIF -> FontFamily.Serif
        app.aemu.AppPrefs.FONT_MONO -> FontFamily.Monospace
        else -> null
    }
    if (family == null && scale == 1f) return AppType
    val t = AppType
    return Typography(
        displayLarge = t.displayLarge.styled(family, scale), displayMedium = t.displayMedium.styled(family, scale),
        displaySmall = t.displaySmall.styled(family, scale), headlineLarge = t.headlineLarge.styled(family, scale),
        headlineMedium = t.headlineMedium.styled(family, scale), headlineSmall = t.headlineSmall.styled(family, scale),
        titleLarge = t.titleLarge.styled(family, scale), titleMedium = t.titleMedium.styled(family, scale),
        titleSmall = t.titleSmall.styled(family, scale), bodyLarge = t.bodyLarge.styled(family, scale),
        bodyMedium = t.bodyMedium.styled(family, scale), bodySmall = t.bodySmall.styled(family, scale),
        labelLarge = t.labelLarge.styled(family, scale), labelMedium = t.labelMedium.styled(family, scale),
        labelSmall = t.labelSmall.styled(family, scale),
    )
}

/** Pure black (AMOLED) dark scheme: black background, containers pulled towards black but still tinted. */
private fun androidx.compose.material3.ColorScheme.pureBlack() = copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.6f),
    surfaceContainer = lerp(surfaceContainer, Color.Black, 0.5f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.4f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.3f),
)

private fun hsl(h: Float, s: Float, l: Float) =
    Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

private fun onColor(c: Color) = if (c.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White

/** Builds a full Material 3 scheme from a single seed color picked by the user. */
fun schemeFromSeed(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.25f, 1f)
    val ns = (s * 0.3f).coerceAtMost(0.16f) // neutral tint for surfaces
    return if (!dark) {
        val primary = seed
        lightColorScheme(
            primary = primary, onPrimary = onColor(primary),
            primaryContainer = hsl(h, s, 0.90f), onPrimaryContainer = hsl(h, s, 0.12f),
            secondary = hsl(h, s * 0.28f, 0.38f), onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.45f, 0.90f), onSecondaryContainer = hsl(h, s * 0.4f, 0.12f),
            tertiary = hsl(h + 60f, s * 0.3f, 0.38f), onTertiary = Color.White,
            tertiaryContainer = hsl(h + 60f, s * 0.5f, 0.88f), onTertiaryContainer = hsl(h + 60f, s * 0.4f, 0.12f),
            background = hsl(h, ns, 0.98f), surface = hsl(h, ns, 0.98f),
            surfaceContainerLow = hsl(h, ns, 0.96f), surfaceContainer = hsl(h, ns, 0.94f),
            surfaceContainerHigh = hsl(h, ns, 0.92f), surfaceContainerHighest = hsl(h, ns, 0.90f),
            error = Color(0xFFBA1A1A),
        )
    } else {
        val primary = hsl(h, s, 0.78f)
        darkColorScheme(
            primary = primary, onPrimary = hsl(h, s, 0.16f),
            primaryContainer = hsl(h, s, 0.26f), onPrimaryContainer = hsl(h, s, 0.90f),
            secondary = hsl(h, s * 0.4f, 0.80f), onSecondary = hsl(h, s * 0.3f, 0.16f),
            secondaryContainer = hsl(h, s * 0.3f, 0.26f), onSecondaryContainer = hsl(h, s * 0.45f, 0.90f),
            tertiary = hsl(h + 60f, s * 0.4f, 0.78f), onTertiary = hsl(h + 60f, s * 0.3f, 0.16f),
            tertiaryContainer = hsl(h + 60f, s * 0.3f, 0.26f), onTertiaryContainer = hsl(h + 60f, s * 0.5f, 0.88f),
            background = hsl(h, ns, 0.08f), surface = hsl(h, ns, 0.08f),
            surfaceContainerLow = hsl(h, ns, 0.10f), surfaceContainer = hsl(h, ns, 0.13f),
            surfaceContainerHigh = hsl(h, ns, 0.17f), surfaceContainerHighest = hsl(h, ns, 0.21f),
        )
    }
}

@Composable
fun AemuTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // the main screen stays alive while the settings screen changes the theme: follow the stored value
    val rev = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(ctx) {
        val sp = app.aemu.AppPrefs.prefs(ctx)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> rev.intValue++ }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    rev.intValue
    val dark = forceDark ?: when (app.aemu.AppPrefs.theme(ctx)) {
        app.aemu.AppPrefs.THEME_LIGHT -> false
        app.aemu.AppPrefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val accent = app.aemu.AppPrefs.accentColor(ctx)
    val scheme = when {
        accent != 0 -> schemeFromSeed(Color(accent), dark)
        app.aemu.AppPrefs.palette(ctx) == app.aemu.AppPrefs.PALETTE_ANDROID -> if (dark) AndroidDark else AndroidLight
        Build.VERSION.SDK_INT >= 31 && app.aemu.AppPrefs.dynamicColor(ctx) -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    val style = app.aemu.AppPrefs.uiStyle(ctx)
    MaterialExpressiveTheme(
        colorScheme = if (dark && style.pureBlack) scheme.pureBlack() else scheme,
        motionScheme = if (style.expressiveMotion) MotionScheme.expressive() else MotionScheme.standard(),
        shapes = appShapes(style.corners),
        typography = appTypography(style.font, style.textScale),
        content = content,
    )
}

val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
