package dev.rits.bettermoodle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.data.ActionEventPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.parseCourseModuleIdFromUrl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 課題締切だけを集めた画面。「通知がごちゃまぜ」問題への回答。
 * core_calendar_get_action_events_by_timesort を assign/quiz 等に絞って表示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlinesScreen(
    container: AppContainer,
    onOpenUrl: (url: String, title: String) -> Unit = { _, _ -> },
    onOpenAssignment: (courseId: Long, moduleId: Long, assignId: Long, title: String) -> Unit = { _, _, _, _ -> },
    onOpenQuiz: (target: QuizTarget, title: String) -> Unit = { _, _ -> },
) {
    // 更新世代: 明示的な更新のたびに検索開始時刻を取り直し、追加ページも捨てる。
    // 追加取得中は同じ世代 (同じ開始時刻) のスナップショットを使う
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val fromEpochSec = remember(generation) { Instant.now().epochSecond - 3600 }
    val (state, reloadFirstPage) = rememberLoadable("deadlines") {
        container.moodleRepository.upcomingDeadlines(fromEpochSec)
    }
    val refresh = {
        generation++
        reloadFirstPage()
    }
    val firstPage = (state as? UiState.Success)?.data
    var extraPages by remember(generation) { mutableStateOf<List<ActionEventPage>>(emptyList()) }
    var loadingMore by remember(generation) { mutableStateOf(false) }
    var loadMoreError by remember(generation) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMore() {
        // 先頭ページの再取得中は旧カーソルで続きを取らない
        if (state.isRefreshing) return
        val last = extraPages.lastOrNull() ?: firstPage ?: return
        val after = last.nextAfterEventId ?: return
        if (loadingMore) return
        loadingMore = true
        loadMoreError = null
        val requestGeneration = generation
        scope.launch {
            try {
                val page = container.moodleRepository.upcomingDeadlines(fromEpochSec, afterEventId = after)
                // 取得中に更新されていたら古い世代の続きは捨てる
                if (requestGeneration == generation) extraPages = extraPages + page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == generation) loadMoreError = loadErrorMessage(e)
            }
            if (requestGeneration == generation) loadingMore = false
        }
    }

    // 更新失敗のバナーは 0件表示より外側に置く (「以前は0件・今回の取得は失敗」を区別する)
    Column(Modifier.fillMaxSize()) {
    StaleDataBanner(state, onRetry = refresh)
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val s = state) {
            is UiState.Loading -> LoadingBox()
            is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
            is UiState.Success -> {
                val lastPage = extraPages.lastOrNull() ?: s.data
                val allEvents = (s.data.events + extraPages.flatMap { it.events }).distinctBy { it.id }
                val incompleteReason = (listOf(s.data) + extraPages).firstNotNullOfOrNull { it.incompleteReason }
                if (allEvents.isEmpty() && !lastPage.hasMore && incompleteReason == null) {
                    EmptyBox("今後の課題・小テストの締切はありません 🎉", onRetry = refresh, icon = Icons.Outlined.EventAvailable)
                    return@PullToRefreshBox
                }
                val zone = ZoneId.systemDefault()
                val grouped = allEvents.sortedBy { it.timesort }
                    .groupBy { Instant.ofEpochSecond(it.timesort).atZone(zone).toLocalDate() }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ListContentPadding,
                    verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
                ) {
                    grouped.entries.forEachIndexed { groupIndex, (date, events) ->
                        item(key = "header-$date") {
                            DateHeader(
                                date = date,
                                count = events.size,
                                modifier = Modifier.padding(top = if (groupIndex == 0) 4.dp else 16.dp, bottom = 2.dp),
                            )
                        }
                        items(events, key = { it.id }) { event ->
                            DeadlineCard(event) {
                                openDeadlineEvent(
                                    event = event,
                                    onOpenUrl = onOpenUrl,
                                    onOpenAssignment = onOpenAssignment,
                                    onOpenQuiz = onOpenQuiz,
                                )
                            }
                        }
                    }
                    if (incompleteReason != null) {
                        item(key = "incomplete") {
                            MessageBanner(
                                "一覧を最後まで取得できませんでした ($incompleteReason)。表示は全件ではない可能性があります。",
                                isError = true,
                                onDismiss = null,
                            )
                        }
                    }
                    if (lastPage.hasMore || loadMoreError != null) {
                        item(key = "load-more") {
                            LoadMoreRow(
                                loading = loadingMore,
                                error = loadMoreError,
                                label = "まだ先の締切があります",
                                onLoadMore = ::loadMore,
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

private fun openDeadlineEvent(
    event: ActionEvent,
    onOpenUrl: (url: String, title: String) -> Unit,
    onOpenAssignment: (courseId: Long, moduleId: Long, assignId: Long, title: String) -> Unit,
    onOpenQuiz: (target: QuizTarget, title: String) -> Unit,
) {
    val title = event.activityname ?: event.name
    val actionUrl = event.action?.url?.takeIf { it.isNotBlank() }
    val fallbackUrl = actionUrl ?: event.url
    val courseId = event.course?.id?.takeIf { it > 0L }
    val moduleId = parseCourseModuleIdFromUrl(actionUrl)
    val instanceId = event.instance?.takeIf { it > 0L }

    when (event.modulename) {
        "assign" -> if (courseId != null && moduleId != null && instanceId != null) {
            onOpenAssignment(courseId, moduleId, instanceId, title)
            return
        }
        "quiz" -> if (courseId != null && moduleId != null && instanceId != null) {
            onOpenQuiz(
                QuizTarget(
                    courseId = courseId,
                    courseModuleId = moduleId,
                    quizInstanceId = instanceId,
                    contextId = null,
                    modName = "quiz",
                    url = actionUrl,
                ),
                title,
            )
            return
        }
    }

    if (!fallbackUrl.isNullOrBlank()) {
        onOpenUrl(fallbackUrl, title)
    }
}

private fun dateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今日"
        today.plusDays(1) -> "明日"
        else -> date.format(DateTimeFormatter.ofPattern("M/d (E)"))
    }
}

/** 日付ごとの見出し。今日・明日は色で区別し、件数を添えて「この日にいくつあるか」を先に伝える。 */
@Composable
private fun DateHeader(date: LocalDate, count: Int, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val accent = when (date) {
        today -> MaterialTheme.colorScheme.error
        today.plusDays(1) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val subLabel = when (date) {
        today, today.plusDays(1) -> date.format(DateTimeFormatter.ofPattern("M/d (E)"))
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(dateLabel(date), style = MaterialTheme.typography.titleMedium, color = accent)
        if (subLabel != null) {
            Spacer(Modifier.width(6.dp))
            MetaText(subLabel)
        }
        Spacer(Modifier.weight(1f))
        MetaText("${count}件")
    }
}

/** 残り時間の短い表現。1日以内は時間、それ以上は日数で丸める。 */
private fun remainingLabel(nowEpochSec: Long, deadlineEpochSec: Long): String? {
    val diff = deadlineEpochSec - nowEpochSec
    return when {
        diff < 0 -> "期限切れ"
        diff < 3600 -> "あと${maxOf(1, diff / 60)}分"
        diff < 24 * 3600 -> "あと${diff / 3600}時間"
        diff < 7 * 24 * 3600 -> "あと${diff / (24 * 3600)}日"
        else -> null
    }
}

@Composable
private fun DeadlineCard(event: ActionEvent, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochSecond(event.timesort).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    val now = Instant.now().epochSecond
    val isUrgent = event.timesort - now < 24 * 3600
    val remaining = remainingLabel(now, event.timesort)
    val accent = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val (icon, kindLabel) = when (event.modulename) {
        "assign" -> Icons.AutoMirrored.Filled.Assignment to "課題"
        "quiz" -> Icons.Filled.Quiz to "小テスト"
        else -> Icons.Filled.Event to (event.modulename ?: "予定")
    }

    AppCard(onClick = onClick) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左端のアクセントバー: 24時間以内は赤で視線を引く
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                Modifier
                    .width(64.dp)
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    time,
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                )
                MetaText(kindLabel)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(top = 12.dp, bottom = 12.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    event.activityname ?: event.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                event.course?.let { MetaText(it.fullname) }
                if (remaining != null) {
                    StatusPill(
                        remaining,
                        containerColor = if (isUrgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isUrgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(end = 14.dp).size(22.dp),
            )
        }
    }
}
