package dev.rits.bettermoodle.data

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 時間割画面の最上部に出す「いま知るべき授業」 */
sealed interface TimetableFocus {
    val entry: TimetableEntry

    /** 授業中。[minutesLeft] はこの時限の終了までの分数 */
    data class InClass(override val entry: TimetableEntry, val minutesLeft: Long) : TimetableFocus

    /** 次の授業。[daysAhead] = 0 なら今日 */
    data class Upcoming(override val entry: TimetableEntry, val daysAhead: Int, val start: LocalTime) : TimetableFocus
}

private val PeriodTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

/** 時限の開始・終了時刻。未知の時限は null */
fun periodRange(period: Int): Pair<LocalTime, LocalTime>? {
    val (start, end) = Timetable.PERIOD_TIMES[period] ?: return null
    return LocalTime.parse(start, PeriodTimeFormat) to LocalTime.parse(end, PeriodTimeFormat)
}

/**
 * [now] 時点で授業中の授業、なければ1週間以内の次の授業。どちらもなければ null。
 * 同じコマに複数科目がある場合は時間割の並び順で先頭を選ぶ。
 */
fun timetableFocus(entries: List<TimetableEntry>, now: LocalDateTime): TimetableFocus? {
    val today = now.dayOfWeek.value - 1 // 0=月
    val time = now.toLocalTime()
    val todays = entries.filter { it.dayIndex == today }.sortedBy { it.period }

    for (entry in todays) {
        val (start, end) = periodRange(entry.period) ?: continue
        if (time >= start && time < end) {
            return TimetableFocus.InClass(entry, Duration.between(time, end).toMinutes().coerceAtLeast(1))
        }
    }
    for (offset in 0..6) {
        val day = (today + offset) % 7
        val next = entries
            .filter { it.dayIndex == day }
            .mapNotNull { entry -> periodRange(entry.period)?.let { entry to it.first } }
            .filter { (_, start) -> offset > 0 || start > time }
            .minByOrNull { (entry, _) -> entry.period }
        if (next != null) return TimetableFocus.Upcoming(next.first, offset, next.second)
    }
    return null
}

/**
 * いまの時刻線を引く位置。[period] の行の上端 0f〜下端 1f の割合で返す。その行に線がなければ null。
 * 休み時間は次の時限の上端 (0f) に置く。1限の開始前・最終限の終了後は引かない。
 */
fun nowLineFraction(period: Int, time: LocalTime): Float? {
    val (start, end) = periodRange(period) ?: return null
    if (time >= start && time < end) {
        val total = Duration.between(start, end).seconds.toFloat()
        return Duration.between(start, time).seconds / total
    }
    if (time < start && period > 1) {
        val previousEnd = periodRange(period - 1)?.second ?: return null
        if (time >= previousEnd) return 0f
    }
    return null
}

/** 「今日」画面の Now Card の状態 (DESIGN.md §8.1) */
sealed interface NowCardState {
    /** 授業中。[progress] は 0f〜1f */
    data class InClass(
        val entry: TimetableEntry,
        val start: LocalTime,
        val end: LocalTime,
        val minutesLeft: Long,
        val progress: Float,
    ) : NowCardState

    /** 今日の次の授業。[freeMinutes] は授業のあいだの空き時間 (90分以上のときだけ) */
    data class NextToday(
        val entry: TimetableEntry,
        val start: LocalTime,
        val end: LocalTime,
        val minutesUntil: Long,
        val freeMinutes: Long?,
    ) : NowCardState

    /** 今日の授業は終わった (または今日は授業がない)。[next] は次の授業日の最初の授業 */
    data class NoMoreToday(val hadClassesToday: Boolean, val next: TimetableFocus.Upcoming?) : NowCardState
}

/** 空きコマとして知らせる最短の空き時間 */
const val FREE_PERIOD_MINUTES = 90L

fun nowCardState(entries: List<TimetableEntry>, now: LocalDateTime): NowCardState {
    val time = now.toLocalTime()
    // 2コマ続きの授業は1ブロック (開始〜終了) として扱う。コマの間の休み時間も授業中とみなす
    val blocks = dayBlocks(entries, now.dayOfWeek.value - 1).sortedBy { it.start }

    blocks.firstOrNull { time >= it.start && time < it.end }?.let { block ->
        val total = Duration.between(block.start, block.end).seconds.toFloat().coerceAtLeast(1f)
        return NowCardState.InClass(
            entry = block.entry,
            start = block.start,
            end = block.end,
            minutesLeft = ((Duration.between(time, block.end).seconds + 59) / 60).coerceAtLeast(1),
            progress = (Duration.between(block.start, time).seconds / total).coerceIn(0f, 1f),
        )
    }
    blocks.firstOrNull { it.start > time }?.let { block ->
        val minutesUntil = (Duration.between(time, block.start).seconds + 59) / 60
        val previousEnd = blocks.lastOrNull { it.end <= time }?.end
        val free = previousEnd?.let { Duration.between(it, block.start).toMinutes() }?.takeIf { it >= FREE_PERIOD_MINUTES }
        return NowCardState.NextToday(block.entry, block.start, block.end, minutesUntil, free)
    }
    val tomorrowOn = now.toLocalDate().plusDays(1).atStartOfDay()
    val next = timetableFocus(entries, tomorrowOn) as? TimetableFocus.Upcoming
    return NowCardState.NoMoreToday(
        hadClassesToday = blocks.isNotEmpty(),
        next = next?.copy(daysAhead = next.daysAhead + 1),
    )
}

/**
 * 今日のタイムラインの1ブロック。連続する同じ授業 (2コマ続き) は1ブロックにまとめる。
 * 同じコマに複数の授業があるときは、授業ごとに別のブロックにする (時限は同じ)。
 */
data class DayBlock(
    val entry: TimetableEntry,
    val firstPeriod: Int,
    val lastPeriod: Int,
    val start: LocalTime,
    val end: LocalTime,
)

fun dayBlocks(entries: List<TimetableEntry>, dayIndex: Int): List<DayBlock> {
    val cells = entries.filter { it.dayIndex == dayIndex }.groupBy { it.period }
    val result = mutableListOf<DayBlock>()
    var period = 1
    val last = cells.keys.maxOrNull() ?: return emptyList()
    while (period <= last) {
        val here = cells[period].orEmpty()
        val range = periodRange(period)
        if (here.isEmpty() || range == null) {
            period++
            continue
        }
        if (here.size > 1) {
            // 複数授業のコマは連結しない (timetableCellsConnected と同じ)
            here.forEach { result += DayBlock(it, period, period, range.first, range.second) }
            period++
            continue
        }
        var end = period
        while (timetableCellsConnected(cells[end].orEmpty(), cells[end + 1].orEmpty()) && periodRange(end + 1) != null) end++
        result += DayBlock(here.first(), period, end, range.first, periodRange(end)!!.second)
        period = end + 1
    }
    return result
}
