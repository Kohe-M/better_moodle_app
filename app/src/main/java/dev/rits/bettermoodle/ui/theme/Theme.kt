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

// 立命館のえんじ色をベースにしたブランドテーマ
private val RitsRed = Color(0xFF8E2340)
private val RitsRedDark = Color(0xFF6E1730)
private val Accent = Color(0xFFF0B429)

private val LightColors = lightColorScheme(
    primary = RitsRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF3E0016),
    secondary = Color(0xFF775058),
    secondaryContainer = Color(0xFFFFD9DF),
    onSecondaryContainer = Color(0xFF2C1217),
    tertiary = Color(0xFF7A5900),
    tertiaryContainer = Color(0xFFFFE08B),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFDF8F8),
    surface = Color(0xFFFDF8F8),
    surfaceVariant = Color(0xFFF3DDE0),
    onSurfaceVariant = Color(0xFF524345),
    outlineVariant = Color(0xFFD6C2C5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C0),
    onPrimary = Color(0xFF5A0022),
    primaryContainer = Color(0xFF5C2334),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFE6BDC4),
    secondaryContainer = Color(0xFF4A2932),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFF3C04B),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFE08B),
    background = Color(0xFF161213),
    surface = Color(0xFF161213),
    surfaceVariant = Color(0xFF2A2226),
    onSurfaceVariant = Color(0xFFD6C2C5),
    outlineVariant = Color(0xFF3E3438),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
