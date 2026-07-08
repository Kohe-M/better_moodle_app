package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.parseCourseModuleIdFromUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionEventRoutingTest {

    @Test
    fun `course module id is parsed from Moodle module urls`() {
        assertEquals(
            12345L,
            parseCourseModuleIdFromUrl("https://moodle.example.ac.jp/mod/assign/view.php?id=12345"),
        )
        assertEquals(
            67890L,
            parseCourseModuleIdFromUrl("/mod/quiz/view.php?forceview=1&id=67890&lang=ja"),
        )
    }

    @Test
    fun `course module id parser accepts escaped query separators`() {
        assertEquals(
            24680L,
            parseCourseModuleIdFromUrl("https://moodle.example.ac.jp/mod/quiz/view.php?foo=bar&amp;id=24680"),
        )
    }

    @Test
    fun `course module id parser ignores missing or invalid ids`() {
        assertNull(parseCourseModuleIdFromUrl(null))
        assertNull(parseCourseModuleIdFromUrl(""))
        assertNull(parseCourseModuleIdFromUrl("https://moodle.example.ac.jp/mod/assign/view.php"))
        assertNull(parseCourseModuleIdFromUrl("https://moodle.example.ac.jp/mod/assign/view.php?id=abc"))
        assertNull(parseCourseModuleIdFromUrl("https://moodle.example.ac.jp/mod/assign/view.php?id=0"))
    }
}
