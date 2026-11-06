package dev.rits.bettermoodle.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Sizes
import dev.rits.bettermoodle.ui.theme.Spacing
import dev.rits.bettermoodle.ui.theme.TABULAR_NUMS

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

/** トップバー: 背景は canvas。スクロール後は surface に切り替えて操作層を浮かせる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = AppTheme.colors.canvas,
    scrolledContainerColor = AppTheme.colors.surface,
    titleContentColor = AppTheme.colors.ink,
    navigationIconContentColor = AppTheme.colors.ink,
    actionIconContentColor = AppTheme.colors.inkSecondary,
)

/** [appTopBarColors] の CenterAlignedTopAppBar 版。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appCenterTopBarColors(): TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
    containerColor = AppTheme.colors.canvas,
    scrolledContainerColor = AppTheme.colors.surface,
    titleContentColor = AppTheme.colors.ink,
    navigationIconContentColor = AppTheme.colors.ink,
    actionIconContentColor = AppTheme.colors.inkSecondary,
)

/** 端末がネットワークにつながっているか。変化を購読する */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
    fun current(): Boolean = runCatching {
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
    var online by remember { mutableStateOf(current()) }
    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = current() }
        }
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess && cm != null
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return online
}

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

// ---------------------------------------------------------------------------
// まとまりを見せる部品。文が並んでいるだけに見えないよう、情報は面 (カード) と
// アイコンの付いた行で区切る。左端の色帯のような装飾線は使わない。
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// ボタン (DESIGN.md §8.7)。Primary は1画面1つまで。高さ 48・ピル
// ---------------------------------------------------------------------------

private val ButtonPadding = PaddingValues(horizontal = 20.dp)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.hitMin),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppTheme.colors.accent,
            contentColor = AppTheme.colors.onAccent,
        ),
    ) {
        leading?.let { it(); Spacer(Modifier.width(Spacing.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.hitMin),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = AppTheme.colors.accentSoft,
            contentColor = AppTheme.colors.accent,
        ),
    ) {
        leading?.let { it(); Spacer(Modifier.width(Spacing.sm)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.hitMin),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) AppTheme.colors.danger else AppTheme.colors.accent,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 確認シート (DESIGN.md §8.7: 破壊的操作は danger 文字の tertiary + 確認シート)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppTheme.colors.surface) {
        Column(Modifier.padding(horizontal = ScreenGutter).padding(bottom = Spacing.xl)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.ink)
            Spacer(Modifier.height(Spacing.sm))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.inkSecondary)
            Spacer(Modifier.height(Spacing.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("キャンセル", onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).heightIn(min = Sizes.hitMin),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (destructive) AppTheme.colors.danger else AppTheme.colors.accent,
                    ),
                ) { Text(confirmLabel, style = MaterialTheme.typography.labelLarge) }
            }
        }
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
