package dev.rits.bettermoodle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.data.DeadlineDoneStore
import dev.rits.bettermoodle.data.Timetable
import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.periodRange
import dev.rits.bettermoodle.data.timetableCellsConnected
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Sizes
import dev.rits.bettermoodle.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 時間割タブ (DESIGN.md §8.2・§5「画面幅ごとの時間割」)。
 * - 1画面に1週間全体が収まることを優先 (行の最小高 64dp に満たないときだけ縦スクロール)
 * - 600dp 以上は右に選択コマの詳細、840dp 以上は何も選んでいないとき今日の締切
 * - 最大フォントサイズ付近ではグリッドをやめてリスト表示にする
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    container: AppContainer,
    onOpenCourse: (courseId: Long, courseName: String, courseCode: String?) -> Unit,
    onOpenDeadline: (ActionEvent) -> Unit = {},
) {
    val (state, refresh) = rememberLoadable("timetable") { container.moodleRepository.timetable() }
    val now = rememberMinuteTicker()
    val timetable = (state as? UiState.Success)?.data
    LaunchedEffect(timetable) { timetable?.let { container.courseColorStore.updateTimetable(it.entries) } }
    val largeText = LocalDensity.current.fontScale >= 1.6f
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        StaleDataBanner(state, onRetry = refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(
                    "${s.message}。時間割はMoodleのダッシュボードから取得しています。",
                    onRetry = refresh,
                    title = "時間割を読み込めませんでした",
                )
                is UiState.Success -> if (s.data.entries.isEmpty()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EmptyBox(
                            "時間割が見つかりませんでした",
                            "Moodleのダッシュボードに時間割ブロックがあるか確認してください。時間割にない授業は「コース」タブから開けます。",
                            onRetry = refresh,
                        )
                    }
                } else if (largeText) {
                    TimetableList(s.data, now, onOpenCourse)
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val height = maxHeight
                        val wide = maxWidth >= 600.dp
                        val extraWide = maxWidth >= 840.dp
                        val openEntry: (TimetableEntry) -> Unit = { entry ->
                            if (wide) {
                                selected = cellKey(entry)
                            } else {
                                entry.courseId?.let { onOpenCourse(it, entry.title, entry.courseCode) }
                            }
                        }
                        if (!wide) {
                            WeekGrid(s.data, now, selected = null, availableHeight = height, onOpen = openEntry)
                        } else {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1.5f).fillMaxHeight()) {
                                    WeekGrid(s.data, now, selected = selected, availableHeight = height, onOpen = openEntry)
                                }
                                VerticalDivider(thickness = 0.5.dp, color = AppTheme.colors.hairline)
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    val entry = s.data.entries.firstOrNull { cellKey(it) == selected }
                                    when {
                                        entry != null -> CellDetailPane(entry, s.data, onOpenCourse)
                                        extraWide -> TodayDeadlinesPane(container, onOpenDeadline)
                                        else -> PaneHint()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun cellKey(entry: TimetableEntry) = "${entry.dayIndex}-${entry.period}-${entry.courseCode ?: entry.title}"

/** 1分ごとに更新される現在時刻 (残り時間・現在の時限のため)。アニメーションはしない */
@Composable
internal fun rememberMinuteTicker(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now = current
            delay((60 - current.second) * 1000L)
        }
    }
    return now
}

internal val WeekdayNames = listOf("月", "火", "水", "木", "金", "土", "日")

/** 表示する最終時限 (最低5限) */
private fun lastPeriod(timetable: Timetable) = maxOf(timetable.entries.maxOfOrNull { it.period } ?: 5, 5)

/** [now] が授業時間内なら、その時限 */
private fun currentPeriod(now: LocalDateTime): Int? =
    (1..7).firstOrNull { p -> periodRange(p)?.let { now.toLocalTime() >= it.first && now.toLocalTime() < it.second } == true }

private val DayHeaderHeight = 36.dp

@Composable
private fun WeekGrid(
    timetable: Timetable,
    now: LocalDateTime,
    selected: String?,
    availableHeight: Dp,
    onOpen: (TimetableEntry) -> Unit,
) {
    val maxPeriod = lastPeriod(timetable)
    val todayIndex = now.dayOfWeek.value - 1
    val nowPeriod = currentPeriod(now)
    val byCell = remember(timetable) { timetable.entries.groupBy { it.dayIndex to it.period } }
    // 1画面に収まるなら行を伸ばし、収まらないときだけ最小高 64dp でスクロールさせる
    val fit = (availableHeight - DayHeaderHeight - Spacing.sm) / maxPeriod
    val rowHeight = if (fit > Sizes.gridRowMin) fit else Sizes.gridRowMin
    var picker by remember { mutableStateOf<List<TimetableEntry>?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.xs, end = Spacing.sm, bottom = Spacing.sm),
    ) {
        Column(Modifier.width(Sizes.gridHeaderCol)) {
            Spacer(Modifier.height(DayHeaderHeight))
            for (period in 1..maxPeriod) {
                PeriodHeader(period, current = nowPeriod == period && todayIndex in timetable.dayLabels.indices, height = rowHeight)
            }
        }
        timetable.dayLabels.forEachIndexed { day, label ->
            Column(Modifier.weight(1f)) {
                DayHeader(label, today = day == todayIndex)
                var period = 1
                while (period <= maxPeriod) {
                    val entries = byCell[day to period].orEmpty()
                    if (entries.isEmpty()) {
                        EmptyCell(Modifier.height(rowHeight))
                        period++
                        continue
                    }
                    var span = 1
                    while (period + span <= maxPeriod &&
                        timetableCellsConnected(byCell[day to (period + span - 1)].orEmpty(), byCell[day to (period + span)].orEmpty())
                    ) {
                        span++
                    }
                    val first = period
                    val isNow = day == todayIndex && nowPeriod != null && nowPeriod in first until first + span
                    TimetableCell(
                        entries = entries,
                        dayLabel = label,
                        firstPeriod = first,
                        span = span,
                        current = isNow,
                        selected = entries.any { cellKey(it) == selected },
                        modifier = Modifier.height(rowHeight * span),
                        onClick = { if (entries.size >= 2) picker = entries else onOpen(entries.first()) },
                    )
                    period += span
                }
            }
        }
    }

    picker?.let { entries ->
        CellPickerSheet(entries, onDismiss = { picker = null }, onOpen = {
            picker = null
            onOpen(it)
        })
    }
}

/** 列ヘッダー: 曜日。今日は accent 文字 + accent-soft の丸背景 */
@Composable
private fun DayHeader(label: String, today: Boolean) {
    val c = AppTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(DayHeaderHeight)
            .semantics { contentDescription = if (today) "${label}曜 (今日)" else "${label}曜" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (today) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (today) c.accent else c.inkSecondary)
        }
    }
}

/** 行ヘッダー (28dp 幅): 時限番号 + 開始時刻。現在の時限には accent のドット */
@Composable
private fun PeriodHeader(period: Int, current: Boolean, height: Dp) {
    val c = AppTheme.colors
    val start = Timetable.PERIOD_TIMES[period]?.first
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(top = Spacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = "${period}限" + (start?.let { " $it から" } ?: "") + if (current) "、授業中" else ""
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$period", style = MaterialTheme.typography.labelLarge, color = if (current) c.accent else c.ink)
        if (start != null) {
            Text(start, style = MaterialTheme.typography.labelSmall, color = c.inkSecondary, maxLines = 1, softWrap = false)
        }
        if (current) {
            Spacer(Modifier.height(Spacing.xs))
            AccentDot()
        }
    }
}

/** 空きコマ: 塗らず、hairline の破線枠だけ */
@Composable
private fun EmptyCell(modifier: Modifier) {
    val hairline = AppTheme.colors.hairline
    Box(
        modifier
            .fillMaxWidth()
            .padding(Spacing.xxs)
            .clearAndSetSemantics { }
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = hairline,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(Radius.sm.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
    )
}

/**
 * 授業のセル: 授業カラー bg + fg の授業名 (600・最大3行) + 教室 (caption 1行)。
 * 連続時限は1つのセルとして縦に結合する。現在の時限は 2px の accent 枠。
 */
@Composable
private fun TimetableCell(
    entries: List<TimetableEntry>,
    dayLabel: String,
    firstPeriod: Int,
    span: Int,
    current: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val entry = entries.first()
    val cc = courseColor(entry.courseCode, entry.courseId, entry.title)
    val shape = RoundedCornerShape(Radius.sm)
    val periodLabel = if (span > 1) "$firstPeriod–${firstPeriod + span - 1}限" else "${firstPeriod}限"
    val room = entries.firstNotNullOfOrNull { it.room?.takeIf(String::isNotBlank) }
    val description = buildString {
        append("${dayLabel}曜 $periodLabel、")
        append(entries.joinToString("、") { it.title })
        room?.let { append("、教室 $it") }
        if (current) append("、授業中")
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(Spacing.xxs)
            .clip(shape)
            .background(cc.bg)
            .then(
                when {
                    current -> Modifier.border(BorderStroke(2.dp, AppTheme.colors.accent), shape)
                    selected -> Modifier.border(BorderStroke(2.dp, cc.fg), shape)
                    else -> Modifier
                },
            )
            .clickable(onClickLabel = "開く", onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(6.dp),
    ) {
        Column {
            Text(
                entry.title,
                style = MaterialTheme.typography.labelSmall,
                color = cc.fg,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entries.size > 1) {
                Text("他${entries.size - 1}件", style = MaterialTheme.typography.labelSmall, color = cc.fg, maxLines = 1)
            }
            if (room != null) {
                Text(
                    room,
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellPickerSheet(entries: List<TimetableEntry>, onDismiss: () -> Unit, onOpen: (TimetableEntry) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppTheme.colors.surface) {
        Text(
            "このコマの授業",
            style = MaterialTheme.typography.labelLarge,
            color = AppTheme.colors.inkSecondary,
            modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm),
        )
        entries.forEach { candidate ->
            val cc = courseColor(candidate.courseCode, candidate.courseId, candidate.title)
            ListRow(
                onClick = { onOpen(candidate) },
                enabled = candidate.courseId != null,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CourseSquare(cc, candidate.title)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(candidate.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    candidate.room?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** 600dp 以上の右ペイン: 選んだコマの詳細 */
@Composable
private fun CellDetailPane(
    entry: TimetableEntry,
    timetable: Timetable,
    onOpenCourse: (Long, String, String?) -> Unit,
) {
    val cc = courseColor(entry.courseCode, entry.courseId, entry.title)
    val slots = timetable.entries
        .filter { (it.courseCode != null && it.courseCode == entry.courseCode) || (it.courseId != null && it.courseId == entry.courseId) }
        .ifEmpty { listOf(entry) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.gutterTablet),
    ) {
        Box(Modifier.width(32.dp).height(4.dp).background(cc.fg, RoundedCornerShape(Radius.xs)))
        Spacer(Modifier.height(Spacing.md))
        Text(entry.title, style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.sm))
        slots.sortedWith(compareBy({ it.dayIndex }, { it.period })).forEach { slot ->
            val range = Timetable.PERIOD_TIMES[slot.period]
            MetaText("${WeekdayNames.getOrElse(slot.dayIndex) { "" }}曜 ${slot.period}限" + (range?.let { " ${it.first}–${it.second}" } ?: ""))
        }
        entry.room?.takeIf { it.isNotBlank() }?.let { room ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.md)) {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = cc.fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(room, style = MaterialTheme.typography.titleMedium, color = cc.fg)
            }
        }
        entry.courseCode?.let { MetaText("授業コード $it", modifier = Modifier.padding(top = Spacing.sm)) }
        val id = entry.courseId
        if (id != null) {
            Spacer(Modifier.height(Spacing.xl))
            SecondaryButton("コースを開く", onClick = { onOpenCourse(id, entry.title, entry.courseCode) })
        }
    }
}

@Composable
private fun PaneHint() {
    Box(Modifier.fillMaxSize().padding(Spacing.gutterTablet), contentAlignment = Alignment.Center) {
        Text("コマを選ぶと詳細を表示します", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.inkSecondary)
    }
}

/** 840dp 以上の右サイド: 今日の締切 */
@Composable
private fun TodayDeadlinesPane(container: AppContainer, onOpenDeadline: (ActionEvent) -> Unit) {
    val from = remember { Instant.now().epochSecond - DEADLINE_LOOKBACK_SEC }
    val (state, _) = rememberDeadlines(container, from)
    val done by container.deadlineDoneStore.done.collectAsState()
    val nowSec = Instant.now().epochSecond
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    val events = (state as? UiState.Success)?.data?.events.orEmpty()
        .filter { deadlineSectionOf(now, it.timesort) == DeadlineSection.Today && DeadlineDoneStore.keyOf(it) !in done }
        .sortedBy { it.timesort }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("今日の締切") }
        if (events.isEmpty()) {
            item {
                MetaText(
                    if (state is UiState.Success) "今日の締切はありません" else "読み込み中",
                    modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm),
                )
            }
        }
        items(events, key = { it.id }) { event ->
            DeadlineRow(
                event = event,
                nowSec = nowSec,
                done = false,
                onToggleDone = { container.deadlineDoneStore.setDone(DeadlineDoneStore.keyOf(event), it) },
                onClick = { onOpenDeadline(event) },
            )
        }
    }
}

/** 最大フォントサイズ時のリスト表示 (DESIGN.md §4・§11) */
@Composable
private fun TimetableList(
    timetable: Timetable,
    now: LocalDateTime,
    onOpenCourse: (Long, String, String?) -> Unit,
) {
    val todayIndex = now.dayOfWeek.value - 1
    val byDay = timetable.entries.groupBy { it.dayIndex }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = RowListPadding) {
        byDay.forEach { (day, entries) ->
            item(key = "d-$day") {
                SectionLabel(
                    WeekdayNames.getOrElse(day) { "" } + "曜" + if (day == todayIndex) " (今日)" else "",
                    color = if (day == todayIndex) AppTheme.colors.accent else AppTheme.colors.inkSecondary,
                )
            }
            items(entries.sortedBy { it.period }, key = { "e-$day-${it.period}-${it.title}" }) { entry ->
                val cc = courseColor(entry.courseCode, entry.courseId, entry.title)
                val range = Timetable.PERIOD_TIMES[entry.period]
                ListRow(
                    onClick = entry.courseId?.let { id -> { onOpenCourse(id, entry.title, entry.courseCode) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).height(40.dp).background(cc.fg, RoundedCornerShape(Radius.xs)))
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        MetaText("${entry.period}限" + (range?.let { " ${it.first}–${it.second}" } ?: ""))
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        entry.room?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
                    }
                }
            }
        }
    }
}
