package dev.rits.bettermoodle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing

/**
 * 同期状態の表示 (DESIGN.md §8.5)。最新なら何も描画しない。
 * - オフライン: 1行のバナー (surface-sunken / ink-secondary)「オフライン — 9:41 時点の情報」。赤くしない
 * - 更新失敗: 一覧の先頭にインラインで「更新できませんでした [再試行]」。トーストだけで済ませない
 */
@Composable
fun StaleDataBanner(state: UiState<*>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val success = state as? UiState.Success<*> ?: return
    val error = success.staleError ?: return
    val online = rememberIsOnline()
    val at = success.loadedAtMillis?.let { formatClock(it) }
    val c = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(c.surfaceSunken)
            .padding(start = ScreenGutter, end = Spacing.xs),
    ) {
        Icon(
            if (online) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = c.inkSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
            if (online) {
                Text("更新できませんでした", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                MetaText(
                    listOfNotNull(error, at?.let { "$it 時点の情報を表示中" }).joinToString(" · "),
                    maxLines = 2,
                )
            } else {
                Text(
                    if (at != null) "オフライン — $at 時点の情報" else "オフライン — 前回取得した情報",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.inkSecondary,
                )
            }
        }
        TertiaryButton("再試行", onClick = onRetry)
    }
}

/** 一覧の末尾に置く「最終同期 9:41」(DESIGN.md §8.5) */
@Composable
fun SyncFooter(state: UiState<*>, modifier: Modifier = Modifier) {
    val at = (state as? UiState.Success<*>)?.loadedAtMillis ?: return
    MetaText(
        "最終同期 ${formatClock(at)}",
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = Spacing.xl),
    )
}

/** 読み込み中。300ms 未満なら何も出さず、それ以上はスケルトン (全画面スピナーは使わない) */
@Composable
fun LoadingBox() {
    SkeletonList()
}

/** 読み込み失敗。何が起きたか ([message]) と、次にできること (1つ) を示す */
@Composable
fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    title: String = "表示できませんでした",
    retryLabel: String = "再試行",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = AppTheme.colors.inkTertiary)
        Spacer(Modifier.height(Spacing.md))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.xl))
            SecondaryButton(retryLabel, onClick = onRetry)
        }
    }
}

/** 空状態 (DESIGN.md §8.6): 小さな線画 + 1文の説明 + 1つの操作 */
@Composable
fun EmptyBox(
    title: String,
    message: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "更新",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyIllustration()
        Spacer(Modifier.height(Spacing.xl))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.inkSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.lg))
            TertiaryButton(retryLabel, onClick = onRetry)
        }
    }
}

/**
 * 画面内のお知らせ/エラー。エラーは danger の面とアイコン、お知らせは accent の面と i アイコン。
 * 文面で何が起きたかを書く (色だけに頼らない)。
 */
@Composable
fun MessageBanner(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
    tone: Tone = if (isError) Tone.Danger else Tone.Accent,
) {
    val c = AppTheme.colors
    val (container, accent) = toneColors(tone)
    Surface(
        color = container,
        contentColor = c.ink,
        shape = RoundedCornerShape(Radius.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = if (isError) "エラー" else "お知らせ",
                tint = accent,
                modifier = Modifier.padding(start = Spacing.md, top = Spacing.md).size(20.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "閉じる", tint = c.inkSecondary)
                }
            }
        }
    }
}

/** 一覧の末尾に置く「さらに読み込む」行。取得上限による打ち切りを利用者に見せる。 */
@Composable
fun LoadMoreRow(
    loading: Boolean,
    error: String?,
    label: String,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error != null) {
            Text(
                "続きを読み込めませんでした ($error)",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.ink,
                textAlign = TextAlign.Center,
            )
        } else {
            MetaText(label)
        }
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = AppTheme.colors.inkTertiary,
                modifier = Modifier.padding(top = Spacing.sm).size(24.dp),
            )
        } else {
            TertiaryButton(if (error != null) "再試行" else "さらに読み込む", onClick = onLoadMore)
        }
    }
}
