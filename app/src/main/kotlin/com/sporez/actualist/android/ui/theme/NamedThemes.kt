package com.sporez.actualist.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.sporez.actualist.android.data.NamedTheme

/** The colours of one of Actualist's named themes (ActualistThemePalette). */
data class NamedPalette(
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val control: Color,
    val accent: Color,
    val primaryText: Color,
    val secondaryText: Color,
)

private fun palette(background: Long, surface: Long, elevated: Long, control: Long, accent: Long, primaryText: Long, secondaryText: Long) =
    NamedPalette(Color(background), Color(surface), Color(elevated), Color(control), Color(accent), Color(primaryText), Color(secondaryText))

fun namedPalette(theme: NamedTheme): NamedPalette = when (theme) {
    NamedTheme.ACTUAL_PURPLE -> palette(0xFF0A0710, 0xFF14101D, 0xFF1D1429, 0xFF25183A, 0xFF7E65A8, 0xFFF9F6FE, 0xFFC7B6DF)
    NamedTheme.ACTUAL_PURPLE_LIGHT -> palette(0xFFECE5F3, 0xFFFCFAFE, 0xFFDED3E9, 0xFFD1C1DF, 0xFF624183, 0xFF211629, 0xFF5B4B67)
    NamedTheme.COASTAL_SAGE_LIGHT -> palette(0xFFE4EFEB, 0xFFFBFEFD, 0xFFD2E3DD, 0xFFC1D9D1, 0xFF1F6B64, 0xFF102925, 0xFF45615B)
    NamedTheme.AMETHYST_HAZE -> palette(0xFF050313, 0xFF100D24, 0xFF191337, 0xFF241A43, 0xFF8B68E8, 0xFFF8F5FF, 0xFFBFB2E3)
    NamedTheme.EMBER_AMBER -> palette(0xFF0E0D10, 0xFF19161B, 0xFF241A20, 0xFF30232A, 0xFFE4A258, 0xFFF8F4F0, 0xFFD1B9AA)
    NamedTheme.BLUE_CURRENT -> palette(0xFF020B13, 0xFF071A26, 0xFF0B2231, 0xFF102D41, 0xFF4B84BE, 0xFFF2F8FF, 0xFF9DB9CF)
    NamedTheme.ROSE_QUARTZ -> palette(0xFF111113, 0xFF1C1A1F, 0xFF241E25, 0xFF302631, 0xFFC47D90, 0xFFF8F3F4, 0xFFD0B7BF)
    NamedTheme.WHY_NAB -> palette(0xFF05050D, 0xFF121226, 0xFF1A1A30, 0xFF2B2B3D, 0xFF7D85FF, 0xFFF5F5FC, 0xFFB8BAC7)
}

/** A Material scheme built from a named theme's palette. */
fun namedColorScheme(theme: NamedTheme): ColorScheme {
    val p = namedPalette(theme)
    val onAccent = if (p.accent.luminance() > 0.4f) Color(0xFF101010) else Color.White
    return if (theme.dark) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = onAccent,
            primaryContainer = p.control,
            onPrimaryContainer = p.primaryText,
            secondary = p.accent,
            onSecondary = onAccent,
            secondaryContainer = p.control,
            onSecondaryContainer = p.primaryText,
            background = p.background,
            onBackground = p.primaryText,
            surface = p.background,
            onSurface = p.primaryText,
            surfaceVariant = p.surface,
            onSurfaceVariant = p.secondaryText,
            surfaceContainerLowest = p.background,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.elevated,
            surfaceContainerHighest = p.control,
            outline = p.secondaryText.copy(alpha = 0.45f),
            outlineVariant = p.control,
            error = Color(0xFFE57373),
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = onAccent,
            primaryContainer = p.control,
            onPrimaryContainer = p.primaryText,
            secondary = p.accent,
            onSecondary = onAccent,
            secondaryContainer = p.control,
            onSecondaryContainer = p.primaryText,
            background = p.background,
            onBackground = p.primaryText,
            surface = p.surface,
            onSurface = p.primaryText,
            surfaceVariant = p.elevated,
            onSurfaceVariant = p.secondaryText,
            surfaceContainerLowest = p.surface,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.elevated,
            surfaceContainerHigh = p.control,
            surfaceContainerHighest = p.control,
            outline = p.secondaryText.copy(alpha = 0.55f),
            outlineVariant = p.control,
            error = Color(0xFFC62828),
        )
    }
}
