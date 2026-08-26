package com.quickbattery.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Selectable color palettes the user can toggle between. Persisted across launches. */
enum class ThemeVariant {
    Blue,
    Grey,
    Green,
    Yellow,
    Red;

    /** Returns the next variant in the cycle, wrapping back to the first. */
    fun next(): ThemeVariant {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

// --- Blue (original) ---------------------------------------------------------

private val BlueLightColorScheme = lightColorScheme(
    primary = Color(0xFF00566A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB3EAF3),
    onPrimaryContainer = Color(0xFF00212A),
    secondary = Color(0xFF10757C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F5F7),
    onSecondaryContainer = Color(0xFF002021),
    tertiary = Color(0xFF4057D6),
    onTertiary = Color.White,
    background = Color(0xFFF6FBFC),
    onBackground = Color(0xFF061A1F),
    surface = Color.White,
    onSurface = Color(0xFF132126),
    surfaceVariant = Color(0xFFE1EDF0),
    onSurfaceVariant = Color(0xFF435055),
    outline = Color(0xFF6A7A80),
)

private val BlueDarkColorScheme = darkColorScheme(
    primary = Color(0xFF5BD2DF),
    onPrimary = Color(0xFF003640),
    primaryContainer = Color(0xFF004E60),
    onPrimaryContainer = Color(0xFFB6F0FA),
    secondary = Color(0xFF52CCD1),
    onSecondary = Color(0xFF003234),
    secondaryContainer = Color(0xFF00494C),
    onSecondaryContainer = Color(0xFFB7F3F7),
    tertiary = Color(0xFFA4B3FF),
    onTertiary = Color(0xFF0A1C5D),
    background = Color(0xFF020C11),
    onBackground = Color(0xFFE0F1F4),
    surface = Color(0xFF041821),
    onSurface = Color(0xFFE0F1F4),
    surfaceVariant = Color(0xFF0F2D36),
    onSurfaceVariant = Color(0xFF9FC1C8),
    outline = Color(0xFF43616A),
)

// --- Grey --------------------------------------------------------------------

private val GreyLightColorScheme = lightColorScheme(
    primary = Color(0xFF4A5B62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3DEE3),
    onPrimaryContainer = Color(0xFF10191D),
    secondary = Color(0xFF5A656A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4E7),
    onSecondaryContainer = Color(0xFF161C1F),
    tertiary = Color(0xFF636C77),
    onTertiary = Color.White,
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF191C1E),
    surface = Color.White,
    onSurface = Color(0xFF20272A),
    surfaceVariant = Color(0xFFE4E8EA),
    onSurfaceVariant = Color(0xFF4A5155),
    outline = Color(0xFF74797C),
)

private val GreyDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB6C4CB),
    onPrimary = Color(0xFF213036),
    primaryContainer = Color(0xFF37464C),
    onPrimaryContainer = Color(0xFFD3E0E7),
    secondary = Color(0xFFBCC6CA),
    onSecondary = Color(0xFF263034),
    secondaryContainer = Color(0xFF3D474B),
    onSecondaryContainer = Color(0xFFD8E2E6),
    tertiary = Color(0xFFC3C8D4),
    onTertiary = Color(0xFF2C333D),
    background = Color(0xFF0E1113),
    onBackground = Color(0xFFE1E3E4),
    surface = Color(0xFF15191B),
    onSurface = Color(0xFFE1E3E4),
    surfaceVariant = Color(0xFF2A3033),
    onSurfaceVariant = Color(0xFFBFC7CB),
    outline = Color(0xFF5A6367),
)

// --- Green -------------------------------------------------------------------

private val GreenLightColorScheme = lightColorScheme(
    primary = Color(0xFF2E6B3E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F1BF),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF4F6353),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8D4),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Color(0xFF3A6470),
    onTertiary = Color.White,
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color.White,
    onSurface = Color(0xFF1A211B),
    surfaceVariant = Color(0xFFDDE6DB),
    onSurfaceVariant = Color(0xFF424941),
    outline = Color(0xFF727970),
)

private val GreenDarkColorScheme = darkColorScheme(
    primary = Color(0xFF97D8A4),
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF11512B),
    onPrimaryContainer = Color(0xFFB2F1BF),
    secondary = Color(0xFFB6CCB8),
    onSecondary = Color(0xFF223527),
    secondaryContainer = Color(0xFF384B3C),
    onSecondaryContainer = Color(0xFFD2E8D4),
    tertiary = Color(0xFFA2CEDB),
    onTertiary = Color(0xFF023640),
    background = Color(0xFF0C120D),
    onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF121A14),
    onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF2A322A),
    onSurfaceVariant = Color(0xFFC1CABF),
    outline = Color(0xFF5C6359),
)

// --- Yellow ------------------------------------------------------------------

private val YellowLightColorScheme = lightColorScheme(
    primary = Color(0xFF7A5B00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF97),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6C5D3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E0BB),
    onSecondaryContainer = Color(0xFF241A04),
    tertiary = Color(0xFF4C6545),
    onTertiary = Color.White,
    background = Color(0xFFFFFBF2),
    onBackground = Color(0xFF1E1B13),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF211C11),
    surfaceVariant = Color(0xFFEDE2CE),
    onSurfaceVariant = Color(0xFF4D4639),
    outline = Color(0xFF7F7667),
)

private val YellowDarkColorScheme = darkColorScheme(
    primary = Color(0xFFF3C044),
    onPrimary = Color(0xFF412D00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFDF97),
    secondary = Color(0xFFD9C4A0),
    onSecondary = Color(0xFF3A2F15),
    secondaryContainer = Color(0xFF52452A),
    onSecondaryContainer = Color(0xFFF6E0BB),
    tertiary = Color(0xFFB2CFA5),
    onTertiary = Color(0xFF1E3719),
    background = Color(0xFF14120B),
    onBackground = Color(0xFFE9E2D4),
    surface = Color(0xFF1C1910),
    onSurface = Color(0xFFE9E2D4),
    surfaceVariant = Color(0xFF352F1F),
    onSurfaceVariant = Color(0xFFD0C6B3),
    outline = Color(0xFF999080),
)

// --- Red ---------------------------------------------------------------------

private val RedLightColorScheme = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF775652),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF745B00),
    onTertiary = Color.White,
    background = Color(0xFFFFFBF9),
    onBackground = Color(0xFF201A19),
    surface = Color(0xFFFFFDFC),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857370),
)

private val RedDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB4A9),
    onPrimary = Color(0xFF680003),
    primaryContainer = Color(0xFF930006),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFE6C34C),
    onTertiary = Color(0xFF3D2F00),
    background = Color(0xFF161210),
    onBackground = Color(0xFFEDE0DD),
    surface = Color(0xFF1E1817),
    onSurface = Color(0xFFEDE0DD),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    outline = Color(0xFFA08C89),
)

private fun lightSchemeFor(variant: ThemeVariant): ColorScheme = when (variant) {
    ThemeVariant.Blue -> BlueLightColorScheme
    ThemeVariant.Grey -> GreyLightColorScheme
    ThemeVariant.Green -> GreenLightColorScheme
    ThemeVariant.Yellow -> YellowLightColorScheme
    ThemeVariant.Red -> RedLightColorScheme
}

private fun darkSchemeFor(variant: ThemeVariant): ColorScheme = when (variant) {
    ThemeVariant.Blue -> BlueDarkColorScheme
    ThemeVariant.Grey -> GreyDarkColorScheme
    ThemeVariant.Green -> GreenDarkColorScheme
    ThemeVariant.Yellow -> YellowDarkColorScheme
    ThemeVariant.Red -> RedDarkColorScheme
}

private val QuickBatteryShapes = Shapes(
    extraLarge = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(24.dp),
    medium = RoundedCornerShape(20.dp),
)

@Composable
fun QuickBatteryTheme(
    variant: ThemeVariant = ThemeVariant.Blue,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) darkSchemeFor(variant) else lightSchemeFor(variant)

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = QuickBatteryShapes,
        content = content,
    )
}
