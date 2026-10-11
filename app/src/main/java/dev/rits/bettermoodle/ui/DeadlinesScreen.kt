package dev.rits.bettermoodle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.data.ActionEventPage
import dev.rits.bettermoodle.data.Course
import dev.rits.bettermoodle.data.DeadlineDoneStore
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.parseCourseModuleIdFromUrl
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Motion
import dev.rits.bettermoodle.ui.theme.Sizes
import dev.rits.bettermoodle.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** 期限切れも一覧に出すため、少し前から取得する (Moodle のタイムラインと同じ 14日) */
internal const val DEADLINE_LOOKBACK_SEC = 14L * 24 * 3600

/** 締切の読み込み (今日画面と課題画面で同じキャッシュを共有する) */
@Composable
internal fun rememberDeadlines(container: AppContainer, fromEpochSec: Long): Pair<UiState<ActionEventPage>, () -> Unit> =
    rememberLoadable("deadlines") { container.moodleRepository.upcomingDeadlines(fromEpochSec) }

/** 課題タブのバッジ (24時間以内の未完了件数) を更新する */
@Composable
internal fun PublishDeadlineBadge(container: AppContainer, events: List<ActionEvent>?, done: Set<String>) {
    LaunchedEffect(events, done) {
        if (events == null) return@LaunchedEffect
        val now = Instant.now().epochSecond
        container.deadlineBadge.value = events.count {
            it.timesort in now until now + 24 * 3600 && DeadlineDoneStore.keyOf(it) !in done
        }
    }
}

private enum class DeadlineFilter(val label: String) { All("すべて"), Open("未完了") }

/**
 * 課題タブ (DESIGN.md §8.3・§9)。
 * フィルタ → 期限切れ / 今日 / 明日 / 今週 / 来週以降 のセクション → 完了の折りたたみ。
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
    // 更新世代: 明示的な更新のたびに検索開始時刻を取り直し、追加ページも捨てる
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val fromEpochSec = remember(generation) { Instant.now().epochSecond - DEADLINE_LOOKBACK_SEC }
    val (state, reloadFirstPage) = rememberDeadlines(container, fromEpochSec)
    val refresh = {
        generation++
        reloadFirstPage()
    }
    val firstPage = (state as? UiState.Success)?.data
    var extraPages by remember(generation) { mutableStateOf<List<ActionEventPage>>(emptyList()) }
    var loadingMore by remember(generation) { mutableStateOf(false) }
    var loadMoreError by remember(generation) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val done by container.deadlineDoneStore.done.collectAsState()
    var filter by rememberSaveable { mutableStateOf(DeadlineFilter.All) }
    var courseFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    fun loadMore() {
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
                if (requestGeneration == generation) extraPages = extraPages + page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == generation) loadMoreError = loadErrorMessage(e)
            }
            if (requestGeneration == generation) loadingMore = false
        }
    }

    fun open(event: ActionEvent) = openDeadlineEvent(event, onOpenUrl, onOpenAssignment, onOpenQuiz)
    fun setDone(event: ActionEvent, value: Boolean) = container.deadlineDoneStore.setDone(DeadlineDoneStore.keyOf(event), value)

    val allEvents = (state as? UiState.Success)?.let { s ->
        (s.data.events + extraPages.flatMap { it.events }).distinctBy { it.id }.sortedBy { it.timesort }
    }
    PublishDeadlineBadge(container, allEvents, done)

    Column(Modifier.fillMaxSize()) {
        StaleDataBanner(state, onRetry = refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(
                    "${s.message}。締切リマインダーの通知は引き続き届きます。",
                    onRetry = refresh,
                    title = "課題を読み込めませんでした",
                )
                is UiState.Success -> {
                    val events = allEvents.orEmpty()
                    val lastPage = extraPages.lastOrNull() ?: s.data
                    val incompleteReason = (listOf(s.data) + extraPages).firstNotNullOfOrNull { it.incompleteReason }
                    val nowSec = Instant.now().epochSecond
                    val now = ZonedDateTime.now(ZoneId.systemDefault())
                    val courses = events.mapNotNull { it.courseLabel() }.distinct()
                    val visible = events.filter { courseFilter == null || it.courseLabel() == courseFilter }
                    val (doneEvents, openEvents) = visible.partition { DeadlineDoneStore.keyOf(it) in done }
                    // 期限を過ぎて完了扱いにしたものは完了の折りたたみにだけ出す
                    val sections = openEvents.groupBy { deadlineSectionOf(now, it.timesort) }
                    val shownDone = if (filter == DeadlineFilter.Open) emptyList() else doneEvents

                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = RowListPadding) {
                        item(key = "filters") {
                            DeadlineFilters(
                                filter = filter,
                                onFilter = { filter = it },
                                courses = courses,
                                course = courseFilter,
                                onCourse = { courseFilter = it },
                            )
                        }
                        // 完了扱いだけが残っている場合は、下の「未完了の締切はありません」か完了の折りたたみで示す
                        if (openEvents.isEmpty() && doneEvents.isEmpty() && !lastPage.hasMore && incompleteReason == null) {
                            item(key = "empty") {
                                Box(Modifier.fillMaxWidth().padding(top = Spacing.xxl)) {
                                    EmptyBox(
                                        if (courseFilter != null) "このコースの締切はありません" else "締切はありません 🎉",
                                        "新しい課題が出ると通知でお知らせします。",
                                    )
                                }
                            }
                        }
                        if (openEvents.isEmpty() && doneEvents.isNotEmpty()) {
                            item(key = "all-done") {
                                MetaText(
                                    "未完了の締切はありません",
                                    modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.xl),
                                )
                            }
                        }
                        DeadlineSection.entries.forEach { section ->
                            val list = sections[section].orEmpty()
                            if (list.isEmpty()) return@forEach
                            item(key = "h-${section.name}") {
                                SectionLabel(
                                    section.label,
                                    modifier = Modifier.animateItem(),
                                    color = if (section == DeadlineSection.Overdue) AppTheme.colors.danger else AppTheme.colors.inkSecondary,
                                )
                            }
                            items(list, key = { it.id }) { event ->
                                SwipeToCompleteRow(onComplete = { setDone(event, true) }, modifier = Modifier.animateItem()) {
                                    DeadlineRow(
                                        event = event,
                                        nowSec = nowSec,
                                        done = false,
                                        onToggleDone = { setDone(event, it) },
                                        onClick = { open(event) },
                                    )
                                }
                            }
                        }
                        if (shownDone.isNotEmpty()) {
                            item(key = "done-header") {
                                DoneHeader(count = shownDone.size, expanded = showDone, onToggle = { showDone = !showDone })
                            }
                            if (showDone) {
                                items(shownDone, key = { "done-${it.id}" }) { event ->
                                    DeadlineRow(
                                        event = event,
                                        nowSec = nowSec,
                                        done = true,
                                        onToggleDone = { setDone(event, it) },
                                        onClick = { open(event) },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                        if (incompleteReason != null) {
                            item(key = "incomplete") {
                                Column(Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.lg)) {
                                    MessageBanner(
                                        "一覧を最後まで取得できませんでした ($incompleteReason)。表示されていない締切があるかもしれません。",
                                        isError = true,
                                        onDismiss = null,
                                    )
                                }
                            }
                        }
                        if (lastPage.hasMore || loadMoreError != null) {
                            item(key = "load-more") {
                                LoadMoreRow(
                                    loading = loadingMore,
                                    error = loadMoreError,
                                    label = "さらに先の締切があります",
                                    onLoadMore = ::loadMore,
                                )
                            }
                        }
                        item(key = "sync") { SyncFooter(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeadlineFilters(
    filter: DeadlineFilter,
    onFilter: (DeadlineFilter) -> Unit,
    courses: List<String>,
    course: String?,
    onCourse: (String?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = AppTheme.colors.accentSoft,
        selectedLabelColor = AppTheme.colors.accent,
        selectedTrailingIconColor = AppTheme.colors.accent,
        labelColor = AppTheme.colors.inkSecondary,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ScreenGutter, vertical = Spacing.sm),
    ) {
        DeadlineFilter.entries.forEach { f ->
            FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.label) }, colors = colors)
        }
        if (courses.isNotEmpty()) {
            Box {
                FilterChip(
                    selected = course != null,
                    onClick = { menu = true },
                    label = { Text(course ?: "コース別", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = colors,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("すべてのコース") }, onClick = { menu = false; onCourse(null) })
                    courses.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { menu = false; onCourse(name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = ScreenGutter, end = ScreenGutter, top = Spacing.xl, bottom = Spacing.sm)
            .semantics { stateDescription = if (expanded) "展開" else "折りたたみ" },
    ) {
        Text(
            "完了（$count）",
            style = MaterialTheme.typography.labelLarge,
            color = AppTheme.colors.inkSecondary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = AppTheme.colors.inkSecondary,
        )
    }
}

/** 右へスワイプで「完了にする (アプリ内のみ)」。行は元の位置に戻り、完了の折りたたみへ移る */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToCompleteRow(onComplete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onComplete()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        modifier = modifier,
        backgroundContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.colors.surfaceSunken)
                    .padding(horizontal = ScreenGutter),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = AppTheme.colors.inkSecondary)
                Spacer(Modifier.width(Spacing.sm))
                Text("完了にする", style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.inkSecondary)
            }
        },
    ) {
        Box(Modifier.background(AppTheme.colors.canvas)) { content() }
    }
}

/**
 * 締切の1行 (DESIGN.md §8.3):
 * 完了チェック (48dp) | 授業カラーのドット + 授業名 caption / 種類アイコン + 課題名 body-sm | 締切 caption
 * 締切の色は必ず文言と併用する。
 */
@Composable
internal fun DeadlineRow(
    event: ActionEvent,
    nowSec: Long,
    done: Boolean,
    onToggleDone: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCheck: Boolean = true,
) {
    val colors = AppTheme.colors
    val urgency = urgencyOf(nowSec, event.timesort)
    val label = deadlineLabel(nowSec, event.timesort)
    val course = event.courseLabel()
    val cc = courseColor(event.courseCode(), event.course?.id, course.orEmpty())
    val title = event.activityname ?: event.name
    // チェック直後に円が塗られるのを見せてから一覧を動かす (Things 3 のチェック感)
    var checking by remember(event.id, done) { mutableStateOf(false) }
    LaunchedEffect(checking) {
        if (checking) {
            delay(Motion.BASE_MS.toLong())
            onToggleDone(true)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(course, title, label, if (done) "完了扱い" else null).joinToString("、")
                customActions = listOf(
                    CustomAccessibilityAction(if (done) "未完了に戻す" else "完了にする") {
                        onToggleDone(!done)
                        true
                    },
                )
            }
            .padding(end = ScreenGutter, top = Spacing.md, bottom = Spacing.md),
    ) {
        if (showCheck) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Sizes.hitMin + Spacing.sm, Sizes.hitMin)
                    // toggleable にしてチェック状態も読み上げる (Role だけでは状態が伝わらない)
                    .toggleable(value = done || checking, role = Role.Checkbox) { checked ->
                        when {
                            checking -> Unit // 完了の反映待ち
                            !checked -> onToggleDone(false)
                            else -> checking = true
                        }
                    },
            ) {
                CheckCircle(checked = done || checking)
            }
        } else {
            Spacer(Modifier.width(ScreenGutter))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CourseDot(cc)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    course ?: "コース不明",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xxs)) {
                Icon(event.kindIcon(), contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) colors.inkSecondary else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        MetaText(
            label,
            color = when {
                done -> colors.inkSecondary
                urgency == Urgency.Overdue -> colors.danger
                urgency == Urgency.Soon -> colors.warning
                else -> colors.inkSecondary
            },
        )
    }
}

internal fun openDeadlineEvent(
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

internal fun ActionEvent.courseLabel(): String? =
    course?.fullname?.let { Course(fullname = it).displayName.ifBlank { it } }

internal fun ActionEvent.courseCode(): String? = course?.fullname?.let { Course(fullname = it).courseCode }

private fun ActionEvent.kindIcon(): ImageVector = when (modulename) {
    "assign" -> Icons.AutoMirrored.Outlined.Assignment
    "quiz" -> Icons.Outlined.Quiz
    "forum" -> Icons.Outlined.Forum
    else -> Icons.Outlined.Event
}
