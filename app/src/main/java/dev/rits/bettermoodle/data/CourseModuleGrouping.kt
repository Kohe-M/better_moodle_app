package dev.rits.bettermoodle.data

data class ModuleGroup(
    val module: CourseModule?,
    val labels: List<CourseModule>,
)

fun groupSectionModules(modules: List<CourseModule>): List<ModuleGroup> {
    val groups = mutableListOf<ModuleGroup>()
    modules.forEach { module ->
        if (module.modName == "label") {
            val previousGroup = groups.lastOrNull()
            if (previousGroup == null) {
                groups += ModuleGroup(module = null, labels = listOf(module))
            } else {
                groups[groups.lastIndex] = previousGroup.copy(labels = previousGroup.labels + module)
            }
        } else {
            groups += ModuleGroup(module = module, labels = emptyList())
        }
    }
    return groups
}

private val JapaneseRange = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日.*?[-–—~〜～]\s*(?:(\d{1,2})\s*月\s*)?(\d{1,2})\s*日""")
private val SlashRange = Regex("""(\d{1,2})/(\d{1,2}).*?[-–—~〜～]\s*(?:(\d{1,2})/)?(\d{1,2})""")

/**
 * 週形式のコースで、名前の日付範囲 (「4月8日 - 4月14日」「4/8 - 4/14」) が [today] を含むセクションの位置。
 * 見つからなければ null (トピック形式など。呼び出し側は全セクションを展開する)。
 */
fun currentSectionIndex(names: List<String>, today: java.time.LocalDate): Int? {
    names.forEachIndexed { index, name ->
        val m = JapaneseRange.find(name) ?: SlashRange.find(name) ?: return@forEachIndexed
        val (sm, sd, em, ed) = m.destructured
        val startMonth = sm.toIntOrNull() ?: return@forEachIndexed
        val endMonth = em.toIntOrNull() ?: startMonth
        // 名前に年はないので、開始が去年の場合 (1月に「12月28日 - 1月3日」を見る) も試す
        for (year in listOf(today.year, today.year - 1)) {
            val start = runCatching { java.time.LocalDate.of(year, startMonth, sd.toInt()) }.getOrNull() ?: return@forEachIndexed
            var end = runCatching { java.time.LocalDate.of(year, endMonth, ed.toInt()) }.getOrNull() ?: return@forEachIndexed
            if (end.isBefore(start)) end = end.plusYears(1) // 12月末〜1月
            if (!today.isBefore(start) && !today.isAfter(end)) return index
        }
    }
    return null
}
