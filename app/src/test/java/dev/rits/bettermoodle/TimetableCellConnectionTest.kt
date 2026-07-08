package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.timetableCellsConnected
import dev.rits.bettermoodle.data.timetableRunStartPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableCellConnectionTest {

    @Test
    fun `single entries with same course code are connected`() {
        assertTrue(
            timetableCellsConnected(
                listOf(entry(period = 3, courseCode = "12345")),
                listOf(entry(period = 4, courseCode = "12345")),
            ),
        )
    }

    @Test
    fun `different course codes are not connected`() {
        assertFalse(
            timetableCellsConnected(
                listOf(entry(period = 3, courseCode = "12345")),
                listOf(entry(period = 4, courseCode = "54321")),
            ),
        )
    }

    @Test
    fun `cells with multiple entries are not connected`() {
        assertFalse(
            timetableCellsConnected(
                listOf(
                    entry(period = 3, courseCode = "12345"),
                    entry(period = 3, courseCode = "12345", title = "Other"),
                ),
                listOf(entry(period = 4, courseCode = "12345")),
            ),
        )
    }

    @Test
    fun `single entries with matching course url are connected when both course codes are null`() {
        val url = "https://lms.ritsumei.ac.jp/course/view.php?id=100"

        assertTrue(
            timetableCellsConnected(
                listOf(entry(period = 3, courseCode = null, courseUrl = url)),
                listOf(entry(period = 4, courseCode = null, courseUrl = url)),
            ),
        )
    }

    @Test
    fun `empty cells are not connected`() {
        assertFalse(
            timetableCellsConnected(
                emptyList(),
                listOf(entry(period = 4, courseCode = "12345")),
            ),
        )
    }

    @Test
    fun `run start period returns own period for single cell`() {
        val byCell = byCell(
            entry(period = 2, courseCode = "12345"),
        )

        assertEquals(2, timetableRunStartPeriod(byCell, dayIdx = 0, period = 2))
    }

    @Test
    fun `run start period returns first period for second cell in two cell run`() {
        val byCell = byCell(
            entry(period = 1, courseCode = "12345"),
            entry(period = 2, courseCode = "12345"),
        )

        assertEquals(1, timetableRunStartPeriod(byCell, dayIdx = 0, period = 2))
    }

    @Test
    fun `run start period returns first period for third cell in three cell run`() {
        val byCell = byCell(
            entry(period = 1, courseCode = "12345"),
            entry(period = 2, courseCode = "12345"),
            entry(period = 3, courseCode = "12345"),
        )

        assertEquals(1, timetableRunStartPeriod(byCell, dayIdx = 0, period = 3))
    }

    private fun byCell(vararg entries: TimetableEntry) = entries.toList().groupBy {
        it.dayIndex to it.period
    }

    private fun entry(
        period: Int,
        courseCode: String?,
        title: String = "Programming",
        courseUrl: String? = "https://lms.ritsumei.ac.jp/course/view.php?id=100",
    ) = TimetableEntry(
        dayIndex = 0,
        period = period,
        title = title,
        courseCode = courseCode,
        courseUrl = courseUrl,
    )
}
