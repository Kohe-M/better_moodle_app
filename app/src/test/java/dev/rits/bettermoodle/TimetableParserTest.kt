package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Course
import dev.rits.bettermoodle.data.MoodleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableParserTest {

    // 拡張機能が対象とする rutime_table ブロックのDOM構造を模したHTML
    private val html = """
        <div class="card-body">
          <table class="timetable">
            <tr><th></th><th>月</th><th>火</th><th>水</th><th>木</th><th>金</th></tr>
            <tr>
              <td class="time">1</td>
              <td><div class="subject"><a href="https://lms.ritsumei.ac.jp/course/view.php?id=100">10001:プログラミング入門 §A</a><span class="room">月1: A101</span></div></td>
              <td></td>
              <td><div class="subject"><a href="https://lms.ritsumei.ac.jp/course/view.php?id=200">10002:線形代数</a></div></td>
              <td></td>
              <td></td>
            </tr>
            <tr>
              <td class="time">2</td>
              <td></td>
              <td><div class="subject"><a href="https://lms.ritsumei.ac.jp/course/view.php?id=300">10003:英語リーディング</a></div></td>
              <td></td>
              <td></td>
              <td></td>
            </tr>
          </table>
        </div>
    """.trimIndent()

    @Test
    fun `時間割HTMLを解析できる`() {
        val timetable = MoodleRepository.parseTimetableHtml(html)
        assertEquals(3, timetable.entries.size)

        val mon1 = timetable.entries.first { it.dayIndex == 0 && it.period == 1 }
        assertEquals("プログラミング入門", mon1.title)
        assertEquals("10001", mon1.courseCode)
        assertEquals("https://lms.ritsumei.ac.jp/course/view.php?id=100", mon1.courseUrl)
        assertEquals("A101", mon1.room)

        val tue2 = timetable.entries.first { it.dayIndex == 1 && it.period == 2 }
        assertEquals("英語リーディング", tue2.title)

        assertEquals(listOf("月", "火", "水", "木", "金"), timetable.dayLabels)
    }

    @Test
    fun `空HTMLは空の時間割を返す`() {
        val timetable = MoodleRepository.parseTimetableHtml("<div>no table</div>")
        assertTrue(timetable.entries.isEmpty())
    }

    @Test
    fun `コース名から授業コードを抽出できる`() {
        assertEquals("12345", Course(fullname = "12345:確率統計 §B").courseCode)
        assertEquals("確率統計", Course(fullname = "12345:確率統計 §B").displayName)
        assertNull(Course(fullname = "コードなしコース").courseCode)
    }

    @Test
    fun `教室文字列から現在の曜日時限だけを抽出できる`() {
        val rooms = "月1: A101 火2: B202 月3: C303"
        assertEquals("A101", MoodleRepository.extractRoomForSlot(rooms, dayIdx = 0, period = 1))
        assertEquals("B202", MoodleRepository.extractRoomForSlot(rooms, dayIdx = 1, period = 2))
        assertNull(MoodleRepository.extractRoomForSlot(rooms, dayIdx = 2, period = 1))
    }
}
