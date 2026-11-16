package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Course
import dev.rits.bettermoodle.data.CourseModuleInfo
import dev.rits.bettermoodle.data.ForumTarget
import dev.rits.bettermoodle.data.PageTarget
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.QuizTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * ルートの組み立てと解決。android.net.Uri は JVM で使えないので、どこがエンコードされるかを
 * 目印付きのエンコーダで確かめる。
 */
class RoutesTest {

    private val mark: (String) -> String = { "<$it>" }
    private val plain: (String) -> String = { it }

    @Test
    fun `builders encode only the string arguments`() {
        assertEquals("course/5?name=<名前>&code=<12345>", Routes.course(5, "名前", "12345", mark))
        assertEquals("course/5?name=<名前>&code=<>", Routes.course(5, "名前", null, mark))
        assertEquals("pdf?url=<u>&title=<t>", Routes.pdf("u", "t", mark))
        assertEquals("assignment/1/2/3?title=<t>", Routes.assignment(1, 2, 3, "t", mark))
        assertEquals(
            "filePreview?url=<u>&title=<t>&kind=<Image>",
            Routes.filePreview("u", "t", PreviewKind.Image, mark),
        )
        assertEquals("moodleWeb?url=<u>&title=<t>", Routes.moodleWeb("u", "t", mark))
        assertEquals("forumDiscussion?discussionId=9&title=<t>&url=<u>", Routes.forumDiscussion(9, "t", "u", mark))
    }

    @Test
    fun `activity routes write zero for a missing context id and empty url`() {
        assertEquals(
            "forum?courseId=1&courseModuleId=2&forumInstanceId=3&contextId=0&modName=<forum>&title=<t>&url=<>",
            Routes.forum(ForumTarget(1, 2, 3, null, "forum", null), "t", mark),
        )
        assertEquals(
            "quiz?courseId=1&courseModuleId=2&quizInstanceId=3&contextId=4&modName=<quiz>&title=<t>&url=<u>",
            Routes.quiz(QuizTarget(1, 2, 3, 4, "quiz", "u"), "t", mark),
        )
        assertEquals(
            "page?courseId=1&courseModuleId=2&contextId=0&modName=<page>&title=<t>&url=<u>",
            Routes.page(PageTarget(1, 2, null, "page", "u"), "t", mark),
        )
    }

    @Test
    fun `route patterns name the same arguments the builders write`() {
        assertEquals("pdf?url={url}&title={title}", Routes.PDF)
        assertEquals("moodleWeb?url={url}&title={title}", Routes.MOODLE_WEB)
        assertEquals("filePreview?url={url}&title={title}&kind={kind}", Routes.FILE_PREVIEW)
        assertEquals("assignment/{courseId}/{moduleId}/{assignId}?title={title}", Routes.ASSIGNMENT)
        assertEquals(
            "forum?courseId={courseId}&courseModuleId={courseModuleId}&forumInstanceId={forumInstanceId}" +
                "&contextId={contextId}&modName={modName}&title={title}&url={url}",
            Routes.FORUM,
        )
        assertEquals(
            "quiz?courseId={courseId}&courseModuleId={courseModuleId}&quizInstanceId={quizInstanceId}" +
                "&contextId={contextId}&modName={modName}&title={title}&url={url}",
            Routes.QUIZ,
        )
        assertEquals(
            "page?courseId={courseId}&courseModuleId={courseModuleId}&contextId={contextId}&modName={modName}&title={title}&url={url}",
            Routes.PAGE,
        )
    }

    @Test
    fun `targets are restored from route arguments with the same defaults`() {
        val args = mapOf(
            "courseId" to "1",
            "courseModuleId" to "2",
            "forumInstanceId" to "3",
            "quizInstanceId" to "4",
            "contextId" to "5",
            "modName" to "forum",
            "url" to "https://lms.ritsumei.ac.jp/mod/forum/view.php?id=2",
        )
        assertEquals(
            ForumTarget(1, 2, 3, 5, "forum", "https://lms.ritsumei.ac.jp/mod/forum/view.php?id=2"),
            Routes.forumTarget(args::get),
        )
        assertEquals(
            QuizTarget(1, 2, 4, 5, "forum", "https://lms.ritsumei.ac.jp/mod/forum/view.php?id=2"),
            Routes.quizTarget(args::get),
        )

        val sparse = mapOf("courseId" to "x", "contextId" to "0", "url" to " ")
        assertEquals(ForumTarget(0, 0, 0, null, "", null), Routes.forumTarget(sparse::get))
        assertEquals(QuizTarget(0, 0, 0, null, "", null), Routes.quizTarget(sparse::get))
        assertEquals(PageTarget(0, 0, null, "", null), Routes.pageTarget(sparse::get))
    }

    @Test
    fun `plugin files open in the matching preview`() {
        val pdf = "https://lms.ritsumei.ac.jp/pluginfile.php/1/mod_resource/content/1/a.pdf"
        assertEquals("pdf?url=<$pdf>&title=<資料>", Routes.pluginFile(pdf, "資料", mark, plain))
        assertEquals("pdf?url=<$pdf>&title=<a.pdf>", Routes.pluginFile(pdf, "", mark, plain))

        val image = "https://lms.ritsumei.ac.jp/pluginfile.php/1/mod_forum/post/1/b.png"
        assertEquals(
            "filePreview?url=<$image>&title=<b.png>&kind=<Image>",
            Routes.pluginFile(image, " ", mark, plain),
        )
        val text = "https://lms.ritsumei.ac.jp/pluginfile.php/1/mod_forum/post/1/c.txt"
        assertEquals(
            "filePreview?url=<$text>&title=<c.txt>&kind=<Text>",
            Routes.pluginFile(text, "", mark, plain),
        )
    }

    @Test
    fun `plugin file label uses the decoded file name`() {
        val url = "https://lms.ritsumei.ac.jp/pluginfile.php/1/mod_resource/content/1/a.pdf"
        assertEquals("pdf?url=<$url>&title=<decoded-a.pdf>", Routes.pluginFile(url, "", mark) { "decoded-$it" })
    }

    @Test
    fun `other plugin files and non plugin urls are not previewed`() {
        assertNull(Routes.pluginFile("https://lms.ritsumei.ac.jp/pluginfile.php/1/mod_resource/content/1/a.docx", "", mark, plain))
        assertNull(Routes.pluginFile("https://lms.ritsumei.ac.jp/pluginfile.php/1/mod_resource/content/1/a.html", "", mark, plain))
        assertNull(Routes.pluginFile("https://lms.ritsumei.ac.jp/mod/assign/view.php?id=1", "", mark, plain))
        assertNull(Routes.pluginFile("https://evil.example.com/pluginfile.php/1/a.pdf", "", mark, plain))
    }

    private fun resolve(
        url: String,
        title: String = "t",
        cm: CourseModuleInfo? = null,
        courses: List<Course> = emptyList(),
    ): String? = runBlocking {
        Routes.resolveNative(url, title, { cm }, { courses }, mark)
    }

    @Test
    fun `course and discussion urls resolve without fetching`() {
        val noFetch: suspend (Long) -> CourseModuleInfo? = { fail("fetched"); null }
        assertEquals(
            "course/100?name=<コース>&code=<>",
            runBlocking {
                Routes.resolveNative("https://lms.ritsumei.ac.jp/course/view.php?id=100", "", noFetch, { emptyList() }, mark)
            },
        )
        val discussion = "https://lms.ritsumei.ac.jp/mod/forum/discuss.php?d=55"
        assertEquals(
            "forumDiscussion?discussionId=55&title=<t>&url=<$discussion>",
            resolve(discussion),
        )
        assertNull(resolve("https://evil.example.com/course/view.php?id=1"))
    }

    @Test
    fun `module urls resolve to native screens using the fetched module`() {
        val assign = "https://lms.ritsumei.ac.jp/mod/assign/view.php?id=7"
        assertEquals(
            "assignment/3/7/70?title=<課題名>",
            resolve(assign, cm = CourseModuleInfo(id = 7, course = 3, modName = "assign", instance = 70, name = "課題名")),
        )
        assertEquals(
            "assignment/3/7/70?title=<t>",
            resolve(assign, cm = CourseModuleInfo(id = 7, course = 3, modName = "assign", instance = 70, name = "")),
        )
        assertNull(resolve(assign, cm = CourseModuleInfo(id = 7, course = 3, modName = "assign", instance = 0)))

        val quiz = "https://lms.ritsumei.ac.jp/mod/quiz/view.php?id=8"
        assertEquals(
            "quiz?courseId=3&courseModuleId=8&quizInstanceId=80&contextId=0&modName=<quiz>&title=<q>&url=<$quiz>",
            resolve(quiz, cm = CourseModuleInfo(id = 8, course = 3, modName = "quiz", instance = 80, name = "q")),
        )
        val forum = "https://lms.ritsumei.ac.jp/mod/forum/view.php?id=9"
        assertEquals(
            "forum?courseId=3&courseModuleId=9&forumInstanceId=90&contextId=0&modName=<forum>&title=<f>&url=<$forum>",
            resolve(forum, cm = CourseModuleInfo(id = 9, course = 3, modName = "forum", instance = 90, name = "f")),
        )
        val page = "https://lms.ritsumei.ac.jp/mod/page/view.php?id=10"
        assertEquals(
            "page?courseId=3&courseModuleId=10&contextId=0&modName=<page>&title=<p>&url=<$page>",
            resolve(page, cm = CourseModuleInfo(id = 10, course = 3, modName = "page", instance = 0, name = "p")),
        )
    }

    @Test
    fun `other modules open their course`() {
        val url = "https://lms.ritsumei.ac.jp/mod/resource/view.php?id=11"
        val cm = CourseModuleInfo(id = 11, course = 3, modName = "resource", instance = 110, name = "r")
        assertEquals(
            "course/3?name=<科目名>&code=<12345>",
            resolve(url, cm = cm, courses = listOf(Course(id = 3, fullname = "12345:科目名"))),
        )
        assertEquals("course/3?name=<コース>&code=<>", resolve(url, cm = cm))
    }

    @Test
    fun `fetch failures fall back but cancellation propagates`() {
        val url = "https://lms.ritsumei.ac.jp/mod/resource/view.php?id=11"
        val cm = CourseModuleInfo(id = 11, course = 3, modName = "resource", instance = 110, name = "r")

        assertNull(runBlocking { Routes.resolveNative(url, "t", { error("network") }, { emptyList() }, mark) })
        assertEquals(
            "course/3?name=<コース>&code=<>",
            runBlocking { Routes.resolveNative(url, "t", { cm }, { error("network") }, mark) },
        )

        runBlocking {
            try {
                Routes.resolveNative(url, "t", { throw CancellationException("module") }, { emptyList() }, mark)
                fail("cancellation was swallowed")
            } catch (e: CancellationException) {
                assertEquals("module", e.message)
            }
            try {
                Routes.resolveNative(url, "t", { cm }, { throw CancellationException("courses") }, mark)
                fail("cancellation was swallowed")
            } catch (e: CancellationException) {
                assertEquals("courses", e.message)
            }
        }
    }
}
