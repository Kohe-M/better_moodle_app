package dev.rits.bettermoodle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * DESIGN.md のトークンをそのまま Kotlin に写したもの。値を足したいときは DESIGN.md を先に直す。
 * - 色: 授業の識別 (course palette) / 状態 (danger・warning・success) / 唯一のアクセント の3役割だけ
 * - Dynamic Color は使わない (授業カラーのコントラスト保証が崩れるため。DESIGN.md §12)
 */

/** DESIGN.md colors (light / dark の片方) */
@Immutable
data class CampusColors(
    val canvas: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val hairline: Color,
    val ink: Color,
    val inkSecondary: Color,
    /** アイコン・区切り記号・プレースホルダー専用。文字に使わない (3.4:1) */
    val inkTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    /** 状態色の面 (バナー・状態カード・札)。文字は対応する状態色を載せる */
    val dangerSoft: Color,
    val warningSoft: Color,
    val successSoft: Color,
    val isDark: Boolean,
)

private val LightCampus = CampusColors(
    canvas = Color(0xFFF2F1EE),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEFEEEA),
    hairline = Color(0xFFE4E2DD),
    ink = Color(0xFF1B1B1F),
    inkSecondary = Color(0xFF5C5C66),
    inkTertiary = Color(0xFF8B8B94),
    accent = Color(0xFF2B59D9),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE8EEFF),
    danger = Color(0xFFC8372D),
    warning = Color(0xFFA15C00),
    success = Color(0xFF1D7A4F),
    dangerSoft = Color(0xFFFDF1EF),
    warningSoft = Color(0xFFFCF0DC),
    successSoft = Color(0xFFE2F3EA),
    isDark = false,
)

private val DarkCampus = CampusColors(
    canvas = Color(0xFF0E0E10),
    surface = Color(0xFF1C1C1F),
    surfaceSunken = Color(0xFF141416),
    hairline = Color(0xFF2A2A2F),
    ink = Color(0xFFEDEDF0),
    inkSecondary = Color(0xFFA3A3AD),
    inkTertiary = Color(0xFF74747E),
    accent = Color(0xFF8AA6FF),
    onAccent = Color(0xFF0E1633),
    accentSoft = Color(0xFF1E2747),
    danger = Color(0xFFFF8A7F),
    warning = Color(0xFFF2B24C),
    success = Color(0xFF5CCB94),
    dangerSoft = Color(0xFF3A1E1C),
    warningSoft = Color(0xFF33270F),
    successSoft = Color(0xFF15291F),
    isDark = true,
)

/** 授業カラー1色。bg (面) と fg (文字・アイコン) は必ずペアで使う。 */
@Immutable
data class CourseColor(val bg: Color, val fg: Color)

/** DESIGN.md course palette。並び順は保存値 (インデックス) なので変えない。 */
private val CoursePaletteLight = listOf(
    CourseColor(Color(0xFFE3ECFF), Color(0xFF1F47B8)), // blue
    CourseColor(Color(0xFFDDF3F0), Color(0xFF0B6B60)), // teal
    CourseColor(Color(0xFFE3F4E1), Color(0xFF2A6B22)), // green
    CourseColor(Color(0xFFF0F1D9), Color(0xFF5B5F12)), // olive
    CourseColor(Color(0xFFFCEFD6), Color(0xFF8A5200)), // amber
    CourseColor(Color(0xFFFDE6DA), Color(0xFFA13F12)), // orange
    CourseColor(Color(0xFFFCE3E3), Color(0xFFA8262B)), // red
    CourseColor(Color(0xFFFBE4F0), Color(0xFF9C2366)), // pink
    CourseColor(Color(0xFFEEE6FB), Color(0xFF5B34A8)), // purple
    CourseColor(Color(0xFFE7EAEE), Color(0xFF3D4A5C)), // slate
)

private val CoursePaletteDark = listOf(
    CourseColor(Color(0xFF1B2A52), Color(0xFFA9C1FF)),
    CourseColor(Color(0xFF12332F), Color(0xFF7FD6C9)),
    CourseColor(Color(0xFF1A331A), Color(0xFF94D38A)),
    CourseColor(Color(0xFF2D2F14), Color(0xFFD0D47A)),
    CourseColor(Color(0xFF3A2A10), Color(0xFFF2C06B)),
    CourseColor(Color(0xFF3D2116), Color(0xFFFFB08A)),
    CourseColor(Color(0xFF3E1C1E), Color(0xFFFF9EA0)),
    CourseColor(Color(0xFF3B1A2C), Color(0xFFFF9CCF)),
    CourseColor(Color(0xFF2A2040), Color(0xFFC8AFFF)),
    CourseColor(Color(0xFF252A31), Color(0xFFB7C2D1)),
)

/** 利用者向けの色名 (色の変更シートで読み上げる) */
val CoursePaletteNames = listOf("青", "青緑", "緑", "オリーブ", "琥珀", "橙", "赤", "桃", "紫", "スレート")

/** DESIGN.md spacing (4px グリッド)。間の値を作らない。 */
object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val gutterPhone: Dp = 16.dp
    val gutterTablet: Dp = 24.dp
}

/** DESIGN.md rounded */
object Radius {
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 14.dp
    val lg: Dp = 20.dp
}

/** DESIGN.md size */
object Sizes {
    val hitMin: Dp = 48.dp
    val gridRowMin: Dp = 64.dp
    val gridHeaderCol: Dp = 28.dp
}

/** DESIGN.md motion */
object Motion {
    const val FAST_MS = 120
    const val BASE_MS = 220
    const val SLOW_MS = 360
}

private val LocalCampusColors = staticCompositionLocalOf { LightCampus }

/** アプリ固有のテーマ値への入口 */
object AppTheme {
    val colors: CampusColors
        @Composable @ReadOnlyComposable get() = LocalCampusColors.current

    val coursePalette: List<CourseColor>
        @Composable @ReadOnlyComposable get() =
            if (LocalCampusColors.current.isDark) CoursePaletteDark else CoursePaletteLight
}

private fun materialColors(t: CampusColors) = if (t.isDark) {
    darkColorScheme(
        primary = t.accent, onPrimary = t.onAccent,
        primaryContainer = t.accentSoft, onPrimaryContainer = t.accent,
        secondary = t.inkSecondary, onSecondary = t.canvas,
        secondaryContainer = t.accentSoft, onSecondaryContainer = t.accent,
        tertiary = t.warning, onTertiary = t.canvas,
        tertiaryContainer = t.surfaceSunken, onTertiaryContainer = t.warning,
        background = t.canvas, onBackground = t.ink,
        surface = t.surface, onSurface = t.ink,
        surfaceVariant = t.surfaceSunken, onSurfaceVariant = t.inkSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = t.canvas, surfaceContainerLow = t.surface,
        surfaceContainer = t.surface, surfaceContainerHigh = t.surfaceSunken,
        surfaceContainerHighest = t.surfaceSunken, surfaceDim = t.canvas, surfaceBright = t.surface,
        inverseSurface = t.ink, inverseOnSurface = t.canvas, inversePrimary = LightCampus.accent,
        error = t.danger, onError = t.canvas,
        errorContainer = t.surfaceSunken, onErrorContainer = t.ink,
        outline = t.inkTertiary, outlineVariant = t.hairline,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = t.accent, onPrimary = t.onAccent,
        primaryContainer = t.accentSoft, onPrimaryContainer = t.accent,
        secondary = t.inkSecondary, onSecondary = t.surface,
        secondaryContainer = t.accentSoft, onSecondaryContainer = t.accent,
        tertiary = t.warning, onTertiary = t.surface,
        tertiaryContainer = t.surfaceSunken, onTertiaryContainer = t.warning,
        background = t.canvas, onBackground = t.ink,
        surface = t.surface, onSurface = t.ink,
        surfaceVariant = t.surfaceSunken, onSurfaceVariant = t.inkSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = t.surface, surfaceContainerLow = t.surface,
        surfaceContainer = t.surface, surfaceContainerHigh = t.surfaceSunken,
        surfaceContainerHighest = t.surfaceSunken, surfaceDim = t.surfaceSunken, surfaceBright = t.surface,
        inverseSurface = t.ink, inverseOnSurface = t.canvas, inversePrimary = DarkCampus.accent,
        error = t.danger, onError = t.surface,
        errorContainer = t.surfaceSunken, onErrorContainer = t.ink,
        outline = t.inkTertiary, outlineVariant = t.hairline,
        scrim = Color.Black,
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.lg),
)

/** 時刻・時限・残り時間は必ず等幅数字 (DESIGN.md typography.numeric) */
const val TABULAR_NUMS = "tnum"

/**
 * DESIGN.md typography の割り当て。太さは 400 / 600 / 700 だけ。
 * - displaySmall   = countdown (34/700, 等幅数字)
 * - headlineMedium = display (28/700) 画面の大タイトル
 * - headlineSmall / titleLarge = title-lg (22/700)
 * - titleMedium    = title-md (17/600)
 * - titleSmall     = body-sm の 600 (一覧の主テキストを少し強くしたいとき)
 * - bodyLarge      = body (16/400/1.6)
 * - bodyMedium     = body-sm (14/400/1.55)
 * - bodySmall      = caption (12/400/1.4)
 * - labelLarge     = label (13/600) ボタン・セクション見出し
 * - labelMedium    = caption の等幅数字版 (時刻・件数)
 * - labelSmall     = grid-cell (11/600) 時間割セル専用。これ未満は作らない
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 1.1.em,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR_NUMS,
    ),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 1.25.em, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 1.3.em, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 1.3.em, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 1.4.em, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 1.55.em, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 1.6.em, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 1.55.em, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 1.4.em, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 1.3.em, letterSpacing = 0.sp),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 1.4.em,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR_NUMS,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 1.25.em,
        letterSpacing = 0.sp, fontFeatureSettings = TABULAR_NUMS,
    ),
)

@Composable
fun BetterMoodleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val campus = if (darkTheme) DarkCampus else LightCampus
    CompositionLocalProvider(LocalCampusColors provides campus) {
        MaterialTheme(
            colorScheme = materialColors(campus),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
