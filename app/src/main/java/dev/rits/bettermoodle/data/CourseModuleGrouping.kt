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
