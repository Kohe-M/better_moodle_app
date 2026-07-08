package dev.rits.bettermoodle.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MoodleDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日(E) HH:mm", Locale.JAPANESE)

private val MoodleDateTimeWithYearFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日(E) HH:mm", Locale.JAPANESE)

fun formatMoodleDateTime(epochSeconds: Long, suffix: String = ""): String {
    return formatMoodleDateTime(
        epochSeconds = epochSeconds,
        suffix = suffix,
        now = Instant.now(),
        zoneId = ZoneId.systemDefault(),
    )
}

internal fun formatMoodleDateTime(
    epochSeconds: Long,
    suffix: String = "",
    now: Instant,
    zoneId: ZoneId,
): String {
    if (epochSeconds <= 0L) return "未設定"
    val dateTime = Instant.ofEpochSecond(epochSeconds).atZone(zoneId)
    val currentYear = now.atZone(zoneId).year
    val formatter = if (dateTime.year == currentYear) {
        MoodleDateTimeFormatter
    } else {
        MoodleDateTimeWithYearFormatter
    }
    return dateTime.format(formatter) + suffix
}
