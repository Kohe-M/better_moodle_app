package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.CourseModule
import dev.rits.bettermoodle.data.groupSectionModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseModuleGroupingTest {

    @Test
    fun `module and two trailing labels are one group`() {
        val module = module(1, "resource")
        val firstLabel = module(2, "label")
        val secondLabel = module(3, "label")

        val groups = groupSectionModules(listOf(module, firstLabel, secondLabel))

        assertEquals(1, groups.size)
        assertEquals(module, groups[0].module)
        assertEquals(listOf(firstLabel, secondLabel), groups[0].labels)
    }

    @Test
    fun `leading label is a standalone group`() {
        val label = module(1, "label")
        val assignment = module(2, "assign")

        val groups = groupSectionModules(listOf(label, assignment))

        assertEquals(2, groups.size)
        assertNull(groups[0].module)
        assertEquals(listOf(label), groups[0].labels)
        assertEquals(assignment, groups[1].module)
        assertEquals(emptyList<CourseModule>(), groups[1].labels)
    }

    @Test
    fun `modules without labels each become one group`() {
        val resource = module(1, "resource")
        val quiz = module(2, "quiz")

        val groups = groupSectionModules(listOf(resource, quiz))

        assertEquals(2, groups.size)
        assertEquals(resource, groups[0].module)
        assertEquals(emptyList<CourseModule>(), groups[0].labels)
        assertEquals(quiz, groups[1].module)
        assertEquals(emptyList<CourseModule>(), groups[1].labels)
    }

    private fun module(id: Long, modName: String): CourseModule =
        CourseModule(id = id, modName = modName)
}
