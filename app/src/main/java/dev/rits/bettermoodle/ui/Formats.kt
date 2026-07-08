package dev.rits.bettermoodle.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MoodleDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日(E) HH:mm", Locale.JAPANESE)

fun formatMoodleDateTime(epochSeconds: Long, suffix: String = ""): String {
    if (epochSeconds <= 0L) return "未設定"
    return Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(MoodleDateTimeFormatter) + suffix
}
