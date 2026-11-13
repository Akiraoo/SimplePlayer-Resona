package com.akira.simpleplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp

/** Interface language (set from the core's settings). */
object Lang {
    @Volatile var english = false
}

/** `tr("中文", "English")` */
fun tr(zh: String, en: String) = if (Lang.english) en else zh

/** The desktop app's colours (ui/common.slint, Palette). */
@Immutable
data class Palette(
    val light: Boolean,
    val bg: Color,
    val rail: Color,
    val surface: Color,
    val raised: Color,
    val line: Color,
    val hover: Color,
    val press: Color,
    val text: Color,
    val strong: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentText: Color,
    val artA: Color,
    val artB: Color,
    val artIcon: Color,
    val track: Color,
) {
    val accentSoft get() = accent.copy(alpha = 0.11f)
    val accentLine get() = accent.copy(alpha = 0.38f)
}

/** Theme colours offered in Settings (the first is the desktop default). */
val ACCENTS = listOf(
    "#f2a65a", "#e8697a", "#c084fc", "#7c9cff", "#38bdf8", "#34d399", "#a3e635", "#facc15"
)

fun parseColor(hex: String, fallback: Color = Color(0xFFF2A65A)): Color = runCatching {
    val h = hex.trim().removePrefix("#")
    require(h.length == 6)
    Color(0xFF000000 or h.toLong(16))
}.getOrDefault(fallback)

fun palette(light: Boolean, accentHex: String): Palette {
    val base = parseColor(accentHex)
    // on a light background the accent is darkened until it reads well (as the desktop does)
    val accent = if (light) lerp(base, Color.Black, if (base.luminance() > 0.45f) 0.28f else 0.12f) else base
    val accentText = if (accent.luminance() > 0.45f) Color(0xFF1B1206) else Color.White
    return if (light) Palette(
        light = true,
        bg = Color(0xFFF1F1F2), rail = Color(0xFFE8E8EA), surface = Color(0xFFF9F9FA),
        raised = Color(0xFFFFFFFF), line = Color(0xFFDADADD), hover = Color(0xFFE2E2E5),
        press = Color(0xFFD6D6DA), text = Color(0xFF222325), strong = Color(0xFF111213),
        muted = Color(0xFF64666B), faint = Color(0xFF9A9CA1), accent = accent, accentText = accentText,
        artA = Color(0xFFFBFBFC), artB = Color(0xFFD3D4D8), artIcon = Color(0xFFA3A5AA),
        track = Color(0xFFD2D3D7)
    ) else Palette(
        light = false,
        bg = Color(0xFF0F1011), rail = Color(0xFF141516), surface = Color(0xFF17181A),
        raised = Color(0xFF1F2123), line = Color(0xFF2A2C2F), hover = Color(0xFF232528),
        press = Color(0xFF2C2F32), text = Color(0xFFE6E7E9), strong = Color(0xFFF7F7F8),
        muted = Color(0xFF8D9095), faint = Color(0xFF5D6065), accent = accent, accentText = accentText,
        artA = Color(0xFF2C2E31), artB = Color(0xFF111213), artIcon = Color(0xFF4E5156),
        track = Color(0xFF303236)
    )
}

val LocalPalette = staticCompositionLocalOf { palette(false, ACCENTS[0]) }

@Composable
fun SpTheme(light: Boolean, accentHex: String, content: @Composable () -> Unit) {
    val p = remember(light, accentHex) { palette(light, accentHex) }
    val scheme = remember(p) {
        if (p.light) lightColorScheme(
            primary = p.accent, onPrimary = p.accentText,
            secondary = p.accent, onSecondary = p.accentText,
            background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text,
            surfaceVariant = p.raised, onSurfaceVariant = p.muted,
            surfaceContainer = p.raised, surfaceContainerHigh = p.raised,
            surfaceContainerHighest = p.hover, surfaceContainerLow = p.surface,
            outline = p.faint, outlineVariant = p.line,
            secondaryContainer = p.accentSoft, onSecondaryContainer = p.strong,
            primaryContainer = p.accentSoft, onPrimaryContainer = p.strong,
        ) else darkColorScheme(
            primary = p.accent, onPrimary = p.accentText,
            secondary = p.accent, onSecondary = p.accentText,
            background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text,
            surfaceVariant = p.raised, onSurfaceVariant = p.muted,
            surfaceContainer = p.raised, surfaceContainerHigh = p.raised,
            surfaceContainerHighest = p.hover, surfaceContainerLow = p.surface,
            outline = p.faint, outlineVariant = p.line,
            secondaryContainer = p.accentSoft, onSecondaryContainer = p.strong,
            primaryContainer = p.accentSoft, onPrimaryContainer = p.strong,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
