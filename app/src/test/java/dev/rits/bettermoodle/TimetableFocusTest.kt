package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.TimetableFocus
import dev.rits.bettermoodle.data.nowLineFraction
import dev.rits.bettermoodle.data.timetableFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class TimetableFocusTest {
    private fun entry(day: Int, period: Int, title: String = "d${day}p$period") =
        TimetableEntry(dayIndex = day, period = period, title = title, courseCode = null, courseUrl = null)

    // 2026-09-28 は月曜日
    private fun monday(hour: Int, minute: Int) = LocalDateTime.of(2026, 9, 28, hour, minute)

    @Test
    fun inClassReportsMinutesUntilPeriodEnd() {
        val focus = timetableFocus(listOf(entry(0, 2)), monday(12, 0))
        assertTrue(focus is TimetableFocus.InClass)
        assertEquals(20L, (focus as TimetableFocus.InClass).minutesLeft) // 2限は 12:20 まで
    }

    @Test
    fun upcomingTodayPicksEarliestRemainingPeriod() {
        val focus = timetableFocus(listOf(entry(0, 1), entry(0, 4), entry(0, 3)), monday(12, 30))
        focus as TimetableFocus.Upcoming
        assertEquals(3, focus.entry.period)
        assertEquals(0, focus.daysAhead)
        assertEquals(LocalTime.of(13, 10), focus.start)
    }

    @Test
    fun afterLastClassLooksAtFollowingDays() {
        val focus = timetableFocus(listOf(entry(0, 1), entry(2, 2)), monday(19, 0))
        focus as TimetableFocus.Upcoming
        assertEquals(2, focus.daysAhead)
        assertEquals(2, focus.entry.period)
    }

    @Test
    fun weekWrapsAroundToEarlierWeekday() {
        // 金曜の夜 → 次は翌週月曜
        val friday = LocalDateTime.of(2026, 10, 2, 21, 0)
        val focus = timetableFocus(listOf(entry(0, 1)), friday)
        focus as TimetableFocus.Upcoming
        assertEquals(3, focus.daysAhead)
    }

    @Test
    fun noEntriesMeansNoFocus() {
        assertNull(timetableFocus(emptyList(), monday(10, 0)))
    }

    @Test
    fun nowLineSitsInsideCurrentPeriod() {
        val fraction = nowLineFraction(1, LocalTime.of(9, 0))
        assertEquals(0f, fraction!!, 0.001f)
        val middle = nowLineFraction(1, LocalTime.of(9, 47, 30))!!
        assertEquals(0.5f, middle, 0.01f)
    }

    @Test
    fun nowLineDuringBreakSitsAtTopOfNextPeriod() {
        assertEquals(0f, nowLineFraction(2, LocalTime.of(10, 40))!!, 0.001f)
        assertNull(nowLineFraction(1, LocalTime.of(10, 40)))
    }

    @Test
    fun noNowLineOutsideClassHours() {
        assertNull(nowLineFraction(1, LocalTime.of(8, 0)))
        assertNull(nowLineFraction(7, LocalTime.of(22, 30)))
    }
}
