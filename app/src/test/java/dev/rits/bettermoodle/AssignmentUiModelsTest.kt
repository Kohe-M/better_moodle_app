package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Assignment
import dev.rits.bettermoodle.data.AssignmentAction
import dev.rits.bettermoodle.data.AssignmentConfig
import dev.rits.bettermoodle.data.AssignmentGradingLabel
import dev.rits.bettermoodle.data.AssignmentLastAttempt
import dev.rits.bettermoodle.data.AssignmentSubmission
import dev.rits.bettermoodle.data.AssignmentSubmissionLabel
import dev.rits.bettermoodle.data.AssignmentAvailabilityLabel
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.SubmissionFileArea
import dev.rits.bettermoodle.data.SubmissionPlugin
import dev.rits.bettermoodle.data.SubmissionStatusResponse
import dev.rits.bettermoodle.data.buildAssignmentUiModel
import dev.rits.bettermoodle.data.extractSubmittedFiles
import dev.rits.bettermoodle.data.previewKindFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssignmentUiModelsTest {

    @Test
    fun `submitted and can edit becomes submitted editable ui state`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(duedate = 2_000, configs = listOf(AssignmentConfig("onlinetext", "", "enabled", "1"))),
            status = SubmissionStatusResponse(
                lastattempt = AssignmentLastAttempt(
                    canedit = true,
                    cansubmit = false,
                    gradingstatus = "notmarked",
                    submission = AssignmentSubmission(status = "submitted"),
                ),
            ),
            nowEpochSeconds = 1_000,
        )

        assertEquals(AssignmentSubmissionLabel.Submitted, ui.submission)
        assertEquals(AssignmentAvailabilityLabel.Resubmittable, ui.availability)
        assertEquals(AssignmentGradingLabel.NotMarked, ui.grading)
        assertTrue(AssignmentAction.Edit in ui.actions)
        assertFalse(AssignmentAction.FinalSubmit in ui.actions)
    }

    @Test
    fun `submitted and can submit false does not show final submit action`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(duedate = 2_000),
            status = SubmissionStatusResponse(
                lastattempt = AssignmentLastAttempt(
                    canedit = false,
                    cansubmit = false,
                    submission = AssignmentSubmission(status = "submitted"),
                ),
            ),
            nowEpochSeconds = 1_000,
        )

        assertEquals(AssignmentSubmissionLabel.Submitted, ui.submission)
        assertFalse(AssignmentAction.FinalSubmit in ui.actions)
    }

    @Test
    fun `draft with submit permission shows final submit action`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(duedate = 2_000),
            status = SubmissionStatusResponse(
                lastattempt = AssignmentLastAttempt(
                    canedit = true,
                    cansubmit = true,
                    submission = AssignmentSubmission(status = "draft"),
                ),
            ),
            nowEpochSeconds = 1_000,
        )

        assertEquals(AssignmentSubmissionLabel.Draft, ui.submission)
        assertTrue(AssignmentAction.Edit in ui.actions)
        assertTrue(AssignmentAction.FinalSubmit in ui.actions)
    }

    @Test
    fun `submitted files are extracted from file plugin areas`() {
        val status = SubmissionStatusResponse(
            lastattempt = AssignmentLastAttempt(
                submission = AssignmentSubmission(
                    plugins = listOf(
                        SubmissionPlugin(
                            type = "file",
                            fileareas = listOf(
                                SubmissionFileArea(
                                    area = "submission_files",
                                    files = listOf(
                                        MoodleFile(
                                            filename = "report.pdf",
                                            filesize = 1234,
                                            fileurl = "https://lms.ritsumei.ac.jp/pluginfile.php/1/report.pdf",
                                            mimetype = "application/pdf",
                                            timemodified = 111,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val files = extractSubmittedFiles(status)
        assertEquals(1, files.size)
        assertEquals("report.pdf", files.single().filename)
        assertEquals(PreviewKind.Pdf, files.single().previewKind)
        assertEquals(1234L, files.single().sizeBytes)
    }

    @Test
    fun `preview kind dispatches pdf image text and unsupported`() {
        assertEquals(PreviewKind.Pdf, previewKindFor("a.pdf", null))
        assertEquals(PreviewKind.Image, previewKindFor("a.bin", "image/png"))
        assertEquals(PreviewKind.Text, previewKindFor("notes.txt", null))
        assertEquals(PreviewKind.Unsupported, previewKindFor("slides.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }
}
