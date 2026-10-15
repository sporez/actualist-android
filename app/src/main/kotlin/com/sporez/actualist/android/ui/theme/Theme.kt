package com.sporez.actualist.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sporez.actualist.android.data.DisplaySize
import com.sporez.actualist.android.data.NamedTheme
import com.sporez.actualist.android.data.ThemeMode

/** Money tones shared by every screen: positive, zero and negative balances. */
@Immutable
data class MoneyColors(
    val positive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val negative: Color,
    val negativeContainer: Color,
    val onNegativeContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
)

private val Green = Color(0xFF8BC34A)
private val GreenDeep = Color(0xFF2E7D32)
private val Red = Color(0xFFE57373)
private val RedDeep = Color(0xFFC62828)

private val DarkMoney = MoneyColors(
    positive = Color(0xFF9CCC65),
    positiveContainer = Color(0xFF8BC34A),
    onPositiveContainer = Color(0xFF0E1A05),
    negative = Color(0xFFEF9A9A),
    negativeContainer = Color(0xFFE57373),
    onNegativeContainer = Color(0xFF2A0606),
    neutralContainer = Color(0xFF3A3842),
    onNeutralContainer = Color(0xFFE6E1EA),
)

private val LightMoney = MoneyColors(
    positive = GreenDeep,
    positiveContainer = Color(0xFFC5E1A5),
    onPositiveContainer = Color(0xFF1B3A05),
    negative = RedDeep,
    negativeContainer = Color(0xFFFFCDD2),
    onNegativeContainer = Color(0xFF4A0A0A),
    neutralContainer = Color(0xFFE6E1EA),
    onNeutralContainer = Color(0xFF2A2830),
)

val LocalMoneyColors = staticCompositionLocalOf { DarkMoney }

private val DarkScheme = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF0E1A05),
    primaryContainer = Color(0xFF2F4A17),
    onPrimaryContainer = Color(0xFFD7F0B8),
    secondary = Color(0xFFB39DDB),
    onSecondary = Color(0xFF1E1530),
    background = Color(0xFF0B0A10),
    onBackground = Color(0xFFECE8F2),
    surface = Color(0xFF0B0A10),
    onSurface = Color(0xFFECE8F2),
    surfaceVariant = Color(0xFF1A1820),
    onSurfaceVariant = Color(0xFFB9B4C2),
    surfaceContainer = Color(0xFF16141C),
    surfaceContainerHigh = Color(0xFF1E1C24),
    surfaceContainerHighest = Color(0xFF26242E),
    surfaceContainerLow = Color(0xFF121017),
    outline = Color(0xFF4A4654),
    outlineVariant = Color(0xFF2E2B36),
    error = Red,
)

private val LightScheme = lightColorScheme(
    primary = GreenDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC5E1A5),
    onPrimaryContainer = Color(0xFF0E2A05),
    secondary = Color(0xFF6A4FA8),
    error = RedDeep,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Row and padding density for the current display size (1 is Comfortable); shared components multiply their spacing by it. */
val LocalSpacingScale = staticCompositionLocalOf { 1f }

private fun DisplaySize.spacingScale(): Float = when (this) {
    DisplaySize.DENSE -> 0.75f
    DisplaySize.COMPACT -> 0.88f
    DisplaySize.COMFORTABLE -> 1f
    DisplaySize.LARGE -> 1.15f
}

/** [value] scaled by the display size's density. */
@Composable
fun spacing(value: Dp): Dp = value * LocalSpacingScale.current

/** Tabular-figure style for amounts so columns of money line up. */
val MoneyTextStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

@Composable
fun ActualistTheme(
    mode: ThemeMode = ThemeMode.DARK,
    namedTheme: NamedTheme? = null,
    displaySize: DisplaySize = DisplaySize.COMFORTABLE,
    content: @Composable () -> Unit,
) {
    val dark = when {
        namedTheme != null -> namedTheme.dark
        mode == ThemeMode.SYSTEM -> isSystemInDarkTheme()
        mode == ThemeMode.DARK -> true
        else -> false
    }
    val scheme = when {
        namedTheme != null -> namedColorScheme(namedTheme)
        dark -> DarkScheme
        else -> LightScheme
    }
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * displaySize.fontScale)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMoneyColors provides if (dark) DarkMoney else LightMoney,
        LocalDensity provides scaled,
        LocalSpacingScale provides displaySize.spacingScale(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
