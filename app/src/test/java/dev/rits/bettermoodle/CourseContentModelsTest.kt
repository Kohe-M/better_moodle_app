package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.CourseModule
import dev.rits.bettermoodle.data.ModuleContent
import dev.rits.bettermoodle.data.urlContentFileUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseContentModelsTest {

    @Test
    fun `url content fileurl is extracted from url content item`() {
        val module = CourseModule(
            modName = "url",
            contents = listOf(
                ModuleContent(type = "file", fileurl = "https://lms.ritsumei.ac.jp/pluginfile.php/1/a.pdf"),
                ModuleContent(type = "url", fileurl = "https://example.com/material"),
            ),
        )

        assertEquals("https://example.com/material", urlContentFileUrl(module))
    }

    @Test
    fun `url content fileurl ignores blank and non-url content`() {
        assertNull(
            urlContentFileUrl(
                CourseModule(
                    modName = "url",
                    contents = listOf(
                        ModuleContent(type = "url", fileurl = ""),
                        ModuleContent(type = "file", fileurl = "https://example.com/file"),
                    ),
                ),
            ),
        )
    }
}
