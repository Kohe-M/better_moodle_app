package dev.rits.bettermoodle.data

enum class ForumLoadErrorKind {
    InvalidForumTarget,
    InvalidParameter,
    InvalidRecord,
    AccessDenied,
    FunctionUnavailable,
    Network,
    ResponseParse,
    Other,
}

data class ForumTarget(
    val courseId: Long,
    val courseModuleId: Long,
    val forumInstanceId: Long,
    val contextId: Long?,
    val modName: String,
    val url: String?,
) {
    val isValid: Boolean
        get() = modName == "forum" && forumInstanceId > 0L
}

fun CourseModule.toForumTarget(courseId: Long): ForumTarget =
    ForumTarget(
        courseId = courseId,
        courseModuleId = id,
        forumInstanceId = instance ?: 0L,
        contextId = contextId.takeIf { it > 0L },
        modName = modName,
        url = url,
    )

fun ForumDiscussion.discussionIdForPosts(): Long =
    id

fun classifyForumLoadError(error: Throwable): ForumLoadErrorKind {
    val code = (error as? MoodleWsException)?.errorCode?.lowercase()
    return when (code) {
        "invalidparameter" -> ForumLoadErrorKind.InvalidParameter
        "invalidrecord" -> ForumLoadErrorKind.InvalidRecord
        "accessexception" -> ForumLoadErrorKind.AccessDenied
        "servicenotavailable",
        "functionnotfound",
        "invalidfunction",
        "disabledfunction",
        "missingfunction",
        -> ForumLoadErrorKind.FunctionUnavailable
        null -> when (error) {
            is MoodleResponseParseException -> ForumLoadErrorKind.ResponseParse
            is MoodleHttpException -> ForumLoadErrorKind.Network
            is java.io.IOException -> ForumLoadErrorKind.Network
            else -> ForumLoadErrorKind.Other
        }
        else -> ForumLoadErrorKind.Other
    }
}

fun forumLoadErrorMessage(kind: ForumLoadErrorKind): String =
    when (kind) {
        ForumLoadErrorKind.FunctionUnavailable,
        ForumLoadErrorKind.AccessDenied,
        -> "このMoodle環境ではフォーラムのネイティブ閲覧APIが利用できません。"
        else -> "フォーラムを読み込めませんでした。再試行してください"
    }

fun forumHttpStatus(error: Throwable): Int? =
    (error as? MoodleHttpException)?.status

fun forumMoodleErrorCode(error: Throwable): String? =
    (error as? MoodleWsException)?.errorCode

fun ForumLoadErrorKind.canFallbackToWebView(): Boolean =
    this == ForumLoadErrorKind.FunctionUnavailable || this == ForumLoadErrorKind.AccessDenied

fun ForumLoadErrorKind.diagnosticCode(): String =
    when (this) {
        ForumLoadErrorKind.InvalidForumTarget -> "INVALID_FORUM_TARGET"
        ForumLoadErrorKind.InvalidParameter -> "INVALID_PARAMETER"
        ForumLoadErrorKind.InvalidRecord -> "INVALID_RECORD"
        ForumLoadErrorKind.AccessDenied -> "ACCESS_DENIED"
        ForumLoadErrorKind.FunctionUnavailable -> "FUNCTION_UNAVAILABLE"
        ForumLoadErrorKind.Network -> "NETWORK"
        ForumLoadErrorKind.ResponseParse -> "RESPONSE_PARSE"
        ForumLoadErrorKind.Other -> "UNKNOWN"
    }
