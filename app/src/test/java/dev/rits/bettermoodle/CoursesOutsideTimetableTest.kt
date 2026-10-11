package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Course
import dev.rits.bettermoodle.data.Timetable
import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.coursesOutsideTimetable
import org.junit.Assert.assertEquals
import org.junit.Test

class CoursesOutsideTimetableTest {

    @Test
    fun `courses linked from timetable cells are excluded by id`() {
        val courses = listOf(
            Course(id = 10, fullname = "11111:情報理論 §A"),
            Course(id = 20, fullname = "22222:卒業研究"),
            Course(id = 30, fullname = "33333:データエンジニアリング基礎"),
        )
        val timetable = timetable(entry(courseCode = "11111", courseUrl = "https://lms.ritsumei.ac.jp/course/view.php?id=10"))

        assertEquals(listOf(20L, 30L), coursesOutsideTimetable(courses, timetable).map { it.id })
    }

    @Test
    fun `cells without course link fall back to course code`() {
        val courses = listOf(
            Course(id = 10, fullname = "11111:情報理論"),
            Course(id = 20, fullname = "22222:卒業研究"),
        )
        val timetable = timetable(entry(courseCode = "11111", courseUrl = null))

        assertEquals(listOf(20L), coursesOutsideTimetable(courses, timetable).map { it.id })
    }

    @Test
    fun `code match is ignored when the cell has a course id`() {
        // 同じ授業コードの別コース (クラス分け等) は、IDが分かっているコマに吸収させない
        val courses = listOf(
            Course(id = 10, fullname = "11111:英語 §A"),
            Course(id = 11, fullname = "11111:英語 §B"),
        )
        val timetable = timetable(entry(courseCode = "11111", courseUrl = "https://lms.ritsumei.ac.jp/course/view.php?id=10"))

        assertEquals(listOf(11L), coursesOutsideTimetable(courses, timetable).map { it.id })
    }

    @Test
    fun `hidden courses and courses without code are excluded`() {
        val courses = listOf(
            Course(id = 10, fullname = "44444:非表示コース", hidden = true),
            Course(id = 20, fullname = "学部からのお知らせ"),
            Course(id = 30, fullname = "22222:卒業研究"),
        )

        assertEquals(listOf(30L), coursesOutsideTimetable(courses, Timetable.EMPTY).map { it.id })
    }

    private fun timetable(vararg entries: TimetableEntry) =
        Timetable(entries.toList(), listOf("月", "火", "水", "木", "金"))

    private fun entry(courseCode: String?, courseUrl: String?) = TimetableEntry(
        dayIndex = 0,
        period = 1,
        title = "科目",
        courseCode = courseCode,
        courseUrl = courseUrl,
    )
}
