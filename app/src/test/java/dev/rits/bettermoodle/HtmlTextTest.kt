package dev.rits.bettermoodle

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import dev.rits.bettermoodle.ui.htmlToAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    @Test
    fun `keeps paragraph and br line breaks`() {
        val text = htmlToAnnotatedString("<p>第一段落</p><div>第二<br>第三</div>")

        assertEquals("第一段落\n第二\n第三", text.text)
    }

    @Test
    fun `renders list items and images`() {
        val text = htmlToAnnotatedString("<ul><li>資料</li><li><img src=\"x.png\"></li></ul>")

        assertEquals("・資料\n・[画像]", text.text)
    }

    @Test
    fun `keeps link annotation and bold style`() {
        val text = htmlToAnnotatedString(
            "<p><strong>重要</strong>: <a href=\"https://example.com/material\">資料</a></p>",
        )

        assertEquals("重要: 資料", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 2 })

        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/material", (links.single().item as LinkAnnotation.Url).url)
        assertEquals("資料", text.text.substring(links.single().start, links.single().end).trim())
    }

    @Test
    fun `removes dangerous tags and unsafe hrefs`() {
        val text = htmlToAnnotatedString(
            "<p>本文<script>alert('x')</script><a href=\"javascript:alert(1)\">危険</a></p>",
        )

        assertFalse(text.text.contains("alert"))
        assertTrue(text.text.contains("本文"))
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }
}
