package dev.rits.bettermoodle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing
import dev.rits.bettermoodle.ui.theme.TABULAR_NUMS

/** 一覧内のまとまりの見出し (「今日」「期限切れ」など)。label / ink-secondary、上の余白で区切る */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.inkSecondary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenGutter + Spacing.xs, end = ScreenGutter, top = Spacing.xl, bottom = Spacing.sm),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** 画面内のセクション見出し (title-md) */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.ink,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** 詳細画面内の小見出し (「提出したファイル」など) */
@Composable
fun SubsectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = AppTheme.colors.inkSecondary,
        modifier = modifier
            .padding(top = Spacing.md)
            .semantics { heading() },
    )
}

/** 補足のメタ情報 (日時・種別など)。caption / 等幅数字 / ink-secondary */
@Composable
fun MetaText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.inkSecondary,
    maxLines: Int = 1,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** DESIGN.md components.chip-status: caption / 角丸6 / 2px 8px。状態・件数にだけ使う */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = AppTheme.colors.surfaceSunken,
    contentColor: Color = AppTheme.colors.ink,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        maxLines = 1,
        modifier = modifier
            .background(containerColor, RoundedCornerShape(Radius.xs))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}

/** 状態の札 (色調つき)。文字は label の太さにして、一覧の中でも札だと分かるようにする */
@Composable
fun StatusPill(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val (container, content) = toneColors(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(container, RoundedCornerShape(Radius.xs))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_NUMS),
            color = content,
            maxLines = 1,
        )
    }
}

/** 状態の色調。札・バナー・状態カード・アイコンタイルで共通に使う */
enum class Tone { Neutral, Accent, Success, Warning, Danger }

/** [Tone] の (面, 文字・アイコン) の色 */
@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = AppTheme.colors
    return when (tone) {
        Tone.Neutral -> c.surfaceSunken to c.inkSecondary
        Tone.Accent -> c.accentSoft to c.accent
        Tone.Success -> c.successSoft to c.success
        Tone.Warning -> c.warningSoft to c.warning
        Tone.Danger -> c.dangerSoft to c.danger
    }
}
