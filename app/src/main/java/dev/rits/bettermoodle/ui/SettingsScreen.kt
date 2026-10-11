package dev.rits.bettermoodle.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Spacing
import kotlinx.coroutines.launch

/** タブから push で開く画面の共通の枠 (戻る + 1行タイトル) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = actions,
                colors = appTopBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), content = content)
    }
}

/**
 * 設定 (DESIGN.md §2: タブにせずコースタブから到達)。
 * 破壊的操作は danger 文字の tertiary + 確認シート (§8.7)。
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val (site, _) = rememberLoadable("siteinfo") { container.moodleRepository.siteInfo() }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClearWeb by remember { mutableStateOf(false) }

    SubScreenScaffold(title = "設定", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("アカウント")
            val name = (site as? UiState.Success)?.data?.fullname?.takeIf { it.isNotBlank() }
            ListRow(onClick = null, verticalAlignment = Alignment.CenterVertically) {
                LeadingIcon(Icons.Outlined.Person, contentDescription = null)
                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                    Text(name ?: "Moodle+R", style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.ink)
                    MetaText("lms.ritsumei.ac.jp にログイン中")
                }
            }
            RowDivider()
            ListRow(onClick = { confirmClearWeb = true }, verticalAlignment = Alignment.CenterVertically) {
                LeadingIcon(Icons.Outlined.Cookie, contentDescription = null)
                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                    Text("Webのログイン状態を削除", style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.ink)
                    MetaText("ポータルとMoodleのWeb画面", maxLines = 2)
                }
            }

            SectionLabel("このアプリについて")
            ListRow(onClick = null, verticalAlignment = Alignment.Top) {
                LeadingIcon(Icons.Outlined.Info, contentDescription = null)
                Text(
                    "立命館大学の学生が作った非公式のMoodle+Rクライアントです。大学・Moodle公式のアプリではありません。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.inkSecondary,
                    modifier = Modifier.weight(1f).padding(start = Spacing.md),
                )
            }

            Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xl)) {
                TertiaryButton("ログアウト", onClick = { confirmLogout = true }, destructive = true)
            }
        }
    }

    if (confirmClearWeb) {
        ConfirmSheet(
            title = "Webのログイン状態を削除",
            message = "ポータルとMoodleのWeb画面のログイン状態を削除します。次に開くときはもう一度ログインが必要です。アプリのログインはそのままです。",
            confirmLabel = "削除",
            onConfirm = {
                confirmClearWeb = false
                scope.launch {
                    container.clearAllWebSessions()
                    android.widget.Toast.makeText(context, "Webのログイン状態を削除しました", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirmClearWeb = false },
        )
    }

    if (confirmLogout) {
        val draftCount = remember { container.savedDraftCount() }
        ConfirmSheet(
            title = "ログアウト",
            message = "この端末に保存したログイン情報と読み込み済みの内容を削除します。" +
                (if (draftCount > 0) "未送信の課題の下書きが${draftCount}件あり、これも削除されます。" else ""),
            confirmLabel = "ログアウト",
            onConfirm = {
                confirmLogout = false
                // 画面 scope ではなくセッション管理側が完了まで所有する
                container.logout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}
