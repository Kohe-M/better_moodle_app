package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.MoodleUrlTarget
import dev.rits.bettermoodle.data.parseMoodleUrlTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodleUrlRoutingTest {

    @Test
    fun `module view urls are parsed as module targets`() {
        assertEquals(
            MoodleUrlTarget.Module(123L, "assign"),
            parseMoodleUrlTarget("https://lms.ritsumei.ac.jp/mod/assign/view.php?id=123"),
        )
        assertEquals(
            MoodleUrlTarget.Module(5L, "quiz"),
            parseMoodleUrlTarget("https://lms.ritsumei.ac.jp/mod/quiz/view.php?id=5&forceview=1"),
        )
        assertEquals(
            MoodleUrlTarget.Module(7L, "forum"),
            parseMoodleUrlTarget("https://lms.ritsumei.ac.jp/mod/forum/view.php?id=7#p10"),
        )
        assertEquals(
            MoodleUrlTarget.Module(9L, "page"),
            parseMoodleUrlTarget("https://lms.ritsumei.ac.jp/mod/page/view.php?foo=bar&amp;id=9"),
        )
    }

    @Test
    fun `course view urls are parsed as course targets`() {
        assertEquals(
            MoodleUrlTarget.Course(100L),
            parseMoodleUrlTarget("https://lms.ritsumei.ac.jp/course/view.php?id=100"),
        )
    }

    @Test
    fun `unsupported or unsafe urls are ignored`() {
        assertNull(parseMoodleUrlTarget("https://lms.ritsumei.ac.jp/mod/forum/discuss.php?d=55"))
        assertNull(parseMoodleUrlTarget("https://evil.example.com/mod/assign/view.php?id=1"))
        assertNull(parseMoodleUrlTarget("http://lms.ritsumei.ac.jp/mod/assign/view.php?id=1"))
        assertNull(parseMoodleUrlTarget("https://lms.ritsumei.ac.jp/mod/assign/view.php"))
    }
}
