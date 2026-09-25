package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Assignment
import dev.rits.bettermoodle.data.AssignmentAction
import dev.rits.bettermoodle.data.AssignmentConfig
import dev.rits.bettermoodle.data.AssignmentFeedback
import dev.rits.bettermoodle.data.AssignmentGrade
import dev.rits.bettermoodle.data.AssignmentGradingLabel
import dev.rits.bettermoodle.data.AssignmentLastAttempt
import dev.rits.bettermoodle.data.AssignmentSubmission
import dev.rits.bettermoodle.data.AssignmentSubmissionLabel
import dev.rits.bettermoodle.data.AssignmentAvailabilityLabel
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.SubmissionEditorField
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
    fun `negative feedback grade is not treated as numeric grade`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(),
            status = SubmissionStatusResponse(
                feedback = AssignmentFeedback(
                    grade = AssignmentGrade(grade = "-1.00000"),
                ),
            ),
            nowEpochSeconds = 1_000,
        )

        assertEquals(AssignmentGradingLabel.NotMarked, ui.grading)
    }

    @Test
    fun `non-negative feedback grade is treated as numeric grade`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(),
            status = SubmissionStatusResponse(
                feedback = AssignmentFeedback(
                    grade = AssignmentGrade(grade = "85.00000"),
                ),
            ),
            nowEpochSeconds = 1_000,
        )

        assertEquals(AssignmentGradingLabel.Graded, ui.grading)
        assertEquals(AssignmentAvailabilityLabel.Graded, ui.availability)
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
        assertEquals(PreviewKind.Html, previewKindFor("index.html", null))
        assertEquals(PreviewKind.Html, previewKindFor("page.bin", "text/html"))
        assertEquals(PreviewKind.Unsupported, previewKindFor("slides.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }
}

class AssignmentActionRulesTest {

    private fun status(status: String, canEdit: Boolean, canSubmit: Boolean, timestarted: Long? = null) =
        SubmissionStatusResponse(
            lastattempt = AssignmentLastAttempt(
                submissionsenabled = true,
                canedit = canEdit,
                cansubmit = canSubmit,
                submission = AssignmentSubmission(status = status, timestarted = timestarted),
            ),
        )

    @Test
    fun `normal assignment is editable from not-submitted without start api`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(duedate = 2_000, configs = listOf(AssignmentConfig("onlinetext", "", "enabled", "1"))),
            status = status("new", canEdit = true, canSubmit = false),
            nowEpochSeconds = 1_000,
        )
        assertEquals(AssignmentSubmissionLabel.NotSubmitted, ui.submission)
        assertTrue(AssignmentAction.Edit in ui.actions)
        assertFalse("通常課題では開始APIを呼ばない", AssignmentAction.Start in ui.actions)
        assertFalse(ui.needsStart)
    }

    @Test
    fun `time limited assignment needs explicit start before editing`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(duedate = 2_000, timelimit = 600),
            status = status("new", canEdit = false, canSubmit = false),
            nowEpochSeconds = 1_000,
        )
        assertTrue(ui.hasTimeLimit)
        assertTrue(ui.needsStart)
        assertEquals(listOf(AssignmentAction.Start), ui.actions)

        val started = buildAssignmentUiModel(
            assignment = Assignment(duedate = 2_000, timelimit = 600),
            status = status("new", canEdit = true, canSubmit = true, timestarted = 1_500),
            nowEpochSeconds = 1_000,
        )
        assertFalse(started.needsStart)
        assertTrue(AssignmentAction.Edit in started.actions)
        assertFalse(AssignmentAction.Start in started.actions)
    }

    @Test
    fun `team submission is flagged unsupported with no native actions`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(duedate = 2_000, teamsubmission = 1),
            status = status("new", canEdit = true, canSubmit = true),
            nowEpochSeconds = 1_000,
        )
        assertTrue(ui.actions.isEmpty())
        assertTrue(ui.unsupportedReason!!.contains("グループ提出"))
    }

    @Test
    fun `submission statement text and rich text flag are exposed`() {
        val ui = buildAssignmentUiModel(
            assignment = Assignment(requiresubmissionstatement = 1, submissionstatement = "<p>これは自分の作品です</p>"),
            status = SubmissionStatusResponse(
                lastattempt = AssignmentLastAttempt(
                    canedit = true,
                    submission = AssignmentSubmission(
                        status = "draft",
                        plugins = listOf(
                            SubmissionPlugin(
                                type = "onlinetext",
                                editorfields = listOf(SubmissionEditorField(text = "<p>x <img src=\"a.png\"></p>")),
                            ),
                        ),
                    ),
                ),
            ),
            nowEpochSeconds = 1_000,
        )
        assertTrue(ui.submissionStatementRequired)
        assertEquals("<p>これは自分の作品です</p>", ui.submissionStatement)
        assertTrue(ui.onlineTextIsRich)
    }
}
