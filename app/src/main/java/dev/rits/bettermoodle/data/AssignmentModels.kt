package dev.rits.bettermoodle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssignmentsResponse(
    val courses: List<AssignmentCourse> = emptyList(),
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class AssignmentCourse(
    val id: Long = 0,
    val fullname: String = "",
    val shortname: String = "",
    val assignments: List<Assignment> = emptyList(),
)

@Serializable
data class Assignment(
    val id: Long = 0,
    val cmid: Long = 0,
    val course: Long = 0,
    val name: String = "",
    val intro: String = "",
    val introformat: Int = 1,
    val duedate: Long = 0,
    val allowsubmissionsfromdate: Long = 0,
    val cutoffdate: Long = 0,
    val gradingduedate: Long = 0,
    val submissiondrafts: Int = 0,
    val submissionstatement: String = "",
    val configs: List<AssignmentConfig> = emptyList(),
) {
    fun supports(plugin: String): Boolean =
        configs.any { it.plugin == plugin && it.name == "enabled" && it.value == "1" }
}

@Serializable
data class AssignmentConfig(
    val plugin: String = "",
    val subtype: String = "",
    val name: String = "",
    val value: String = "",
)

@Serializable
data class SubmissionStatusResponse(
    val lastattempt: AssignmentLastAttempt? = null,
    val feedback: AssignmentFeedback? = null,
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class AssignmentLastAttempt(
    val submission: AssignmentSubmission? = null,
    val submissionsenabled: Boolean = false,
    val locked: Boolean = false,
    val graded: Boolean = false,
    val canedit: Boolean = false,
    val cansubmit: Boolean = false,
    val gradingstatus: String = "",
)

@Serializable
data class AssignmentSubmission(
    val id: Long = 0,
    val userid: Long = 0,
    val attemptnumber: Int = 0,
    val timecreated: Long = 0,
    val timemodified: Long = 0,
    val status: String = "",
    val plugins: List<SubmissionPlugin> = emptyList(),
)

@Serializable
data class SubmissionPlugin(
    val type: String = "",
    val name: String = "",
    val fileareas: List<SubmissionFileArea> = emptyList(),
    val editorfields: List<SubmissionEditorField> = emptyList(),
)

@Serializable
data class SubmissionFileArea(
    val area: String = "",
    val files: List<MoodleFile> = emptyList(),
)

@Serializable
data class SubmissionEditorField(
    val name: String = "",
    val description: String = "",
    val text: String = "",
    val format: Int = 1,
)

@Serializable
data class AssignmentFeedback(
    val grade: AssignmentGrade? = null,
    val plugins: List<SubmissionPlugin> = emptyList(),
)

@Serializable
data class AssignmentGrade(
    val id: Long = 0,
    val grade: String = "",
    val grader: Long = 0,
    val timemodified: Long = 0,
)

@Serializable
data class MoodleFile(
    val filename: String = "",
    val filepath: String = "",
    val filesize: Long = 0,
    val fileurl: String? = null,
    val mimetype: String? = null,
    val timemodified: Long = 0,
)

@Serializable
data class UploadedFile(
    val component: String = "",
    val contextid: Long = 0,
    val userid: Long = 0,
    val filearea: String = "",
    val filename: String = "",
    val filepath: String = "",
    val itemid: Long = 0,
    val license: String = "",
    val author: String = "",
    val source: String = "",
)

@Serializable
data class WsWarning(
    val item: String? = null,
    val itemid: Long? = null,
    val warningcode: String = "",
    val message: String = "",
)
