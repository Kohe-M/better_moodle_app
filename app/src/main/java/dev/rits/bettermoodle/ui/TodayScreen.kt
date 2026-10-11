package dev.rits.bettermoodle.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.data.DayBlock
import dev.rits.bettermoodle.data.DeadlineDoneStore
import dev.rits.bettermoodle.data.NowCardState
import dev.rits.bettermoodle.data.Timetable
import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.TimetableFocus
import dev.rits.bettermoodle.data.dayBlocks
import dev.rits.bettermoodle.data.nowCardState
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.CourseColor
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 今日画面の締切は今日・明日のものを最大5件 (DESIGN.md §9) */
private const val TODAY_DEADLINE_LIMIT = 5

/**
 * 今日タブ (DESIGN.md §1 原則1・§8.1・§9)。問い = 「次に何があるか」を1秒で答える。
 * Now Card → 今日の授業タイムライン (現在時刻ライン付き) → 今日・明日の締切 → 最終同期
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    container: AppContainer,
    listState: LazyListState,
    onOpenCourse: (courseId: Long, courseName: String, courseCode: String?) -> Unit,
    onOpenDeadline: (ActionEvent) -> Unit,
    onSeeAllDeadlines: () -> Unit,
) {
    val (ttState, refreshTimetable) = rememberLoadable("timetable") { container.moodleRepository.timetable() }
    val from = remember { Instant.now().epochSecond - DEADLINE_LOOKBACK_SEC }
    val (dlState, refreshDeadlines) = rememberDeadlines(container, from)
    val done by container.deadlineDoneStore.done.collectAsState()
    val now = rememberMinuteTicker()
    val refresh = {
        refreshTimetable()
        refreshDeadlines()
    }
    val timetable = (ttState as? UiState.Success)?.data
    LaunchedEffect(timetable) { timetable?.let { container.courseColorStore.updateTimetable(it.entries) } }
    PublishDeadlineBadge(container, (dlState as? UiState.Success)?.data?.events, done)

    Column(Modifier.fillMaxSize()) {
        // 時間割の失敗を優先して出す (起動直後の主情報)
        if (ttState.isStale) StaleDataBanner(ttState, onRetry = refresh) else StaleDataBanner(dlState, onRetry = refresh)
        PullToRefreshBox(
            isRefreshing = ttState.isRefreshing || dlState.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = RowListPadding) {
                item(key = "now") {
                    Box(Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm)) {
                        when (val s = ttState) {
                            is UiState.Loading -> NowCardSkeleton()
                            is UiState.Error -> NowCardMessage(
                                title = "時間割を読み込めませんでした",
                                body = s.message,
                                action = "再試行" to refresh,
                            )
                            is UiState.Success -> if (s.data.entries.isEmpty()) {
                                NowCardMessage(
                                    title = "時間割がありません",
                                    body = "Moodleのダッシュボードの時間割ブロックから授業を読み込みます。",
                                )
                            } else {
                                val card = remember(s.data, now) { nowCardState(s.data.entries, now) }
                                NowCard(card, onOpenCourse)
                            }
                        }
                    }
                }

                val blocks = timetable?.let { dayBlocks(it.entries, now.dayOfWeek.value - 1) }.orEmpty()
                if (timetable != null && timetable.entries.isNotEmpty()) {
                    item(key = "tl-h") { SectionLabel("今日の授業") }
                    if (blocks.isEmpty()) {
                        item(key = "tl-empty") {
                            Text(
                                "今日は授業がありません",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.inkSecondary,
                                modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm),
                            )
                        }
                    } else {
                        val time = now.toLocalTime()
                        val nowIndex = blocks.indexOfFirst { time < it.end }.let { if (it < 0) blocks.size else it }
                        blocks.forEachIndexed { index, block ->
                            if (index == nowIndex && time < block.start) {
                                item(key = "tl-now") { NowMarker(time) }
                            }
                            // 同じコマに複数の授業があると時限が重なるので、並び順で区別する
                            item(key = "tl-$index-${block.firstPeriod}") {
                                TimelineRow(
                                    block = block,
                                    time = time,
                                    onClick = block.entry.courseId?.let { id ->
                                        { onOpenCourse(id, block.entry.title, block.entry.courseCode) }
                                    },
                                )
                            }
                        }
                        if (nowIndex == blocks.size) item(key = "tl-now") { NowMarker(time) }
                    }
                }

                item(key = "dl-h") {
                    SectionLabel("締切", trailing = { TertiaryButton("すべて見る", onClick = onSeeAllDeadlines) })
                }
                when (val s = dlState) {
                    is UiState.Loading -> item(key = "dl-loading") {
                        Column(Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm)) {
                            SkeletonBlock(width = null, height = 14.dp)
                            Spacer(Modifier.height(Spacing.sm))
                            SkeletonBlock(width = 160.dp, height = 12.dp)
                        }
                    }
                    is UiState.Error -> item(key = "dl-error") {
                        Column(Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm)) {
                            MessageBanner("締切を読み込めませんでした (${s.message})", isError = true, onDismiss = null)
                            TertiaryButton("再試行", onClick = refreshDeadlines)
                        }
                    }
                    is UiState.Success -> {
                        val nowZ = ZonedDateTime.now(ZoneId.systemDefault())
                        val nowSec = nowZ.toEpochSecond()
                        val soon = s.data.events
                            .filter {
                                val section = deadlineSectionOf(nowZ, it.timesort)
                                (section == DeadlineSection.Today || section == DeadlineSection.Tomorrow) &&
                                    DeadlineDoneStore.keyOf(it) !in done
                            }
                            .sortedBy { it.timesort }
                        if (soon.isEmpty()) {
                            item(key = "dl-empty") {
                                Text(
                                    "今日と明日の締切はありません",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppTheme.colors.inkSecondary,
                                    modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm),
                                )
                            }
                        }
                        items(soon.take(TODAY_DEADLINE_LIMIT), key = { "dl-${it.id}" }) { event ->
                            DeadlineRow(
                                event = event,
                                nowSec = nowSec,
                                done = false,
                                onToggleDone = { container.deadlineDoneStore.setDone(DeadlineDoneStore.keyOf(event), it) },
                                onClick = { onOpenDeadline(event) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (soon.size > TODAY_DEADLINE_LIMIT) {
                            item(key = "dl-more") {
                                MetaText(
                                    "ほかに${soon.size - TODAY_DEADLINE_LIMIT}件",
                                    modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.xs),
                                )
                            }
                        }
                    }
                }
                item(key = "sync") {
                    // 古いほうの同期時刻を出す (どちらかが古いまま新しく見せない)
                    val older = listOf(ttState, dlState).mapNotNull { (it as? UiState.Success<*>)?.loadedAtMillis }.minOrNull()
                    if (older != null) SyncFooter(UiState.Success(Unit, loadedAtMillis = older))
                }
            }
        }
    }
}

/**
 * Now Card (DESIGN.md §8.1)。左端 4px のバーに授業カラー fg。
 * 重要情報は色より先にサイズで強調する: 残り時間 = countdown、教室 = title-md、授業名 = body。
 */
@Composable
private fun NowCard(state: NowCardState, onOpenCourse: (Long, String, String?) -> Unit) {
    val c = AppTheme.colors
    val entry: TimetableEntry? = when (state) {
        is NowCardState.InClass -> state.entry
        is NowCardState.NextToday -> state.entry
        is NowCardState.NoMoreToday -> state.next?.entry
    }
    val cc = entry?.let { courseColor(it.courseCode, it.courseId, it.title) }
    val onClick = entry?.courseId?.let { id -> { onOpenCourse(id, entry.title, entry.courseCode) } }
    NowCardFrame(accent = cc, onClick = onClick) {
        when (state) {
            is NowCardState.InClass -> {
                CardLabel("授業中 · ${periodLabel(state.entry, state.start, state.end)}")
                Countdown("残り", state.minutesLeft)
                Spacer(Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { state.progress },
                    color = cc!!.fg,
                    trackColor = c.surfaceSunken,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                ClassDetails(state.entry, cc, state.start, state.end)
            }
            is NowCardState.NextToday -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardLabel("次の授業 · ${periodLabel(state.entry, state.start, state.end)}", Modifier.weight(1f, fill = false))
                    state.freeMinutes?.let {
                        Spacer(Modifier.width(Spacing.sm))
                        StatusPill("空きコマ ${durationLabel(it)}")
                    }
                }
                Countdown("あと", state.minutesUntil)
                ClassDetails(state.entry, cc!!, state.start, state.end)
            }
            is NowCardState.NoMoreToday -> {
                Text(
                    if (state.hadClassesToday) "今日の授業は終わりです" else "今日は授業がありません",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                )
                val next = state.next
                if (next != null && cc != null) {
                    Spacer(Modifier.height(Spacing.md))
                    CardLabel("${nextDayLabel(next)} · ${next.entry.period}限")
                    val range = Timetable.PERIOD_TIMES[next.entry.period]
                    ClassDetails(
                        next.entry,
                        cc,
                        next.start,
                        range?.second?.let { LocalTime.parse(it, ClockFormat) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NowCardFrame(accent: CourseColor?, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    AppCard(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.lg),
        border = BorderStroke(0.5.dp, c.hairline),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent?.fg ?: c.hairline),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(Spacing.lg)
                    .semantics(mergeDescendants = true) {},
            ) { content() }
        }
    }
}

@Composable
private fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = AppTheme.colors.inkSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 「あと 12 分」: 数字だけ countdown の大きさ、単位は title-md */
@Composable
private fun Countdown(prefix: String, minutes: Long) {
    val big = MaterialTheme.typography.displaySmall
    val small = MaterialTheme.typography.titleMedium
    val text: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = small.fontSize, fontWeight = small.fontWeight)) { append("$prefix ") }
        val h = minutes / 60
        val m = minutes % 60
        if (h > 0) {
            append("$h")
            withStyle(SpanStyle(fontSize = small.fontSize, fontWeight = small.fontWeight)) { append(" 時間 ") }
        }
        if (h == 0L || m > 0) {
            append("$m")
            withStyle(SpanStyle(fontSize = small.fontSize, fontWeight = small.fontWeight)) { append(" 分") }
        }
    }
    Text(
        text,
        style = big,
        color = AppTheme.colors.ink,
        modifier = Modifier
            .padding(top = Spacing.xs)
            .semantics { contentDescription = "$prefix${durationLabel(minutes)}" },
    )
}

@Composable
private fun ClassDetails(entry: TimetableEntry, cc: CourseColor, start: LocalTime, end: LocalTime?) {
    Spacer(Modifier.height(Spacing.md))
    Text(
        entry.title,
        style = MaterialTheme.typography.bodyLarge,
        color = AppTheme.colors.ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    entry.room?.takeIf { it.isNotBlank() }?.let { room ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
            Icon(Icons.Outlined.Place, contentDescription = "教室", tint = cc.fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(room, style = MaterialTheme.typography.titleMedium, color = cc.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    MetaText(
        start.format(ClockFormat) + (end?.let { "–" + it.format(ClockFormat) } ?: ""),
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

/** 2コマ続きの授業は開始・終了時刻から「3–4限」にする */
private fun periodLabel(entry: TimetableEntry, start: LocalTime, end: LocalTime): String {
    val first = Timetable.PERIOD_TIMES.entries.firstOrNull { it.value.first == start.format(ClockFormat) }?.key ?: entry.period
    val last = Timetable.PERIOD_TIMES.entries.firstOrNull { it.value.second == end.format(ClockFormat) }?.key ?: entry.period
    return if (last > first) "$first–${last}限" else "${first}限"
}

private fun nextDayLabel(next: TimetableFocus.Upcoming): String = when (next.daysAhead) {
    1 -> "明日"
    else -> WeekdayNames[(LocalDateTime.now().dayOfWeek.value - 1 + next.daysAhead) % 7] + "曜"
}

@Composable
private fun NowCardMessage(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    NowCardFrame(accent = null, onClick = null) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.ink)
        Spacer(Modifier.height(Spacing.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.inkSecondary)
        action?.let { (label, onClick) -> TertiaryButton(label, onClick = onClick) }
    }
}

@Composable
private fun NowCardSkeleton() {
    NowCardFrame(accent = null, onClick = null) {
        SkeletonBlock(width = 96.dp, height = 13.dp)
        Spacer(Modifier.height(Spacing.md))
        SkeletonBlock(width = 160.dp, height = 34.dp)
        Spacer(Modifier.height(Spacing.md))
        SkeletonBlock(width = null, height = 16.dp)
    }
}

/**
 * タイムラインの1行 (Structured 風)。左に開始・終了時刻、授業カラーの縦バー、授業名と教室。
 * 授業中の行には現在時刻の位置に accent の線を引く (1分ごとに位置更新、アニメーションなし)。
 */
@Composable
private fun TimelineRow(block: DayBlock, time: LocalTime, onClick: (() -> Unit)?) {
    val c = AppTheme.colors
    val cc = courseColor(block.entry.courseCode, block.entry.courseId, block.entry.title)
    val inClass = time >= block.start && time < block.end
    val past = time >= block.end
    val fraction = if (inClass) {
        java.time.Duration.between(block.start, time).seconds.toFloat() /
            java.time.Duration.between(block.start, block.end).seconds.coerceAtLeast(1)
    } else {
        null
    }
    val accent = c.accent
    val periods = if (block.lastPeriod > block.firstPeriod) "${block.firstPeriod}–${block.lastPeriod}限" else "${block.firstPeriod}限"
    ListRow(
        onClick = onClick,
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .drawWithContent {
                drawContent()
                if (fraction != null) {
                    val y = size.height * fraction
                    val x0 = ScreenGutter.toPx() + 44.dp.toPx()
                    drawLine(accent, Offset(x0, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                    drawCircle(accent, radius = 4.dp.toPx(), center = Offset(x0, y))
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    "$periods ${block.start.format(ClockFormat)}から${block.end.format(ClockFormat)}",
                    block.entry.title,
                    block.entry.room?.let { "教室 $it" },
                    if (inClass) "授業中" else if (past) "終了" else null,
                ).joinToString("、")
            },
    ) {
        Column(Modifier.width(44.dp)) {
            Text(block.start.format(ClockFormat), style = MaterialTheme.typography.labelMedium, color = if (past) c.inkSecondary else c.ink)
            Text(block.end.format(ClockFormat), style = MaterialTheme.typography.labelMedium, color = c.inkSecondary)
        }
        Box(
            Modifier
                .padding(horizontal = Spacing.sm)
                .width(4.dp)
                .height(48.dp)
                .background(if (past) c.hairline else cc.fg, RoundedCornerShape(Radius.xs)),
        )
        Column(Modifier.weight(1f)) {
            MetaText(periods)
            Text(
                block.entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (past) c.inkSecondary else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            block.entry.room?.takeIf { it.isNotBlank() }?.let { MetaText(it) }
        }
    }
}

/** 授業と授業のあいだの現在時刻ライン */
@Composable
private fun NowMarker(time: LocalTime) {
    val accent = AppTheme.colors.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = Spacing.xs)
            .semantics { contentDescription = "現在 ${time.format(ClockFormat)}" },
    ) {
        Text(time.format(ClockFormat), style = MaterialTheme.typography.labelMedium, color = accent, modifier = Modifier.width(44.dp))
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Box(Modifier.weight(1f).height(2.dp).background(accent))
    }
}
