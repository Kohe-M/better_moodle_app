package dev.rits.bettermoodle.data

/**
 * 授業カラーの自動割り当て (DESIGN.md §3「授業カラー」)。
 * - 既定はコースキーのハッシュ mod 10
 * - 時間割で隣り合うコマ (同曜日の前後の時限・同時限の隣の曜日) に同じ色が並ぶときは次の色へずらす
 * - 利用者が選んだ色 ([overrides]) はずらさない
 * 色の実体 (bg/fg) は UI 側のパレット。ここではパレットのインデックスだけを扱う。
 */
object CourseColors {
    const val PALETTE_SIZE = 10

    /**
     * 画面をまたいで同じ授業を同じ色にするためのキー。
     * コースIDを優先する。IDは時間割 (courseUrl)・コース一覧・締切・Moodleのリンクのどこからでも取れるが、
     * 授業コードはリンクや通知から開いたときや、コマ名にコードがないときに欠けるため、
     * コードを優先すると同じ授業でも入口によってキーが変わってしまう。
     * IDがなければ授業コード、最後に名前。
     */
    fun key(courseCode: String?, courseId: Long?, title: String = ""): String = when {
        courseId != null && courseId > 0L -> "i:$courseId"
        !courseCode.isNullOrBlank() -> "c:$courseCode"
        else -> "t:${title.trim()}"
    }

    fun key(entry: TimetableEntry): String = key(entry.courseCode, entry.courseId, entry.title)

    fun key(course: Course): String = key(course.courseCode, course.id, course.displayName)

    /** String.hashCode は JVM 間で安定しているので、起動し直しても同じ色になる */
    fun hashIndex(key: String): Int = Math.floorMod(key.hashCode(), PALETTE_SIZE)

    /**
     * 時間割のコマから授業ごとの色インデックスを決める。曜日→時限の順に初出の授業から確定させる。
     */
    fun assign(entries: List<TimetableEntry>, overrides: Map<String, Int> = emptyMap()): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        val keyAt = HashMap<Pair<Int, Int>, MutableSet<String>>()
        entries.forEach { keyAt.getOrPut(it.dayIndex to it.period) { HashSet() }.add(key(it)) }

        val ordered = entries.sortedWith(compareBy({ it.dayIndex }, { it.period }))
        for (entry in ordered) {
            val k = key(entry)
            if (k in result) continue
            overrides[k]?.takeIf { it in 0 until PALETTE_SIZE }?.let {
                result[k] = it
                continue
            }
            // この授業が置かれた全コマの隣にある、すでに色の決まった別の授業の色
            val taken = HashSet<Int>()
            entries.filter { key(it) == k }.forEach { cell ->
                val neighbours = listOf(
                    cell.dayIndex to cell.period - 1,
                    cell.dayIndex to cell.period + 1,
                    cell.dayIndex - 1 to cell.period,
                    cell.dayIndex + 1 to cell.period,
                )
                neighbours.forEach { pos ->
                    keyAt[pos]?.forEach { other -> if (other != k) result[other]?.let(taken::add) }
                }
            }
            var index = hashIndex(k)
            var tries = 0
            while (index in taken && tries < PALETTE_SIZE) {
                index = (index + 1) % PALETTE_SIZE
                tries++
            }
            result[k] = index
        }
        return result
    }
}
