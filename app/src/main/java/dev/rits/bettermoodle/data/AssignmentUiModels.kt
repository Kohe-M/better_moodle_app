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
    /** 制限時間付き課題の計測開始 (mod_assign_start_submission)。通常課題では出さない */
    Start,
    Edit,
    FinalSubmit,
}

enum class PreviewKind {
    Pdf,
    Image,
    Text,
    Html,
    Unsupported,
}

data class SubmittedFileUi(
    val filename: String,
    /** draft内ディレクトリ ("/" または "/sub/")。同名ファイルの識別に使う */
    val filepath: String,
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
    /** 提出確認文 (HTML)。空なら確認文なし */
    val submissionStatement: String,
    /** 制限時間付き課題 */
    val hasTimeLimit: Boolean,
    /** 制限時間付きで、まだ計測開始していない */
    val needsStart: Boolean,
    val actions: List<AssignmentAction>,
    val files: List<SubmittedFileUi>,
    val onlineText: String?,
    /** 既存の文章が段落・改行以外の書式を含む (プレーンテキスト編集では書式が失われる) */
    val onlineTextIsRich: Boolean,
    /** グループ提出など、このアプリでは扱わない設定の課題 */
    val unsupportedReason: String?,
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
    val graded = last?.graded == true || hasNumericGrade(status.feedback?.grade?.grade)
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

    // 制限時間付き課題は開始APIで計測を始めるまで編集できない。通常課題は canedit だけで判定する
    // (mod_assign_start_submission は通常課題では timelimitnotenabled warning を返す)。
    val hasTimeLimit = assignment.hasTimeLimit
    val started = (last?.submission?.timestarted ?: 0L) > 0L
    val needsStart = hasTimeLimit && !started && submission == AssignmentSubmissionLabel.NotSubmitted
    val unsupportedReason = when {
        assignment.teamsubmission == 1 -> "グループ提出の課題はこのアプリから提出できません。Moodleで提出してください。"
        else -> null
    }

    val actions = buildList {
        if (unsupportedReason != null) return@buildList
        if (overdue && !canEdit && !canSubmit) return@buildList
        when {
            needsStart && last?.submissionsenabled == true -> add(AssignmentAction.Start)
            canEdit -> add(AssignmentAction.Edit)
        }
        if (canSubmit && submission != AssignmentSubmissionLabel.Submitted) add(AssignmentAction.FinalSubmit)
    }

    val onlineText = extractOnlineText(status).takeIf { it.isNotBlank() }
    return AssignmentUiModel(
        submission = submission,
        grading = grading,
        availability = availability,
        canEdit = canEdit,
        canSubmit = canSubmit,
        submissionStatementRequired = assignment.requiresubmissionstatement == 1 || assignment.submissionstatement.isNotBlank(),
        submissionStatement = assignment.submissionstatement,
        hasTimeLimit = hasTimeLimit,
        needsStart = needsStart,
        actions = actions,
        files = extractSubmittedFiles(status),
        onlineText = onlineText,
        onlineTextIsRich = OnlineTextFormat.isRichHtml(onlineText),
        unsupportedReason = unsupportedReason,
    )
}

fun extractOnlineText(status: SubmissionStatusResponse): String =
    status.lastattempt?.submission?.plugins
        ?.firstOrNull { it.type == "onlinetext" }
        ?.editorfields
        ?.firstOrNull { it.text.isNotBlank() }
        ?.text
        .orEmpty()

fun hasNumericGrade(grade: String?): Boolean =
    grade?.trim()?.toDoubleOrNull()?.let { it >= 0.0 } == true

/** 提出済みファイル (draft引き継ぎ用の生DTO) */
fun extractSubmittedMoodleFiles(status: SubmissionStatusResponse): List<MoodleFile> =
    status.lastattempt?.submission?.plugins
        .orEmpty()
        .filter { it.type == "file" }
        .flatMap { it.fileareas }
        .flatMap { it.files }

fun extractSubmittedFiles(status: SubmissionStatusResponse): List<SubmittedFileUi> =
    extractSubmittedMoodleFiles(status)
        .map { file ->
            SubmittedFileUi(
                filename = file.filename,
                filepath = MoodleClient.normalizeDraftPath(file.filepath),
                typeLabel = file.mimetype?.substringAfter('/')?.uppercase() ?: "FILE",
                sizeBytes = file.filesize ?: -1L,
                modifiedAt = file.timemodified,
                sourceUrl = file.fileurl,
                mimeType = file.mimetype,
                previewKind = previewKindFor(file.filename, file.mimetype),
            )
        }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

/**
 * ファイル名と MIME からプレビュー種別を決める。MIME が無い (HTML内リンク等) 場合は拡張子で判定する。
 * 実際の応答種別 (Content-Type) の検証はプレビュー画面側で行う。
 */
fun previewKindFor(filename: String, mimeType: String?): PreviewKind {
    val lower = filename.lowercase()
    val extension = lower.substringAfterLast('.', missingDelimiterValue = "")
    return when {
        mimeType == "application/pdf" || lower.endsWith(".pdf") -> PreviewKind.Pdf
        mimeType.orEmpty().startsWith("image/") || (mimeType.isNullOrBlank() && extension in IMAGE_EXTENSIONS) -> PreviewKind.Image
        mimeType == "text/html" || lower.endsWith(".html") || lower.endsWith(".htm") -> PreviewKind.Html
        mimeType.orEmpty().startsWith("text/") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".csv") ||
            lower.endsWith(".md") -> PreviewKind.Text
        else -> PreviewKind.Unsupported
    }
}
