package dev.rits.bettermoodle.data

enum class QuizLoadErrorKind {
    InvalidQuizTarget,
    InvalidParameter,
    InvalidRecord,
    AccessDenied,
    FunctionUnavailable,
    Network,
    ResponseParse,
    Other,
}

data class QuizTarget(
    val courseId: Long,
    val courseModuleId: Long,
    val quizInstanceId: Long,
    val contextId: Long?,
    val modName: String,
    val url: String?,
) {
    val isValid: Boolean
        get() = modName == "quiz" && courseId > 0L && courseModuleId > 0L
}

fun CourseModule.toQuizTarget(courseId: Long): QuizTarget =
    QuizTarget(
        courseId = courseId,
        courseModuleId = id,
        quizInstanceId = instance ?: 0L,
        contextId = contextId.takeIf { it > 0L },
        modName = modName,
        url = url,
    )

fun classifyQuizLoadError(error: Throwable): QuizLoadErrorKind {
    val code = (error as? MoodleWsException)?.errorCode?.lowercase()
    return when (code) {
        "invalidparameter" -> QuizLoadErrorKind.InvalidParameter
        "invalidrecord" -> QuizLoadErrorKind.InvalidRecord
        "accessexception" -> QuizLoadErrorKind.AccessDenied
        "servicenotavailable",
        "functionnotfound",
        "invalidfunction",
        "disabledfunction",
        "missingfunction",
        -> QuizLoadErrorKind.FunctionUnavailable
        null -> when (error) {
            is MoodleResponseParseException -> QuizLoadErrorKind.ResponseParse
            is MoodleHttpException -> QuizLoadErrorKind.Network
            is java.io.IOException -> QuizLoadErrorKind.Network
            else -> QuizLoadErrorKind.Other
        }
        else -> QuizLoadErrorKind.Other
    }
}

fun quizLoadErrorMessage(kind: QuizLoadErrorKind): String =
    when (kind) {
        QuizLoadErrorKind.FunctionUnavailable,
        QuizLoadErrorKind.AccessDenied,
        -> "このMoodle環境では小テストの閲覧APIを利用できません。Moodle画面で確認してください。"
        else -> "小テストを読み込めませんでした。再試行してください。"
    }

fun quizHttpStatus(error: Throwable): Int? =
    (error as? MoodleHttpException)?.status

fun quizMoodleErrorCode(error: Throwable): String? =
    (error as? MoodleWsException)?.errorCode

fun QuizLoadErrorKind.canFallbackToWebView(): Boolean =
    this == QuizLoadErrorKind.FunctionUnavailable || this == QuizLoadErrorKind.AccessDenied

fun QuizLoadErrorKind.diagnosticCode(): String =
    when (this) {
        QuizLoadErrorKind.InvalidQuizTarget -> "INVALID_QUIZ_TARGET"
        QuizLoadErrorKind.InvalidParameter -> "INVALID_PARAMETER"
        QuizLoadErrorKind.InvalidRecord -> "INVALID_RECORD"
        QuizLoadErrorKind.AccessDenied -> "ACCESS_DENIED"
        QuizLoadErrorKind.FunctionUnavailable -> "FUNCTION_UNAVAILABLE"
        QuizLoadErrorKind.Network -> "NETWORK"
        QuizLoadErrorKind.ResponseParse -> "RESPONSE_PARSE"
        QuizLoadErrorKind.Other -> "UNKNOWN"
    }
