package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.CourseModule
import dev.rits.bettermoodle.data.MoodleHttpException
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.MoodleWsException
import dev.rits.bettermoodle.data.PageBlock
import dev.rits.bettermoodle.data.PageLoadErrorKind
import dev.rits.bettermoodle.data.canFallbackToWebView
import dev.rits.bettermoodle.data.classifyPageLoadError
import dev.rits.bettermoodle.data.pluginFileNameFromUrl
import dev.rits.bettermoodle.data.splitHtmlToBlocks
import dev.rits.bettermoodle.data.toPageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRoutingTest {

    @Test
    fun `toPageTarget keeps cmid and url separate`() {
        val module = CourseModule(
            id = 7L,
            instance = 55L,
            contextId = 99L,
            name = "概要",
            url = "https://lms.ritsumei.ac.jp/mod/page/view.php?id=7",
            modName = "page",
        )
        val target = module.toPageTarget(courseId = 42L)
        assertEquals(42L, target.courseId)
        assertEquals(7L, target.courseModuleId)
        assertEquals(99L, target.contextId)
        assertEquals("https://lms.ritsumei.ac.jp/mod/page/view.php?id=7", target.url)
        assertTrue(target.isValid)
    }

    @Test
    fun `target is invalid for non-page module or missing ids`() {
        val forum = CourseModule(id = 7L, modName = "forum").toPageTarget(42L)
        assertFalse(forum.isValid)
        val noCmid = CourseModule(id = 0L, modName = "page").toPageTarget(42L)
        assertFalse(noCmid.isValid)
        val noCourse = CourseModule(id = 7L, modName = "page").toPageTarget(0L)
        assertFalse(noCourse.isValid)
    }

    @Test
    fun `pages params are fixed`() {
        assertEquals(
            mapOf("courseids[0]" to "42"),
            MoodleRepository.pagesByCoursesParams(42L),
        )
    }

    @Test
    fun `classify maps moodle error codes`() {
        assertEquals(
            PageLoadErrorKind.InvalidParameter,
            classifyPageLoadError(MoodleWsException("invalidparameter", "x")),
        )
        assertEquals(
            PageLoadErrorKind.InvalidRecord,
            classifyPageLoadError(MoodleWsException("invalidrecord", "x")),
        )
        assertEquals(
            PageLoadErrorKind.FunctionUnavailable,
            classifyPageLoadError(MoodleWsException("servicenotavailable", "x")),
        )
        assertEquals(
            PageLoadErrorKind.Network,
            classifyPageLoadError(java.io.IOException("boom")),
        )
        assertEquals(
            PageLoadErrorKind.Network,
            classifyPageLoadError(MoodleHttpException(503)),
        )
        assertTrue(PageLoadErrorKind.FunctionUnavailable.canFallbackToWebView())
        assertFalse(PageLoadErrorKind.InvalidParameter.canFallbackToWebView())
    }

    @Test
    fun `split keeps text and image order`() {
        val html = """
            <p>前置きの文章。</p>
            <img src="https://lms.ritsumei.ac.jp/webservice/pluginfile.php/1/mod_page/content/1/a.png" alt="">
            <p>後半の文章。</p>
        """.trimIndent()
        val blocks = splitHtmlToBlocks(html)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is PageBlock.Html)
        assertEquals(
            "https://lms.ritsumei.ac.jp/webservice/pluginfile.php/1/mod_page/content/1/a.png",
            (blocks[1] as PageBlock.Image).src,
        )
        assertTrue(blocks[2] is PageBlock.Html)
        assertTrue((blocks[0] as PageBlock.Html).html.contains("前置き"))
        assertTrue((blocks[2] as PageBlock.Html).html.contains("後半"))
    }

    @Test
    fun `split without images returns single html block`() {
        val blocks = splitHtmlToBlocks("<p>本文だけ</p><p>二段落目</p>")
        assertEquals(1, blocks.size)
        assertTrue((blocks[0] as PageBlock.Html).html.contains("二段落目"))
    }

    @Test
    fun `split handles consecutive images and blank html`() {
        val blocks = splitHtmlToBlocks("""<img src="a.png"><img src="b.png">""")
        assertEquals(2, blocks.size)
        assertEquals("a.png", (blocks[0] as PageBlock.Image).src)
        assertEquals("b.png", (blocks[1] as PageBlock.Image).src)
        assertTrue(splitHtmlToBlocks("").isEmpty())
        assertTrue(splitHtmlToBlocks(null).isEmpty())
    }

    @Test
    fun `split keeps image without src as empty placeholder`() {
        val blocks = splitHtmlToBlocks("""<p>文</p><img alt="x">""")
        assertEquals(2, blocks.size)
        assertEquals("", (blocks[1] as PageBlock.Image).src)
    }

    @Test
    fun `pluginfile name decodes encoded segment and strips query`() {
        assertEquals(
            "講義.pdf",
            pluginFileNameFromUrl(
                "https://lms.ritsumei.ac.jp/webservice/pluginfile.php/1/mod_page/content/1/%E8%AC%9B%E7%BE%A9.pdf?forcedownload=1",
            ),
        )
        assertEquals("a.png", pluginFileNameFromUrl("https://lms.ritsumei.ac.jp/pluginfile.php/1/a.png"))
        assertEquals("ファイル", pluginFileNameFromUrl("https://lms.ritsumei.ac.jp/"))
    }
}
