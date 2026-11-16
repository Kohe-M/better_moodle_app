package dev.rits.bettermoodle.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 時間割: ダッシュボードのカスタムブロック rutime_table の HTML を解析する純関数。
 * 取得 (core_block_get_dashboard_blocks) は [MoodleRepository.timetable]。
 */
object TimetableParser {
    private val DAY_CHARS = listOf("月", "火", "水", "木", "金", "土", "日")
    private val DAY_OF_WEEK_HEADER_REGEX = Regex("([月火水木金土日])曜")

    /**
     * rutime_tableブロックの table.timetable を解析する。
     * 行 = 時限 (td.time に時限番号)、列 = 曜日 (ヘッダ行に曜日名)。
     * 各セルには .subject 要素があり、コースリンクとコース名
     * ("12345:科目名" 形式) が含まれる。
     */
    fun parse(html: String): Timetable {
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("table.timetable")
            ?: doc.selectFirst("table")
            ?: return Timetable.EMPTY

        // ヘッダから列index → 曜日indexの対応を作る
        val headerCells = table.select("tr").firstOrNull()?.children() ?: return Timetable.EMPTY
        val colToDay = mutableMapOf<Int, Int>()
        headerCells.forEachIndexed { col, cell ->
            val text = cell.text()
            val dayIdx = DAY_OF_WEEK_HEADER_REGEX.find(text)
                ?.groupValues
                ?.get(1)
                ?.let(DAY_CHARS::indexOf)
                ?: DAY_CHARS.indexOfFirst { text.contains(it) }
            if (dayIdx >= 0) {
                colToDay[col] = dayIdx
            }
        }

        val entries = mutableListOf<TimetableEntry>()
        for (row in table.select("tr").drop(1)) {
            val cells = row.children()
            val periodCell = cells.firstOrNull { it.hasClass("time") } ?: cells.firstOrNull()
            val period = periodCell?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                ?: continue

            cells.forEachIndexed { col, cell ->
                if (cell === periodCell) return@forEachIndexed
                val dayIdx = colToDay[col] ?: run {
                    // ヘッダに曜日がなければ「時限セルを除く左からの位置」で月〜日を割り当てる
                    val offset = if (cells.indexOf(periodCell) < col) col - 1 else col
                    offset.takeIf { it in 0..6 }
                } ?: return@forEachIndexed

                val subjects = cell.select(".subject").ifEmpty {
                    if (cell.selectFirst("a[href*=course/view.php]") != null) listOf(cell) else emptyList()
                }
                for (subject in subjects) {
                    parseSubject(subject, dayIdx, period)?.let(entries::add)
                }
            }
        }

        val usedDays = entries.map { it.dayIndex }.toSortedSet()
        val maxDay = maxOf(usedDays.maxOrNull() ?: 4, 4) // 最低 月〜金
        return Timetable(
            entries = entries,
            dayLabels = DAY_CHARS.subList(0, (maxDay + 1).coerceAtMost(7)),
        )
    }

    private fun parseSubject(subject: Element, dayIdx: Int, period: Int): TimetableEntry? {
        val link = subject.selectFirst("a[href*=course/view.php]")
        val rawText = (link?.text()?.takeIf { it.isNotBlank() } ?: subject.text()).trim()
        if (rawText.isBlank()) return null
        val code = Course.COURSE_CODE_REGEX.find(rawText)?.groupValues?.get(1)
        val title = rawText
            .replace(Regex("^\\d+\\s*[:：]\\s*"), "")
            .substringBefore('§')
            .trim()
        return TimetableEntry(
            dayIndex = dayIdx,
            period = period,
            title = title.ifBlank { rawText },
            courseCode = code,
            courseUrl = link?.attr("href"),
            room = extractRoom(subject, dayIdx, period),
        )
    }

    /**
     * 教室名を抽出する。rutime_tableでは .subject .room 要素に
     * "月1: 教室名" 形式で入る (曜日+時限プレフィックスを除去する)。
     */
    private fun extractRoom(subject: Element, dayIdx: Int, period: Int): String? {
        val roomText = subject.select(".room").joinToString("\n") { room ->
            room.wholeText().ifBlank { room.text() }
        }
        return extractRoomForSlot(roomText, dayIdx, period)
    }

    fun extractRoomForSlot(roomText: String?, dayIdx: Int, period: Int): String? {
        val text = roomText?.trim().orEmpty()
        if (text.isBlank()) return null

        val targetDay = DAY_CHARS.getOrNull(dayIdx) ?: return null
        val slotRegex = Regex("([月火水木金土日])\\s*(\\d+)\\s*[:：]\\s*")
        val matches = slotRegex.findAll(text).toList()
        if (matches.isEmpty()) return text.ifBlank { null }

        matches.forEachIndexed { index, match ->
            val day = match.groupValues[1]
            val matchedPeriod = match.groupValues[2].toIntOrNull()
            if (day == targetDay && matchedPeriod == period) {
                val start = match.range.last + 1
                val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
                return text.substring(start, end)
                    .trim()
                    .trim(';', '；', ',', '、', '/', '／')
                    .trim()
                    .ifBlank { null }
            }
        }
        return null
    }
}
