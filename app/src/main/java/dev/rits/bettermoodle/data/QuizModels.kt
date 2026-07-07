package dev.rits.bettermoodle.data

import kotlinx.serialization.Serializable

@Serializable
data class QuizzesResponse(
    val quizzes: List<Quiz> = emptyList(),
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class Quiz(
    val id: Long = 0,
    val coursemodule: Long = 0,
    val course: Long = 0,
    val name: String = "",
    val intro: String = "",
    val introformat: Int = 1,
    val timeopen: Long = 0,
    val timeclose: Long = 0,
    val timelimit: Long = 0,
    val attempts: Int = 0,
    val grademethod: Int = 0,
    val grade: Double = 0.0,
)

@Serializable
data class QuizAttemptsResponse(
    val attempts: List<QuizAttempt> = emptyList(),
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class QuizAttempt(
    val attempt: Int = 0,
    val state: String = "",
    val timestart: Long = 0,
    val timefinish: Long = 0,
    val sumgrades: Double? = null,
)

@Serializable
data class QuizBestGradeResponse(
    val hasgrade: Boolean = false,
    val grade: Double = 0.0,
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class QuizAccessInformationResponse(
    val canattempt: Boolean = false,
    val preventaccessreasons: List<String> = emptyList(),
    val warnings: List<WsWarning> = emptyList(),
)
