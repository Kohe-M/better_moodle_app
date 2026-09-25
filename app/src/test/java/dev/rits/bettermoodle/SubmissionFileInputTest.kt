package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Assignment
import dev.rits.bettermoodle.data.AssignmentConfig
import dev.rits.bettermoodle.data.AssignmentEditor
import dev.rits.bettermoodle.data.AssignmentFileDraft
import dev.rits.bettermoodle.data.AssignmentGateway
import dev.rits.bettermoodle.data.AssignmentLastAttempt
import dev.rits.bettermoodle.data.AssignmentSubmission
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.SubmissionFileArea
import dev.rits.bettermoodle.data.SubmissionPlugin
import dev.rits.bettermoodle.data.SubmissionStatusResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** G03 (Moodle のファイル名整形) と G06 (サイズ超過ファイルの事前拒否) */
class SubmissionFileInputTest {

    @Test
    fun `filename is cleaned with the same rules as Moodle PARAM_FILE`() {
        assertEquals("レポート資料.pdf", AssignmentFileDraft.cleanFilename("レポート&資料.pdf"))
        assertEquals("its.docx", AssignmentFileDraft.cleanFilename("it's.docx"))
        assertEquals("ab.txt", AssignmentFileDraft.cleanFilename("a:b.txt"))
        assertEquals("abc.pdf", AssignmentFileDraft.cleanFilename("a<b>\"c\".pdf"))
        assertEquals("ab.pdf", AssignmentFileDraft.cleanFilename("a\\/b.pdf"))
        assertEquals("tab.pdf", AssignmentFileDraft.cleanFilename("t\tab.pdf"))
        assertEquals("pipe.pdf", AssignmentFileDraft.cleanFilename("pi|pe`.pdf"))
        // PARAM_FILE が消さない文字はそのまま (空白・全角・? * など)
        assertEquals(" 課題 1 (最終)?.pdf ", AssignmentFileDraft.cleanFilename(" 課題 1 (最終)?.pdf "))
        assertEquals("submission", AssignmentFileDraft.cleanFilename(".."))
        assertEquals("submission", AssignmentFileDraft.cleanFilename("&&&"))
        assertEquals(".pdf", AssignmentFileDraft.cleanFilename("::.pdf"))
    }

    @Test
    fun `cleaned filename is never empty or a dot name`() {
        for (name in listOf("", ".", "..", "'", "&<>", "/\\")) {
            val cleaned = AssignmentFileDraft.cleanFilename(name)
            assertTrue(name, cleaned.isNotEmpty() && cleaned != "." && cleaned != "..")
        }
    }

    private class Gateway : AssignmentGateway {
        var serverFiles: List<MoodleFile> = emptyList()
        val built = mutableListOf<AssignmentFileDraft.Plan>()
        private val drafts = mutableMapOf<Long, List<MoodleFile>>()
        val assignment = Assignment(
            id = 42, cmid = 7, course = 1,
            configs = listOf(
                AssignmentConfig("file", "assignsubmission", "enabled", "1"),
                AssignmentConfig("file", "assignsubmission", "maxsubmissionsizebytes", "1024"),
            ),
        )

        override suspend fun loadAssignment(courseId: Long, moduleId: Long, assignId: Long) = assignment
        override suspend fun submissionStatus(assignId: Long) = SubmissionStatusResponse(
            lastattempt = AssignmentLastAttempt(
                submissionsenabled = true, canedit = true, cansubmit = true,
                submission = AssignmentSubmission(
                    status = "draft",
                    plugins = listOf(SubmissionPlugin(type = "file", fileareas = listOf(SubmissionFileArea("submission_files", serverFiles)))),
                ),
            ),
        )
        override suspend fun startSubmission(assignId: Long) = Unit
        override suspend fun saveOnlineText(assignmentId: Long, html: String) = Unit
        override suspend fun buildFileDraft(plan: AssignmentFileDraft.Plan, maxBytesPerFile: Long): Long {
            built += plan
            val id = 100L + built.size
            drafts[id] = plan.keep + listOfNotNull(plan.add?.let { MoodleFile(it.filename, "/", it.sizeBytes, "https://lms.ritsumei.ac.jp/pluginfile.php/1/${it.filename}") })
            return id
        }
        override suspend fun saveFiles(assignmentId: Long, draftItemId: Long) {
            serverFiles = drafts.getValue(draftItemId)
        }
        override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) = Unit
    }

    @Test
    fun `rejected local file shows its reason and uploads nothing`() = runBlocking {
        val gateway = Gateway()
        val editor = AssignmentEditor(gateway, 1, 7, 42)
        editor.refresh()

        val ok = editor.addFile { throw AssignmentFileDraft.LocalFileRejectedException("ファイルサイズが上限 (1MB) を超えています。") }

        assertFalse(ok)
        assertEquals("ファイルサイズが上限 (1MB) を超えています。", editor.state.value.error)
        assertTrue(gateway.built.isEmpty())
    }

    @Test
    fun `renamed file is saved under the cleaned name and the user is told`() = runBlocking {
        val gateway = Gateway()
        val editor = AssignmentEditor(gateway, 1, 7, 42)
        editor.refresh()
        val tmp = File.createTempFile("upload", ".pdf").apply { writeText("abc") }
        try {
            val ok = editor.addFile(
                AssignmentFileDraft.LocalFile(tmp, AssignmentFileDraft.cleanFilename("A&B.pdf"), originalFilename = "A&B.pdf"),
            )
            assertTrue(editor.state.value.error, ok)
            assertEquals(listOf("AB.pdf"), gateway.serverFiles.map { it.filename })
            val notice = editor.state.value.notice.orEmpty()
            assertTrue(notice, "AB.pdf" in notice && "A&B.pdf" in notice)
        } finally {
            tmp.delete()
        }
    }
}
