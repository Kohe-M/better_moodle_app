package dev.rits.bettermoodle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing
import dev.rits.bettermoodle.ui.theme.TABULAR_NUMS

// ---------------------------------------------------------------------------
// 共通部品 (DESIGN.md §5・§6・§8)
// - 区切りはまず余白、次に面の明度差、最後に 0.5px の hairline。影は付けない
// - 余白は Spacing トークンだけ (4px グリッド)
// ---------------------------------------------------------------------------

/** 詳細画面 (課題・フォーラム等) の本文の余白 */
val ListContentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg)
val ListItemSpacing = Spacing.sm

/** 行で構成する一覧の余白。行自身が左右の余白を持つので、末尾だけ空ける。 */
val RowListPadding = PaddingValues(bottom = Spacing.xxl)

/** 画面の左右の基準余白 (phone) */
val ScreenGutter = Spacing.gutterPhone

/** DESIGN.md components.card: surface + 角丸14 + 0.5px hairline、影なし */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    containerColor: Color = AppTheme.colors.surface,
    contentColor: Color = AppTheme.colors.ink,
    shape: Shape = RoundedCornerShape(Radius.md),
    border: BorderStroke? = BorderStroke(0.5.dp, AppTheme.colors.hairline),
    content: @Composable () -> Unit,
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Surface(
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    }
}

/** 行頭の種類アイコン。ink-secondary の単色線アイコン */
@Composable
fun LeadingIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = AppTheme.colors.inkSecondary,
    size: Dp = 20.dp,
) {
    Icon(icon, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** 一覧の1行 (上下 12 / 左右 16、最小 56)。押したときの反応だけで「押せる」ことを伝える。 */
@Composable
fun ListRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    contentPadding: PaddingValues = PaddingValues(horizontal = ScreenGutter, vertical = Spacing.md),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/** 行と行の間の hairline。[inset] で行頭の印の分だけ字下げする。 */
@Composable
fun RowDivider(inset: Dp = ScreenGutter) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 0.5.dp,
        color = AppTheme.colors.hairline,
    )
}

// ---------------------------------------------------------------------------
// まとまりを見せる部品。文が並んでいるだけに見えないよう、情報は面 (カード) と
// アイコンの付いた行で区切る。左端の色帯のような装飾線は使わない。
// ---------------------------------------------------------------------------

/** 角丸の面にアイコンを載せた種類の印 (ファイル・活動・見出し)。装飾なので読み上げない */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    val (container, content) = toneColors(tone)
    Box(
        modifier
            .size(size)
            .background(container, RoundedCornerShape(Radius.sm))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

/**
 * 詳細画面の1つのまとまり (説明・詳細・提出物など)。surface のカードに、アイコン付きの見出しを載せる。
 */
@Composable
fun DetailCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    containerColor: Color = AppTheme.colors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier = modifier, containerColor = containerColor) {
        Column(Modifier.padding(Spacing.lg)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.md)) {
                    if (icon != null) {
                        IconTile(icon, tone = Tone.Accent, size = 28.dp, iconSize = 16.dp)
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppTheme.colors.ink,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** 詳細カード内の「アイコン ラベル … 値」の1行。値は等幅数字で右に寄せ、ラベルより強くする */
@Composable
fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppTheme.colors.ink,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(icon, contentDescription = null, tint = AppTheme.colors.inkSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.inkSecondary,
            modifier = Modifier.padding(end = Spacing.md),
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** カード内の行と行の間の hairline */
@Composable
fun CardDivider(inset: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 0.5.dp,
        color = AppTheme.colors.hairline,
    )
}

/** 詳細画面の先頭: 種類 (アイコン + 「課題」など) と大きな題名 */
@Composable
fun DetailTitle(
    title: String,
    typeLabel: String,
    typeIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(typeIcon, tone = Tone.Accent, size = 24.dp, iconSize = 14.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(typeLabel, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * 詳細画面の最上部の状態カード (課題の提出期限・小テストの受付終了)。
 * 面を状態の色調で塗り、日時を大きく、残り時間を状態色で出す。札は [pills] に置く。
 */
@Composable
fun StatusHeroCard(
    tone: Tone,
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    remaining: String? = null,
    pills: @Composable RowScope.() -> Unit = {},
) {
    val (container, accent) = toneColors(tone)
    AppCard(modifier = modifier, containerColor = container, border = null, shape = RoundedCornerShape(Radius.lg)) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(label, style = MaterialTheme.typography.labelLarge, color = accent)
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = AppTheme.colors.ink,
            )
            if (remaining != null) {
                Text(
                    remaining,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = accent,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.md),
                content = pills,
            )
        }
    }
}

/** [StatusHeroCard] の上に載せる札。色調の面の上でも読めるよう surface の地にする */
@Composable
fun HeroPill(text: String, color: Color = AppTheme.colors.ink, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(AppTheme.colors.surface, RoundedCornerShape(Radius.xs))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_NUMS),
            color = color,
            maxLines = 1,
        )
    }
}

/** 一覧の中での位置。まとまりを1枚のカードとして角を丸める */
enum class GroupPosition {
    Single, First, Middle, Last;

    val isFirst: Boolean get() = this == Single || this == First
    val isLast: Boolean get() = this == Single || this == Last

    companion object {
        fun of(index: Int, size: Int): GroupPosition = when {
            size <= 1 -> Single
            index == 0 -> First
            index == size - 1 -> Last
            else -> Middle
        }
    }
}

/**
 * LazyColumn の行を、まとまりごとに1枚のカード (surface・角丸) に見せる。
 * 2行目以降の上端には [dividerInset] から hairline を引く (null なら引かない)。
 */
@Composable
fun Modifier.groupedItem(position: GroupPosition, dividerInset: Dp? = ScreenGutter): Modifier {
    val c = AppTheme.colors
    val r = Radius.md
    val shape = when (position) {
        GroupPosition.Single -> RoundedCornerShape(r)
        GroupPosition.First -> RoundedCornerShape(topStart = r, topEnd = r)
        GroupPosition.Middle -> RoundedCornerShape(0.dp)
        GroupPosition.Last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
    }
    val hairline = c.hairline
    return this
        .padding(horizontal = ScreenGutter)
        .clip(shape)
        .background(c.surface)
        .drawWithContent {
            drawContent()
            if (!position.isFirst && dividerInset != null) {
                drawLine(
                    hairline,
                    Offset(dividerInset.toPx(), 0f),
                    Offset(size.width, 0f),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
        }
}
