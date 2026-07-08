package dev.rits.bettermoodle

import dev.rits.bettermoodle.ui.formatMoodleDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class FormatsTest {

    private val zoneId = ZoneId.of("Asia/Tokyo")
    private val now = LocalDateTime.of(2026, 7, 8, 12, 0)
        .atZone(zoneId)
        .toInstant()

    @Test
    fun `omits year when target date is in current year`() {
        val epochSeconds = LocalDateTime.of(2026, 7, 9, 8, 30)
            .atZone(zoneId)
            .toEpochSecond()

        val formatted = formatMoodleDateTime(
            epochSeconds = epochSeconds,
            suffix = "まで",
            now = now,
            zoneId = zoneId,
        )

        assertEquals("7月9日(木) 08:30まで", formatted)
    }

    @Test
    fun `includes year when target date is in different year`() {
        val epochSeconds = LocalDateTime.of(2027, 1, 12, 18, 5)
            .atZone(zoneId)
            .toEpochSecond()

        val formatted = formatMoodleDateTime(
            epochSeconds = epochSeconds,
            now = now,
            zoneId = zoneId,
        )

        assertEquals("2027年1月12日(火) 18:05", formatted)
    }

    @Test
    fun `keeps unset date label`() {
        assertEquals(
            "未設定",
            formatMoodleDateTime(epochSeconds = 0L, now = Instant.EPOCH, zoneId = zoneId),
        )
    }
}
