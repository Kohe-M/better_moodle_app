package dev.rits.bettermoodle.ui

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.data.UrlPolicy

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Success<T>(
        val data: T,
        val refreshing: Boolean = false,
        /** 最終成功日時 (epoch millis)。null は不明 */
        val loadedAtMillis: Long? = null,
        /** キャッシュ表示中に再読込が失敗した理由。null なら最新 */
        val staleError: String? = null,
    ) : UiState<T>
}

val UiState<*>.isRefreshing: Boolean
    get() = (this as? UiState.Success<*>)?.refreshing == true

val UiState<*>.isStale: Boolean
    get() = (this as? UiState.Success<*>)?.staleError != null

// ---------------------------------------------------------------------------
// 共通の見た目部品
// ---------------------------------------------------------------------------

/** 一覧画面の左右余白。カード同士の縦間隔は [ListItemSpacing]。 */
val ListContentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)
val ListItemSpacing = 8.dp

/**
 * アプリ共通のカード。背景より明確に持ち上げた色 (surfaceContainerLow) に
 * 細い枠線を添えて、ライト/ダークどちらでも「ひとつの塊」として見えるようにする。
 * 強調したいカードは [containerColor] に primaryContainer 等を渡す。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = MaterialTheme.shapes.medium,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
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

/**
 * カード先頭に置くアイコン枠。色付きの丸角ボックスに収めて、
 * 一覧を目で走査するときの手がかりにする。
 */
@Composable
fun LeadingIconBox(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * 画面内のセクション見出し。左に短いアクセントバーを付けて
 * 「ここから新しいまとまり」と一目で分かるようにする。
 */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 詳細画面内の小見出し (「提出したファイル」など)。本文より一段強く、画面見出しより弱い。 */
@Composable
fun SubsectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp),
    )
}

/** 補足的なメタ情報 (日時・種別など) 用の小さく薄い文字。 */
@Composable
fun MetaText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 状態を示す小さなピル (「未読」「あと3時間」など)。
 * 文字だけでなく背景色でも区別できるようにする。
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = contentColor,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 詳細画面のトップバー配色。トップバーは背景と同系の落ち着いた色にして、コンテンツ側の強調を邪魔しない。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** [appTopBarColors] の CenterAlignedTopAppBar 版。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appCenterTopBarColors(): TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * キャッシュ表示中の更新失敗を知らせるバナー。最新なら何も描画しない。
 * 一覧画面の先頭に置く。
 */
@Composable
fun StaleDataBanner(state: UiState<*>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val success = state as? UiState.Success<*> ?: return
    val error = success.staleError ?: return
    val loadedAt = success.loadedAtMillis?.let { formatMoodleDateTime(it / 1000) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("更新に失敗しました: $error", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (loadedAt != null) "$loadedAt 取得時点のデータを表示しています" else "以前取得したデータを表示しています",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRetry) { Text("再試行") }
        }
    }
}

@Composable
fun LoadingBox() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * エラー・空状態の表示。[icon] を渡すと空状態らしい見た目になる
 * (エラーメッセージのみの場合はアイコン無し)。
 */
@Composable
fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    icon: ImageVector? = null,
    retryLabel: String = "再読み込み",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(48.dp).padding(bottom = 12.dp),
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            // エラーは復帰操作を目立たせ、空状態は控えめに
            if (icon == null) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text(retryLabel) }
            } else {
                OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text(retryLabel) }
            }
        }
    }
}

/**
 * 画面内のお知らせ/エラーバナー。アイコンで種別が一目で分かるようにする。
 */
@Composable
fun MessageBanner(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.padding(start = 12.dp).size(20.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
            if (onDismiss != null) {
                androidx.compose.material3.IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "閉じる")
                }
            }
        }
    }
}

/** 一覧が空のときの表示。エラーと区別するためアイコン付きにする。 */
@Composable
fun EmptyBox(message: String, onRetry: (() -> Unit)? = null, icon: ImageVector = Icons.Outlined.Inbox) {
    ErrorBox(message = message, onRetry = onRetry, icon = icon, retryLabel = "更新")
}

/**
 * 「ラベル: 値」の1行。ラベルは薄く、値は太くして読む側の目を値に導く。
 * [emphasis] を付けると値を primary 色で強調する (締切など)。
 */
@Composable
fun SummaryLine(label: String, value: String, emphasis: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Chrome Custom Tab でURLを開く。ブラウザが無い等で起動できなければ false を返し、利用者に知らせる。 */
fun openInCustomTab(context: Context, url: String): Boolean {
    if (!UrlPolicy.canOpenExternally(url)) return false
    val launched = runCatching {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    }.isSuccess
    if (!launched) {
        android.widget.Toast.makeText(context, "ブラウザを起動できませんでした", android.widget.Toast.LENGTH_SHORT).show()
    }
    return launched
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
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error != null) {
            Text(
                "続きを読み込めませんでした: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
        } else {
            OutlinedButton(onClick = onLoadMore, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (error != null) "再試行" else "さらに読み込む")
            }
        }
    }
}
