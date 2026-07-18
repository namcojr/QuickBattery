package com.quickbattery.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightRideQuestColorScheme = lightColorScheme(
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

private val DarkRideQuestColorScheme = darkColorScheme(
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

private val QuickBatteryShapes = Shapes(
    extraLarge = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(24.dp),
    medium = RoundedCornerShape(20.dp),
)

@Composable
fun QuickBatteryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkRideQuestColorScheme else LightRideQuestColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = QuickBatteryShapes,
        content = content,
    )
}
