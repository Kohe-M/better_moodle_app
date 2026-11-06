package dev.rits.bettermoodle.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.data.CourseColorStore
import dev.rits.bettermoodle.data.CourseColors
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.CourseColor
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing
import kotlinx.coroutines.delay

/*
 * 授業カラーと、どの画面でも使う小さな部品 (DESIGN.md §3・§7・§8.6)。
 * 授業カラーは授業に属する要素にだけ使い、UI クローム (タブバー・ボタン) には使わない。
 */

/** 授業カラーの割り当て状態。LoggedInRoot で CourseColorStore から供給する */
val LocalCourseColors = compositionLocalOf { CourseColorStore.State() }

@Composable
fun courseColor(key: String): CourseColor {
    val index = LocalCourseColors.current.indexFor(key)
    return AppTheme.coursePalette[index.coerceIn(0, CourseColors.PALETTE_SIZE - 1)]
}

@Composable
fun courseColor(courseCode: String?, courseId: Long?, title: String = ""): CourseColor =
    courseColor(CourseColors.key(courseCode, courseId, title))

/** 授業カラーの角丸スクエア (bg 色 + 頭文字 fg)。頭文字は大きさに合わせて大きくする */
@Composable
fun CourseSquare(color: CourseColor, name: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier
            .size(size)
            .background(color.bg, RoundedCornerShape(Radius.xs))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            courseInitial(name),
            style = when {
                size >= 48.dp -> MaterialTheme.typography.titleLarge
                size >= 32.dp -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.labelLarge
            },
            color = color.fg,
            textAlign = TextAlign.Center,
        )
    }
}

/** 授業名の頭文字。記号・括弧は飛ばす */
internal fun courseInitial(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.toString() ?: "・"

/** 締切行などの授業カラーの 8px ドット */
@Composable
fun CourseDot(color: CourseColor, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color.fg, CircleShape))
}

/**
 * 空状態の線画 (最大 96px、1種類だけ)。週のコマの枠と「いま」の線。装飾なので読み上げない。
 */
@Composable
fun EmptyIllustration(modifier: Modifier = Modifier) {
    val line = AppTheme.colors.inkTertiary
    val now = AppTheme.colors.accent
    Canvas(modifier.size(width = 96.dp, height = 72.dp).clearAndSetSemantics { }) {
        val cols = 4
        val rows = 3
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (cols - 1)) / cols
        val h = (size.height - gap * (rows - 1)) / rows
        val stroke = Stroke(width = 1.5.dp.toPx())
        val radius = CornerRadius(4.dp.toPx())
        for (c in 0 until cols) for (r in 0 until rows) {
            drawRoundRect(line, Offset(c * (w + gap), r * (h + gap)), Size(w, h), radius, style = stroke)
        }
        val y = h * 1.5f + gap
        drawLine(now, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
        drawCircle(now, radius = 3.dp.toPx(), center = Offset(0f, y))
    }
}

/** スケルトンの面 (形だけの灰色面。DESIGN.md §7) */
@Composable
fun SkeletonBlock(width: Dp?, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .background(AppTheme.colors.surfaceSunken, RoundedCornerShape(Radius.xs)),
    )
}

/**
 * 読み込み中の表示。300ms 未満で終わる読み込みでは何も出さず、それ以上はリストの形のスケルトンを出す。
 * 全画面スピナーは使わない。
 */
@Composable
fun SkeletonList(rows: Int = 6, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        visible = true
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
            .clearAndSetSemantics { },
    ) {
        if (!visible) return@Column
        SkeletonBlock(width = 120.dp, height = 14.dp)
        Spacer(Modifier.height(Spacing.lg))
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.md)) {
                SkeletonBlock(width = 24.dp, height = 24.dp)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    SkeletonBlock(width = null, height = 14.dp)
                    Spacer(Modifier.height(Spacing.sm))
                    SkeletonBlock(width = 96.dp, height = 12.dp)
                }
            }
        }
    }
}

/**
 * 完了チェックの円。押すと円が塗られる (spring-ui)。
 * [submitted] は Moodle 上の提出 (success)、それ以外の完了はアプリ内の完了扱い (ink-secondary)。
 */
@Composable
fun CheckCircle(checked: Boolean, submitted: Boolean = false, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val fill = if (submitted) colors.success else colors.inkSecondary
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(stiffness = 380f, dampingRatio = 32f / (2f * kotlin.math.sqrt(380f))),
        label = "check",
    )
    Box(modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(colors.inkTertiary, style = Stroke(width = 1.5.dp.toPx()))
            if (progress > 0f) drawCircle(fill, radius = size.minDimension / 2 * progress)
        }
        if (progress > 0.5f) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = colors.surface,
                modifier = Modifier.size(14.dp).scale(progress),
            )
        }
    }
}

/** 色の見本 (色の変更シートで使う) */
@Composable
fun ColorSwatch(color: CourseColor, selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .background(color.bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = color.fg, modifier = Modifier.size(20.dp))
        } else {
            Box(Modifier.size(12.dp).background(color.fg, CircleShape))
        }
    }
}

/** 未読などを示す 6px のドット (accent) */
@Composable
fun AccentDot(modifier: Modifier = Modifier, color: Color = AppTheme.colors.accent) {
    Box(modifier.size(6.dp).background(color, CircleShape))
}
