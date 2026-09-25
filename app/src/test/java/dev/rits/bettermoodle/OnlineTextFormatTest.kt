package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.AssignmentFileDraft
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.OnlineTextFormat
import dev.rits.bettermoodle.data.SubmissionFileFingerprint
import dev.rits.bettermoodle.data.SubmissionFileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F06/N03: プレーンテキスト入力と HTML (format=1) の契約。N01: ファイル計画。 */
class OnlineTextFormatTest {

    @Test
    fun `escapes special characters and converts line breaks`() {
        assertEquals(
            "<p>1 &lt; 2 &amp;&amp; &quot;q&quot; &gt; 0<br />次の行</p>",
            OnlineTextFormat.plainTextToHtml("1 < 2 && \"q\" > 0\n次の行"),
        )
        assertEquals("<p>a</p><p>b</p>", OnlineTextFormat.plainTextToHtml("a\n\nb"))
        assertEquals("<p>a</p><p>b</p>", OnlineTextFormat.plainTextToHtml("a\r\n\r\n\r\nb\n"))
        assertEquals("", OnlineTextFormat.plainTextToHtml("   \n"))
    }

    @Test
    fun `round trips plain paragraphs breaks tabs and inner spaces`() {
        val text = "日本語の段落\n改行あり\n\n第二段落 <tag> & amp\n\tタブ  と  連続空白"
        val html = OnlineTextFormat.plainTextToHtml(text)
        assertEquals(text, OnlineTextFormat.htmlToEditableText(html))
        assertFalse(OnlineTextFormat.isRichHtml(html))
    }

    @Test
    fun `whitespace rules are explicit - trailing spaces and extra blank lines are dropped`() {
        assertEquals("a\n\nb", OnlineTextFormat.normalizeEditableText("a   \n\n\n\nb\n\n"))
        assertEquals("a\n\nb", OnlineTextFormat.normalizeEditableText("a\r\n\r\nb"))
    }

    @Test
    fun `existing moodle html with whitespace between paragraphs is editable`() {
        val html = "<p>one</p>\n<p>two<br>three</p>\n"
        assertEquals("one\n\ntwo\nthree", OnlineTextFormat.htmlToEditableText(html))
        assertEquals("", OnlineTextFormat.htmlToEditableText(null))
    }

    /** T04 + N03: 属性付きの書式もリッチとみなす */
    @Test
    fun `rich html including styled attributes is detected so it is not silently flattened`() {
        assertTrue(OnlineTextFormat.isRichHtml("<p><span style=\"color:red;font-weight:bold\">important</span></p>"))
        assertTrue(OnlineTextFormat.isRichHtml("<p align=\"center\">centered</p>"))
        assertTrue(OnlineTextFormat.isRichHtml("<p class=\"lead\">x</p>"))
        assertTrue(OnlineTextFormat.isRichHtml("<p><b>bold</b></p>"))
        assertTrue(OnlineTextFormat.isRichHtml("<p>see <img src=\"x.png\"></p>"))
        assertTrue(OnlineTextFormat.isRichHtml("<ul><li>a</li></ul>"))
        assertTrue(OnlineTextFormat.isRichHtml("<p><a href=\"https://x\">link</a></p>"))
        assertTrue(OnlineTextFormat.isRichHtml("<table><tr><td>1</td></tr></table>"))
        assertFalse(OnlineTextFormat.isRichHtml("<p>plain<br>text</p><div>more</div>"))
        assertFalse(OnlineTextFormat.isRichHtml("<p>a<span>b</span></p>"))
        assertFalse(OnlineTextFormat.isRichHtml(""))
    }

    @Test
    fun `comparison keeps meaningful inner differences`() {
        assertEquals(OnlineTextFormat.editableTextOf("<p>a</p><p>b</p>"), OnlineTextFormat.editableTextOf("<p>a</p>\n\n<p>b</p>"))
        assertTrue(OnlineTextFormat.editableTextOf("<p>a  b</p>") != OnlineTextFormat.editableTextOf("<p>a b</p>"))
    }

    /** T01: フォルダ違いの同名ファイルは別ファイル */
    @Test
    fun `plan preserves same filename in different directories`() {
        val existing = listOf(
            MoodleFile(filename = "report.pdf", filepath = "/chapter1/"),
            MoodleFile(filename = "report.pdf", filepath = "/chapter2/"),
        )
        val plan = AssignmentFileDraft.plan(existing, null)
        assertEquals(2, plan.keep.size)

        // ルートに同名を追加しても、サブディレクトリの同名は置き換えない
        val add = AssignmentFileDraft.LocalFile(File("report.pdf"), "report.pdf", sizeBytes = 5)
        val withAdd = AssignmentFileDraft.plan(existing, add)
        assertEquals(2, withAdd.keep.size)
        assertNull(withAdd.replaced)

        // 同じキー (ルート同名) は置き換え
        val root = existing + MoodleFile(filename = "report.pdf", filepath = "/", filesize = 3)
        val replaced = AssignmentFileDraft.plan(root, add)
        assertEquals(2, replaced.keep.size)
        assertEquals("/", replaced.replaced?.filepath)

        // 削除はキー単位
        val removal = AssignmentFileDraft.plan(existing, add = null, remove = setOf(SubmissionFileKey("/chapter1/", "report.pdf")))
        assertEquals(listOf("/chapter2/"), removal.keep.map { it.filepath })
    }

    @Test
    fun `file limits are reported`() {
        val plan = AssignmentFileDraft.plan(
            listOf(MoodleFile(filename = "A.pdf")),
            AssignmentFileDraft.LocalFile(File("B.pdf"), "B.pdf", sizeBytes = 1),
        )
        assertNull(AssignmentFileDraft.fileCountViolation(plan, maxFiles = 2))
        assertNotNull(AssignmentFileDraft.fileCountViolation(plan, maxFiles = 1))
        assertNull(AssignmentFileDraft.fileCountViolation(plan, maxFiles = 0))
        assertNull(AssignmentFileDraft.fileSizeViolation(10, maxBytes = 0))
        assertNotNull(AssignmentFileDraft.fileSizeViolation(2L * 1024 * 1024 + 1, maxBytes = 2L * 1024 * 1024))
    }

    @Test
    fun `verifySaved detects missing files and stale sizes`() {
        val expected = setOf(
            SubmissionFileFingerprint(SubmissionFileKey("/", "A.pdf"), 10),
            SubmissionFileFingerprint(SubmissionFileKey("/", "B.pdf"), 20),
        )
        assertNull(
            AssignmentFileDraft.verifySaved(
                expected,
                listOf(MoodleFile(filename = "A.pdf", filesize = 10), MoodleFile(filename = "B.pdf", filesize = 20)),
            ),
        )
        assertNotNull(AssignmentFileDraft.verifySaved(expected, listOf(MoodleFile(filename = "A.pdf", filesize = 10))))
        assertNotNull(
            AssignmentFileDraft.verifySaved(
                expected,
                listOf(MoodleFile(filename = "A.pdf", filesize = 10), MoodleFile(filename = "B.pdf", filesize = 3)),
            ),
        )
        // R07: 既知の 0 バイトは「不明」ではない。0→正、正→0、0→0、不明 を区別する
        assertNotNull(
            "known local zero bytes is not unknown",
            AssignmentFileDraft.verifySaved(
                setOf(SubmissionFileFingerprint(SubmissionFileKey.root("empty.txt"), 0)),
                listOf(MoodleFile(filename = "empty.txt", filepath = "/", filesize = 100)),
            ),
        )
        assertNotNull(
            AssignmentFileDraft.verifySaved(
                expected,
                listOf(MoodleFile(filename = "A.pdf", filesize = 0), MoodleFile(filename = "B.pdf", filesize = 20)),
            ),
        )
        assertNull(
            AssignmentFileDraft.verifySaved(
                setOf(SubmissionFileFingerprint(SubmissionFileKey.root("empty.txt"), 0)),
                listOf(MoodleFile(filename = "empty.txt", filepath = "/", filesize = 0)),
            ),
        )
        // サイズ不明 (null) は検証済みと断定しない
        assertNotNull(
            AssignmentFileDraft.verifySaved(
                expected,
                listOf(MoodleFile(filename = "A.pdf", filesize = null), MoodleFile(filename = "B.pdf", filesize = 20)),
            ),
        )
        assertNotNull(
            AssignmentFileDraft.verifySaved(
                setOf(SubmissionFileFingerprint(SubmissionFileKey.root("x.pdf"), null)),
                listOf(MoodleFile(filename = "x.pdf", filesize = 5)),
            ),
        )
    }
}
