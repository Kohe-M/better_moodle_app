package dev.rits.bettermoodle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Blue = Color(0xFF0B5CAD)
private val BlueDark = Color(0xFF78B7FF)
private val Teal = Color(0xFF006B62)
private val Amber = Color(0xFF8A5A00)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E9FF),
    onPrimaryContainer = Color(0xFF001C37),
    secondary = Teal,
    secondaryContainer = Color(0xFFCFEDEA),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Amber,
    tertiaryContainer = Color(0xFFFFE2A8),
    onTertiaryContainer = Color(0xFF2B1B00),
    background = Color(0xFFF7FAFE),
    onBackground = Color(0xFF101418),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE5EBF2),
    onSurfaceVariant = Color(0xFF303A45),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF65717E),
    outlineVariant = Color(0xFFC5CED8),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF002F5D),
    primaryContainer = Color(0xFF004881),
    onPrimaryContainer = Color(0xFFD7E9FF),
    secondary = Color(0xFF80D5CE),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFCFEDEA),
    tertiary = Color(0xFFFFC857),
    tertiaryContainer = Color(0xFF664200),
    onTertiaryContainer = Color(0xFFFFE2A8),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF141A20),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF2B333D),
    onSurfaceVariant = Color(0xFFC5CED8),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F9BA8),
    outlineVariant = Color(0xFF3F4A55),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun BetterMoodleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
