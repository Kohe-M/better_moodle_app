package dev.rits.bettermoodle.data

fun quizGradeMethodLabel(method: Int): String =
    when (method) {
        1 -> "最高評点"
        2 -> "平均評点"
        3 -> "最初の受験"
        4 -> "最後の受験"
        else -> "未設定"
    }

fun quizTimeLimitLabel(timelimitSeconds: Long): String =
    if (timelimitSeconds <= 0L) {
        "なし"
    } else {
        "${(timelimitSeconds + 59L) / 60L}分"
    }

fun quizAttemptsLimitLabel(attempts: Int): String =
    if (attempts == 0) "無制限" else "${attempts}回"

fun quizAttemptStateLabel(state: String): String =
    when (state.lowercase()) {
        "inprogress" -> "受験中"
        "finished" -> "完了"
        else -> state.ifBlank { "不明" }
    }

fun quizGradeLabel(grade: Double): String {
    val longValue = grade.toLong()
    return if (grade == longValue.toDouble()) longValue.toString() else grade.toString()
}
