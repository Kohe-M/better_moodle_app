package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.timetableCellsConnected
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
