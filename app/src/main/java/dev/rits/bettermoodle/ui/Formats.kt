package dev.rits.bettermoodle.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 文言の書式 (DESIGN.md §10)。
 * - 時刻は 24 時間制 `13:00` / `9:41`、日付は `9/28(月)`、年が違うときだけ `2027/1/15(金)`
 * - 相対時間は `あと12分` `残り40分` `明日 23:59` `2日前に期限切れ`
 */

private val DateShort: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE)
private val DateWithYear: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d(E)", Locale.JAPANESE)
private val TitleDate: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPANESE)
val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.JAPANESE)

fun formatMoodleDateTime(epochSeconds: Long, suffix: String = ""): String =
    formatMoodleDateTime(epochSeconds, suffix, Instant.now(), ZoneId.systemDefault())

internal fun formatMoodleDateTime(
    epochSeconds: Long,
    suffix: String = "",
    now: Instant,
    zoneId: ZoneId,
): String {
    if (epochSeconds <= 0L) return "未設定"
    val dateTime = Instant.ofEpochSecond(epochSeconds).atZone(zoneId)
    return "${formatDate(dateTime.toLocalDate(), now.atZone(zoneId).toLocalDate())} ${dateTime.format(ClockFormat)}$suffix"
}

/** `9/28(月)`。[today] と年が違うときだけ `2027/1/15(金)` */
internal fun formatDate(date: LocalDate, today: LocalDate): String =
    date.format(if (date.year == today.year) DateShort else DateWithYear)

/** 画面の大タイトル用 `9月28日(月)` */
fun formatTitleDate(date: LocalDate): String = date.format(TitleDate)

fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(ClockFormat)

/** 締切の緊急度。色は必ず文言 ([deadlineLabel]) と併用する */
enum class Urgency { Overdue, Soon, Calm }

fun urgencyOf(nowEpochSec: Long, deadlineEpochSec: Long): Urgency = when {
    deadlineEpochSec < nowEpochSec -> Urgency.Overdue
    deadlineEpochSec - nowEpochSec < 24 * 3600 -> Urgency.Soon
    else -> Urgency.Calm
}

/** 残り時間の短い表現。期限後は null (呼び出し側で期限の日時を出す) */
internal fun remainingLabel(nowEpochSec: Long, deadlineEpochSec: Long): String {
    val diff = deadlineEpochSec - nowEpochSec
    return when {
        diff < 0 -> "期限切れ"
        diff < 3600 -> "あと${maxOf(1, diff / 60)}分"
        diff < 24 * 3600 -> "あと${diff / 3600}時間"
        else -> "あと${diff / (24 * 3600)}日"
    }
}

/**
 * 締切一覧の右端の文言。
 * 期限切れ `3時間前に期限切れ` / 24時間以内 `あと3時間` / 明日 `明日 23:59` / それ以降 `10/14(火) 23:59`
 */
internal fun deadlineLabel(nowEpochSec: Long, deadlineEpochSec: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val diff = deadlineEpochSec - nowEpochSec
    if (diff < 0) {
        val ago = -diff
        return when {
            ago < 3600 -> "${maxOf(1, ago / 60)}分前に期限切れ"
            ago < 24 * 3600 -> "${ago / 3600}時間前に期限切れ"
            else -> "${ago / (24 * 3600)}日前に期限切れ"
        }
    }
    if (diff < 24 * 3600) return remainingLabel(nowEpochSec, deadlineEpochSec)
    val now = Instant.ofEpochSecond(nowEpochSec).atZone(zoneId)
    val at = Instant.ofEpochSecond(deadlineEpochSec).atZone(zoneId)
    val time = at.format(ClockFormat)
    return if (at.toLocalDate() == now.toLocalDate().plusDays(1)) {
        "明日 $time"
    } else {
        "${formatDate(at.toLocalDate(), now.toLocalDate())} $time"
    }
}

/** 締切一覧のセクション (DESIGN.md §8.3) */
enum class DeadlineSection(val label: String) {
    Overdue("期限切れ"),
    Today("今日"),
    Tomorrow("明日"),
    ThisWeek("今週"),
    Later("来週以降"),
}

internal fun deadlineSectionOf(now: ZonedDateTime, deadlineEpochSec: Long): DeadlineSection {
    if (deadlineEpochSec < now.toEpochSecond()) return DeadlineSection.Overdue
    val date = Instant.ofEpochSecond(deadlineEpochSec).atZone(now.zone).toLocalDate()
    val today = now.toLocalDate()
    return when {
        date == today -> DeadlineSection.Today
        date == today.plusDays(1) -> DeadlineSection.Tomorrow
        date.isBefore(today.plusDays(7)) -> DeadlineSection.ThisWeek
        else -> DeadlineSection.Later
    }
}

/** 分数を `12分` `1時間20分` にする */
internal fun durationLabel(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    return when {
        m < 60 -> "${m}分"
        m % 60 == 0L -> "${m / 60}時間"
        else -> "${m / 60}時間${m % 60}分"
    }
}
