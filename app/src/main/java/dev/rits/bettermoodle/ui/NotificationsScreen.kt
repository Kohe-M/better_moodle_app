package dev.rits.bettermoodle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.NotificationsPage
import dev.rits.bettermoodle.data.PopupNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class NotificationFilter(val label: String) {
    ALL("すべて"),
    ASSIGN("課題関連"),
    OTHER("その他"),
}

/** Moodleの通知一覧。課題関連とそれ以外をフィルタで分けられる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    container: AppContainer,
    onOpenUrl: (url: String, title: String) -> Unit = { _, _ -> },
) {
    // 更新世代: 明示的な更新のたびに追加ページを捨て、追加取得中に更新されたら古い世代の応答を混ぜない
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val (state, reloadFirstPage) = rememberLoadable("notifications") { container.moodleRepository.notifications() }
    val refresh = {
        generation++
        reloadFirstPage()
    }
    var filter by remember { mutableStateOf(NotificationFilter.ALL) }
    var selectedNotification by remember { mutableStateOf<PopupNotification?>(null) }
    val firstPage = (state as? UiState.Success)?.data
    var extraPages by remember(generation) { mutableStateOf<List<NotificationsPage>>(emptyList()) }
    var loadingMore by remember(generation) { mutableStateOf(false) }
    var loadMoreError by remember(generation) { mutableStateOf<String?>(null) }
    // 既読同期はセッション scope (画面遷移でキャンセルされない)。成功IDと失敗理由を表示に反映する
    val readSync by container.notificationReadSync.state.collectAsState()
    val scope = rememberCoroutineScope()

    fun loadMore() {
        // 先頭ページの再取得中は旧 offset で続きを取らない
        if (state.isRefreshing) return
        val last = extraPages.lastOrNull() ?: firstPage ?: return
        if (!last.hasMore || loadingMore) return
        loadingMore = true
        loadMoreError = null
        val requestGeneration = generation
        scope.launch {
            try {
                val page = container.moodleRepository.notifications(offset = last.nextOffset)
                if (requestGeneration == generation) extraPages = extraPages + page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == generation) loadMoreError = loadErrorMessage(e)
            }
            if (requestGeneration == generation) loadingMore = false
        }
    }

    fun markRead(n: PopupNotification) {
        if (n.read) return
        container.notificationReadSync.request(n.id)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 更新失敗の表示は 0件・フィルタ0件の表示より外側に置く
        StaleDataBanner(state, onRetry = refresh)
        readSync.failed.entries.firstOrNull()?.let { (_, reason) ->
            Column(Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp)) {
                MessageBanner(
                    "既読にできなかった通知があります ($reason)。Moodle側では未読のままです。もう一度開くと再試行します。",
                    isError = true,
                    onDismiss = null,
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.weight(1f),
        ) {
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(
                    "${s.message}。Moodleからの通知を表示できません。",
                    onRetry = refresh,
                    title = "通知を読み込めませんでした",
                )
                is UiState.Success -> {
                    val lastPage = extraPages.lastOrNull() ?: s.data
                    val all = (s.data.notifications + extraPages.flatMap { it.notifications })
                        .distinctBy { it.id }
                        .map { if (it.id in readSync.readIds) it.copy(read = true) else it }
                    val filtered = all.filter {
                        when (filter) {
                            NotificationFilter.ALL -> true
                            NotificationFilter.ASSIGN -> it.isAssignRelated()
                            NotificationFilter.OTHER -> !it.isAssignRelated()
                        }
                    }
                    val (unread, read) = filtered.partition { !it.read }
                    val filterMenu: @Composable () -> Unit = {
                        NotificationFilterMenu(filter, onSelect = { filter = it })
                    }
                    if (filtered.isEmpty() && !lastPage.hasMore) {
                        Column(Modifier.fillMaxSize()) {
                            SectionLabel("", trailing = filterMenu)
                            EmptyBox(
                                if (all.isEmpty()) "通知はありません" else "「${filter.label}」の通知はありません",
                                if (all.isEmpty()) {
                                    "Moodleからの新しい連絡はここに届きます。"
                                } else {
                                    "ほかの通知は右上の切り替えで表示できます。"
                                },
                            )
                        }
                        return@PullToRefreshBox
                    }
                    fun openNotification(n: PopupNotification) {
                        markRead(n)
                        val contextUrl = n.contexturl?.takeIf { it.isNotBlank() }
                        if (contextUrl != null) {
                            onOpenUrl(contextUrl, n.contexturlname ?: n.subject)
                        } else {
                            selectedNotification = n
                        }
                    }
                    LazyColumn(contentPadding = RowListPadding) {
                        // 見出しの右に1つだけフィルタを置く (まれにしか使わないので常設の行にしない)
                        item(key = "unread-header") {
                            SectionLabel(
                                if (unread.isEmpty()) "未読はありません" else "未読 ${unread.size}件",
                                color = if (unread.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                trailing = filterMenu,
                            )
                        }
                        itemsIndexed(unread, key = { _, it -> it.id }) { index, n ->
                            NotificationRow(n, Modifier.groupedItem(GroupPosition.of(index, unread.size), NotificationTextInset)) {
                                openNotification(n)
                            }
                        }
                        if (read.isNotEmpty()) {
                            item(key = "read-header") { SectionLabel("既読") }
                            itemsIndexed(read, key = { _, it -> it.id }) { index, n ->
                                NotificationRow(n, Modifier.groupedItem(GroupPosition.of(index, read.size), NotificationTextInset)) {
                                    openNotification(n)
                                }
                            }
                        }
                        if (lastPage.hasMore || loadMoreError != null) {
                            item(key = "load-more") {
                                LoadMoreRow(
                                    loading = loadingMore,
                                    error = loadMoreError,
                                    label = "${all.size}件を表示中。古い通知がまだあります",
                                    onLoadMore = ::loadMore,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedNotification?.let { notification ->
        NotificationDetailSheet(
            notification = notification,
            onDismiss = { selectedNotification = null },
            onOpenUrl = onOpenUrl,
        )
    }
}

private fun PopupNotification.isAssignRelated(): Boolean =
    component == "mod_assign" || component == "mod_quiz" ||
        eventtype?.contains("assign") == true ||
        subject.contains("課題") || subject.contains("提出")

@Composable
private fun NotificationFilterMenu(filter: NotificationFilter, onSelect: (NotificationFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(filter.label, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "表示する通知を選ぶ")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NotificationFilter.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label) },
                    onClick = {
                        expanded = false
                        onSelect(f)
                    },
                )
            }
        }
    }
}

/** 本文の開始位置 (未読の点の分だけ字下げ)。区切り線もここから引く */
private val NotificationTextInset = 32.dp

private val TodayTime: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
private val OtherDay: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

/**
 * 通知の1行。未読は藍の点と本文色の件名、既読は点なし・薄い件名。
 * 太字は使わず、点とコントラストで未読を浮かせる。
 */
@Composable
private fun NotificationRow(n: PopupNotification, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val created = Instant.ofEpochSecond(n.timecreated).atZone(ZoneId.systemDefault())
    val time = if (created.toLocalDate() == LocalDate.now()) created.format(TodayTime) else created.format(OtherDay)
    val unread = !n.read
    ListRow(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(end = ScreenGutter, top = 12.dp, bottom = 12.dp)) {
        Box(Modifier.width(NotificationTextInset).padding(top = 7.dp), contentAlignment = Alignment.Center) {
            if (unread) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(c.primary, CircleShape)
                        .semantics { contentDescription = "未読" },
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                n.subject,
                style = MaterialTheme.typography.titleSmall,
                color = if (unread) c.onSurface else c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            n.smallmessage?.takeIf { it.isNotBlank() }?.let { msg ->
                val plain = Jsoup.parse(msg).text()
                // 件名と同じ本文を繰り返さない (Moodleの通知は件名=本文のことが多い)
                if (plain.trim() != n.subject.trim()) {
                    Text(
                        plain,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        MetaText(time, modifier = Modifier.padding(top = 3.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationDetailSheet(
    notification: PopupNotification,
    onDismiss: () -> Unit,
    onOpenUrl: (url: String, title: String) -> Unit,
) {
    val fullHtml = notification.fullmessagehtml
        ?.takeIf { htmlHasRenderableContent(it) }
    val fallbackMessage = notification.smallmessage
        ?.let { Jsoup.parse(it).text().trim() }
        ?.takeIf { it.isNotBlank() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "subject") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(notification.subject, style = MaterialTheme.typography.titleLarge)
                    MetaText(formatMoodleDateTime(notification.timecreated))
                }
            }
            item(key = "body") {
                if (fullHtml != null) {
                    HtmlText(
                        html = fullHtml,
                        onOpenUrl = { url ->
                            onDismiss()
                            onOpenUrl(url, notification.contexturlname ?: notification.subject)
                        },
                    )
                } else {
                    Text(
                        fallbackMessage ?: "本文はありません",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
