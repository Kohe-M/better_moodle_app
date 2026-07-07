package dev.rits.bettermoodle.data

import java.time.Instant

enum class AssignmentSubmissionLabel(val text: String) {
    NotSubmitted("未提出"),
    Draft("下書き保存済み"),
    Submitted("提出済み"),
    Unknown("状態を確認できません"),
}

enum class AssignmentGradingLabel(val text: String) {
    NotMarked("未採点"),
    Graded("採点済み"),
    Unknown("状態を確認できません"),
}

enum class AssignmentAvailabilityLabel(val text: String) {
    BeforeOpen("受付前"),
    Open("受付中"),
    DueSoon("期限まで残り24時間未満"),
    Overdue("期限切れ"),
    Submitted("提出済み"),
    Resubmittable("再提出可能"),
    Graded("採点済み"),
    Unknown("状態を確認できません"),
}

enum class AssignmentAction {
    Start,
    Edit,
    FinalSubmit,
}

enum class PreviewKind {
    Pdf,
    Image,
    Text,
    Unsupported,
}

data class SubmittedFileUi(
    val filename: String,
    val typeLabel: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val sourceUrl: String?,
    val mimeType: String?,
    val previewKind: PreviewKind,
)

data class AssignmentUiModel(
    val submission: AssignmentSubmissionLabel,
    val grading: AssignmentGradingLabel,
    val availability: AssignmentAvailabilityLabel,
    val canEdit: Boolean,
    val canSubmit: Boolean,
    val submissionStatementRequired: Boolean,
    val actions: List<AssignmentAction>,
    val files: List<SubmittedFileUi>,
    val onlineText: String?,
)

fun buildAssignmentUiModel(
    assignment: Assignment,
    status: SubmissionStatusResponse,
    nowEpochSeconds: Long = Instant.now().epochSecond,
): AssignmentUiModel {
    val last = status.lastattempt
    val submissionStatus = last?.submission?.status.orEmpty().lowercase()
    val isSubmitted = submissionStatus == "submitted"
    val isDraft = submissionStatus == "draft"
    val canEdit = last?.canedit == true
    val canSubmit = last?.cansubmit == true
    val graded = last?.graded == true || status.feedback?.grade?.grade?.isNotBlank() == true
    val overdue = assignment.duedate > 0L && nowEpochSeconds > assignment.duedate
    val beforeOpen = assignment.allowsubmissionsfromdate > 0L && nowEpochSeconds < assignment.allowsubmissionsfromdate
    val dueSoon = assignment.duedate > 0L && assignment.duedate - nowEpochSeconds in 0 until 24 * 3600

    val submission = when {
        isSubmitted -> AssignmentSubmissionLabel.Submitted
        isDraft -> AssignmentSubmissionLabel.Draft
        submissionStatus.isBlank() || submissionStatus == "new" -> AssignmentSubmissionLabel.NotSubmitted
        else -> AssignmentSubmissionLabel.Unknown
    }
    val grading = when {
        graded -> AssignmentGradingLabel.Graded
        last?.gradingstatus.orEmpty().lowercase() in setOf("notmarked", "notgraded", "") -> AssignmentGradingLabel.NotMarked
        else -> AssignmentGradingLabel.Unknown
    }
    val availability = when {
        graded -> AssignmentAvailabilityLabel.Graded
        isSubmitted && canEdit -> AssignmentAvailabilityLabel.Resubmittable
        isSubmitted -> AssignmentAvailabilityLabel.Submitted
        beforeOpen -> AssignmentAvailabilityLabel.BeforeOpen
        overdue && !canEdit && !canSubmit -> AssignmentAvailabilityLabel.Overdue
        dueSoon -> AssignmentAvailabilityLabel.DueSoon
        canEdit || canSubmit || last?.submissionsenabled == true -> AssignmentAvailabilityLabel.Open
        else -> AssignmentAvailabilityLabel.Unknown
    }

    val actions = buildList {
        if (overdue && !canEdit && !canSubmit) return@buildList
        when {
            submission == AssignmentSubmissionLabel.NotSubmitted && last?.submissionsenabled == true ->
                add(AssignmentAction.Start)
            submission == AssignmentSubmissionLabel.Draft && canEdit ->
                add(AssignmentAction.Edit)
            submission == AssignmentSubmissionLabel.Submitted && canEdit ->
                add(AssignmentAction.Edit)
        }
        if (canSubmit && submission != AssignmentSubmissionLabel.Submitted) add(AssignmentAction.FinalSubmit)
    }

    return AssignmentUiModel(
        submission = submission,
        grading = grading,
        availability = availability,
        canEdit = canEdit,
        canSubmit = canSubmit,
        submissionStatementRequired = assignment.submissionstatement.isNotBlank(),
        actions = actions,
        files = extractSubmittedFiles(status),
        onlineText = extractOnlineText(status).takeIf { it.isNotBlank() },
    )
}

fun extractOnlineText(status: SubmissionStatusResponse): String =
    status.lastattempt?.submission?.plugins
        ?.firstOrNull { it.type == "onlinetext" }
        ?.editorfields
        ?.firstOrNull { it.text.isNotBlank() }
        ?.text
        .orEmpty()

fun extractSubmittedFiles(status: SubmissionStatusResponse): List<SubmittedFileUi> =
    status.lastattempt?.submission?.plugins
        .orEmpty()
        .filter { it.type == "file" }
        .flatMap { it.fileareas }
        .flatMap { it.files }
        .map { file ->
            SubmittedFileUi(
                filename = file.filename,
                typeLabel = file.mimetype?.substringAfter('/')?.uppercase() ?: "FILE",
                sizeBytes = file.filesize,
                modifiedAt = file.timemodified,
                sourceUrl = file.fileurl,
                mimeType = file.mimetype,
                previewKind = previewKindFor(file.filename, file.mimetype),
            )
        }

fun previewKindFor(filename: String, mimeType: String?): PreviewKind {
    val lower = filename.lowercase()
    return when {
        mimeType == "application/pdf" || lower.endsWith(".pdf") -> PreviewKind.Pdf
        mimeType.orEmpty().startsWith("image/") -> PreviewKind.Image
        mimeType.orEmpty().startsWith("text/") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".csv") ||
            lower.endsWith(".md") -> PreviewKind.Text
        else -> PreviewKind.Unsupported
    }
}
