package dev.rits.bettermoodle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SiteInfo(
    val sitename: String = "",
    val username: String = "",
    val firstname: String = "",
    val lastname: String = "",
    val fullname: String = "",
    val userid: Long = 0,
    val userpictureurl: String = "",
)

@Serializable
data class DashboardBlocksResponse(
    val blocks: List<DashboardBlock> = emptyList(),
)

@Serializable
data class DashboardBlock(
    val instanceid: Long = 0,
    val name: String = "",
    val region: String = "",
    val visible: Boolean? = null,
    val contents: BlockContents? = null,
)

@Serializable
data class BlockContents(
    val title: String = "",
    val content: String = "",
    val footer: String = "",
)

@Serializable
data class ActionEventsResponse(
    val events: List<ActionEvent> = emptyList(),
    val firstid: Long = 0,
    val lastid: Long = 0,
)

@Serializable
data class ActionEvent(
    val id: Long = 0,
    val name: String = "",
    val activityname: String? = null,
    val modulename: String? = null,
    val eventtype: String? = null,
    val timesort: Long = 0,
    val timestart: Long = 0,
    val course: EventCourse? = null,
    val url: String? = null,
    val action: EventAction? = null,
    val formattedtime: String? = null,
)

@Serializable
data class EventCourse(
    val id: Long = 0,
    val fullname: String = "",
    val shortname: String = "",
)

@Serializable
data class EventAction(
    val name: String = "",
    val url: String = "",
    val actionable: Boolean = false,
)

@Serializable
data class NotificationsResponse(
    val notifications: List<PopupNotification> = emptyList(),
    val unreadcount: Int = 0,
)

@Serializable
data class PopupNotification(
    val id: Long = 0,
    val subject: String = "",
    val smallmessage: String? = null,
    val fullmessagehtml: String? = null,
    val contexturl: String? = null,
    val contexturlname: String? = null,
    val timecreated: Long = 0,
    val read: Boolean = false,
    val component: String? = null,
    val eventtype: String? = null,
    val customdata: String? = null,
)

@Serializable
data class CoursesByTimelineResponse(
    val courses: List<Course> = emptyList(),
    val nextoffset: Int = 0,
)

@Serializable
data class Course(
    val id: Long = 0,
    val fullname: String = "",
    val shortname: String = "",
    val coursecategory: String? = null,
    val viewurl: String? = null,
    val hidden: Boolean? = null,
) {
    /** コース名先頭の5桁授業コード ("12345:○○概論" 形式) */
    val courseCode: String?
        get() = COURSE_CODE_REGEX.find(fullname)?.groupValues?.get(1)

    /** "12345:" プレフィックスと "§..." 以降を除いた表示用の短い名前 */
    val displayName: String
        get() = fullname
            .replace(Regex("^\\d+\\s*[:：]\\s*"), "")
            .substringBefore('§')
            .trim()

    companion object {
        val COURSE_CODE_REGEX = Regex("(?:^|[^\\d])(\\d{5})(?=\\s*[:：])")
    }
}

/** 時間割の1コマ */
data class TimetableEntry(
    val dayIndex: Int,          // 0=月 ... 6=日
    val period: Int,            // 1..7
    val title: String,
    val courseCode: String?,    // 5桁授業コード
    val courseUrl: String?,     // Moodleコースへのリンク
    val room: String? = null,   // 教室名
) {
    /** courseUrl (course/view.php?id=NN) からコースIDを取り出す */
    val courseId: Long?
        get() = courseUrl?.let { Regex("[?&]id=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
}

data class Timetable(
    val entries: List<TimetableEntry>,
    val dayLabels: List<String>,
) {
    companion object {
        val EMPTY = Timetable(emptyList(), emptyList())

        /** 立命館の時限と時刻 */
        val PERIOD_TIMES = mapOf(
            1 to ("9:00" to "10:35"),
            2 to ("10:45" to "12:20"),
            3 to ("13:10" to "14:45"),
            4 to ("14:55" to "16:30"),
            5 to ("16:40" to "18:15"),
            6 to ("18:25" to "20:00"),
            7 to ("20:10" to "21:45"),
        )
    }
}

/** Moodle WSのエラー (HTTP 200で返る) */
@Serializable
data class WsError(
    val exception: String? = null,
    val errorcode: String? = null,
    val message: String? = null,
    @SerialName("debuginfo") val debugInfo: String? = null,
)

class MoodleWsException(val errorCode: String?, message: String) : Exception(message)

class MoodleHttpException(val status: Int) : Exception("HTTP $status")

class MoodleResponseParseException : Exception("Moodle response parse error")
