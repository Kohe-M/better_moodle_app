package dev.rits.bettermoodle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF0B5CAD)
private val BlueDark = Color(0xFF8CC0FF)
private val Teal = Color(0xFF006B62)
private val Amber = Color(0xFF8A5A00)

/**
 * 配色の方針:
 * - 背景 (background) はわずかに沈めた色、カード (surfaceContainerLow 以上) は
 *   背景より明確に持ち上げる。M3 の既定 surfaceContainer* は背景とほぼ同色に
 *   なるため、段階 (Lowest < Low < Container < High < Highest) を明示的に定義する。
 * - 一覧のカードは surfaceContainerLow、その中の入れ子や強調は High 以上を使う。
 */
private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E9FF),
    onPrimaryContainer = Color(0xFF001C37),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFEDEA),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2A8),
    onTertiaryContainer = Color(0xFF2B1B00),
    background = Color(0xFFEFF3F8),
    onBackground = Color(0xFF101418),
    surface = Color(0xFFEFF3F8),
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFDFE6EE),
    onSurfaceVariant = Color(0xFF46525E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFE6ECF3),
    surfaceContainerHighest = Color(0xFFDCE3EB),
    surfaceDim = Color(0xFFD8DEE5),
    surfaceBright = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6C7884),
    outlineVariant = Color(0xFFCBD3DC),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF00305B),
    primaryContainer = Color(0xFF0F4A86),
    onPrimaryContainer = Color(0xFFD7E9FF),
    secondary = Color(0xFF80D5CE),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF0B5B54),
    onSecondaryContainer = Color(0xFFCFEDEA),
    tertiary = Color(0xFFFFC857),
    onTertiary = Color(0xFF3F2C00),
    tertiaryContainer = Color(0xFF664200),
    onTertiaryContainer = Color(0xFFFFE2A8),
    background = Color(0xFF0D1114),
    onBackground = Color(0xFFE4E9EF),
    surface = Color(0xFF0D1114),
    onSurface = Color(0xFFE4E9EF),
    surfaceVariant = Color(0xFF2E3742),
    onSurfaceVariant = Color(0xFFB4BECA),
    surfaceContainerLowest = Color(0xFF080B0E),
    surfaceContainerLow = Color(0xFF1E252D),
    surfaceContainer = Color(0xFF232B34),
    surfaceContainerHigh = Color(0xFF2E3742),
    surfaceContainerHighest = Color(0xFF3A4450),
    surfaceDim = Color(0xFF0D1114),
    surfaceBright = Color(0xFF3A4450),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1F1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A96A3),
    outlineVariant = Color(0xFF424D59),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * 文字階層。画面内で「何が見出しで何が補足か」を大きさ・太さで区別できるよう、
 * 見出し (title*) は太く、補足 (label* / bodySmall) は小さく・薄く使う前提。
 * - headlineSmall: 画面内の主題 (課題名など)
 * - titleLarge:    トップバーのタイトル・大見出し
 * - titleMedium:   カードの主タイトル・セクション見出し
 * - titleSmall:    小見出し・日付ヘッダ
 * - bodyLarge/Medium: 本文
 * - bodySmall/labelMedium/labelSmall: 補足情報・メタデータ
 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

@Composable
fun BetterMoodleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
