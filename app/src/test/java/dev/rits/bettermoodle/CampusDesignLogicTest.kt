package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Course
import dev.rits.bettermoodle.data.CourseColors
import dev.rits.bettermoodle.data.NowCardState
import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.currentSectionIndex
import dev.rits.bettermoodle.data.dayBlocks
import dev.rits.bettermoodle.data.nowCardState
import dev.rits.bettermoodle.ui.DeadlineSection
import dev.rits.bettermoodle.ui.courseInitial
import dev.rits.bettermoodle.ui.deadlineLabel
import dev.rits.bettermoodle.ui.deadlineSectionOf
import dev.rits.bettermoodle.ui.durationLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** DESIGN.md の振る舞い (授業カラー・締切の文言とセクション・Now Card) の固定 */
class CampusDesignLogicTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun entry(day: Int, period: Int, code: String? = null, title: String = "d${day}p$period") =
        TimetableEntry(dayIndex = day, period = period, title = title, courseCode = code, courseUrl = null)

    // 2026-09-28 は月曜日
    private fun monday(hour: Int, minute: Int) = LocalDateTime.of(2026, 9, 28, hour, minute)
    private fun epoch(dt: LocalDateTime) = dt.atZone(zone).toEpochSecond()

    // ---- 授業カラー ----

    @Test
    fun courseKeyPrefersCourseId() {
        assertEquals("i:99", CourseColors.key("12345", 99L, "名前"))
        assertEquals("i:99", CourseColors.key(null, 99L, "名前"))
        assertEquals("c:12345", CourseColors.key("12345", null, "名前"))
        assertEquals("t:名前", CourseColors.key(null, null, " 名前 "))
    }

    @Test
    fun courseKeyIsSameFromEveryEntryPoint() {
        // 時間割 (コードあり・なし)、コース一覧、Moodleのリンクから開いたコース詳細 (コードなし) で同じキー
        val url = "https://lms.ritsumei.ac.jp/course/view.php?id=99"
        val withCode = TimetableEntry(0, 1, "データモデル論", courseCode = "12345", courseUrl = url)
        val withoutCode = TimetableEntry(0, 2, "データモデル論", courseCode = null, courseUrl = url)
        val course = Course(id = 99L, fullname = "12345:データモデル論 §A")
        val keys = setOf(
            CourseColors.key(withCode),
            CourseColors.key(withoutCode),
            CourseColors.key(course),
            CourseColors.key(null, 99L, "コース"),
        )
        assertEquals(setOf("i:99"), keys)
    }

    @Test
    fun adjacentCellsNeverShareAColor() {
        // ハッシュが衝突しやすいよう多数の授業を隣接させる
        val entries = (0..4).flatMap { day -> (1..5).map { p -> entry(day, p, code = "%05d".format(day * 10 + p)) } }
        val colors = CourseColors.assign(entries)
        for (e in entries) {
            val mine = colors.getValue(CourseColors.key(e))
            val right = entries.firstOrNull { it.dayIndex == e.dayIndex + 1 && it.period == e.period }
            val below = entries.firstOrNull { it.dayIndex == e.dayIndex && it.period == e.period + 1 }
            right?.let { assertNotEquals("右隣と同色: $e", mine, colors.getValue(CourseColors.key(it))) }
            below?.let { assertNotEquals("下隣と同色: $e", mine, colors.getValue(CourseColors.key(it))) }
        }
    }

    @Test
    fun consecutivePeriodsOfSameCourseKeepOneColor() {
        val colors = CourseColors.assign(listOf(entry(0, 1, "11111"), entry(0, 2, "11111")))
        assertEquals(1, colors.size)
        assertEquals(CourseColors.hashIndex("c:11111"), colors["c:11111"])
    }

    @Test
    fun userOverrideIsNotShifted() {
        val a = entry(0, 1, "11111")
        val b = entry(0, 2, "22222")
        val colors = CourseColors.assign(listOf(a, b), overrides = mapOf("c:11111" to 3, "c:22222" to 3))
        assertEquals(3, colors["c:11111"])
        assertEquals(3, colors["c:22222"])
    }

    @Test
    fun initialSkipsSymbols() {
        assertEquals("情", courseInitial("「情報理工学特別講義Ⅱ（データサイエンス応用）」"))
        assertEquals("・", courseInitial("（）"))
    }

    // ---- 締切 ----

    @Test
    fun deadlineLabelsFollowMicrocopy() {
        val now = epoch(monday(12, 0))
        assertEquals("あと12分", deadlineLabel(now, now + 12 * 60, zone))
        assertEquals("あと3時間", deadlineLabel(now, now + 3 * 3600 + 5, zone))
        assertEquals("明日 23:59", deadlineLabel(now, epoch(LocalDateTime.of(2026, 9, 29, 23, 59)), zone))
        assertEquals("10/14(水) 9:00", deadlineLabel(now, epoch(LocalDateTime.of(2026, 10, 14, 9, 0)), zone))
        assertEquals("2027/1/15(金) 23:59", deadlineLabel(now, epoch(LocalDateTime.of(2027, 1, 15, 23, 59)), zone))
        assertEquals("3時間前に期限切れ", deadlineLabel(now, now - 3 * 3600, zone))
        assertEquals("2日前に期限切れ", deadlineLabel(now, now - 2 * 24 * 3600, zone))
    }

    @Test
    fun deadlineSections() {
        val now = monday(12, 0).atZone(zone)
        assertEquals(DeadlineSection.Overdue, deadlineSectionOf(now, epoch(monday(11, 0))))
        assertEquals(DeadlineSection.Today, deadlineSectionOf(now, epoch(monday(23, 59))))
        assertEquals(DeadlineSection.Tomorrow, deadlineSectionOf(now, epoch(monday(9, 0).plusDays(1))))
        assertEquals(DeadlineSection.ThisWeek, deadlineSectionOf(now, epoch(monday(9, 0).plusDays(6))))
        assertEquals(DeadlineSection.Later, deadlineSectionOf(now, epoch(monday(9, 0).plusDays(7))))
    }

    @Test
    fun durationLabels() {
        assertEquals("12分", durationLabel(12))
        assertEquals("2時間", durationLabel(120))
        assertEquals("1時間20分", durationLabel(80))
    }

    // ---- Now Card ----

    @Test
    fun nowCardInClassMergesDoublePeriod() {
        val card = nowCardState(listOf(entry(0, 3, "33333"), entry(0, 4, "33333")), monday(15, 0))
        card as NowCardState.InClass
        assertEquals(LocalTime.of(13, 10), card.start)
        assertEquals(LocalTime.of(16, 30), card.end)
        assertEquals(90L, card.minutesLeft)
        assertTrue(card.progress in 0.4f..0.6f)
    }

    @Test
    fun nowCardKeepsDoublePeriodInClassDuringBreak() {
        // 3限 (〜14:45) と 4限 (14:55〜) の間の休み時間も同じ授業の途中
        val card = nowCardState(listOf(entry(0, 3, "33333"), entry(0, 4, "33333")), monday(14, 50))
        card as NowCardState.InClass
        assertEquals(LocalTime.of(13, 10), card.start)
        assertEquals(LocalTime.of(16, 30), card.end)
        assertEquals(100L, card.minutesLeft)
    }

    @Test
    fun nowCardShowsFreePeriodOfNinetyMinutesOrMore() {
        // 1限 (〜10:35) のあと 4限 (14:55〜) まで空き
        val card = nowCardState(listOf(entry(0, 1), entry(0, 4)), monday(11, 0))
        card as NowCardState.NextToday
        assertEquals(4, card.entry.period)
        assertEquals(235L, card.minutesUntil)
        assertEquals(260L, card.freeMinutes)
    }

    @Test
    fun nowCardHasNoFreePeriodBeforeFirstClass() {
        val card = nowCardState(listOf(entry(0, 4)), monday(8, 0))
        card as NowCardState.NextToday
        assertNull(card.freeMinutes)
    }

    @Test
    fun nowCardAfterLastClassPointsToNextDay() {
        val card = nowCardState(listOf(entry(0, 1), entry(1, 2)), monday(19, 0))
        card as NowCardState.NoMoreToday
        assertTrue(card.hadClassesToday)
        assertEquals(1, card.next?.daysAhead)
        assertEquals(2, card.next?.entry?.period)
    }

    @Test
    fun dayBlocksMergeConsecutivePeriods() {
        val blocks = dayBlocks(listOf(entry(0, 1, "1"), entry(0, 3, "33333"), entry(0, 4, "33333"), entry(1, 2)), 0)
        assertEquals(2, blocks.size)
        assertEquals(3, blocks[1].firstPeriod)
        assertEquals(4, blocks[1].lastPeriod)
        assertEquals(LocalTime.of(16, 30), blocks[1].end)
    }

    @Test
    fun dayBlocksKeepEveryCourseOfSharedPeriod() {
        val a = entry(0, 2, "11111", title = "A")
        val b = entry(0, 2, "22222", title = "B")
        val blocks = dayBlocks(listOf(a, b, entry(0, 3, "22222", title = "B")), 0)
        // 複数授業のコマは連結せず、授業ごとに1ブロック。続く3限は別ブロック
        assertEquals(listOf("A", "B", "B"), blocks.map { it.entry.title })
        assertEquals(listOf(2, 2, 3), blocks.map { it.firstPeriod })
        assertEquals(listOf(2, 2, 3), blocks.map { it.lastPeriod })
    }

    // ---- コース詳細の現在週 ----

    @Test
    fun currentSectionAcrossYearEnd() {
        val names = listOf("一般", "12月21日 - 12月27日", "12月28日 - 1月3日", "1月4日 - 1月10日")
        // 1月に見ても、去年の12月に始まった週を今週と判定する
        assertEquals(2, currentSectionIndex(names, LocalDate.of(2027, 1, 1)))
        assertEquals(2, currentSectionIndex(names, LocalDate.of(2026, 12, 30)))
        assertEquals(3, currentSectionIndex(names, LocalDate.of(2027, 1, 5)))
    }

    @Test
    fun currentSectionFromDateRangeNames() {
        val names = listOf("一般", "9月21日 - 9月27日", "9月28日 - 10月4日", "10/5 - 10/11")
        assertEquals(2, currentSectionIndex(names, LocalDate.of(2026, 9, 30)))
        assertEquals(3, currentSectionIndex(names, LocalDate.of(2026, 10, 6)))
        assertNull(currentSectionIndex(listOf("第1回", "第2回"), LocalDate.of(2026, 9, 30)))
    }
}
