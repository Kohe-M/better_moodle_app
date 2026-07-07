package dev.rits.bettermoodle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** core_course_get_contents のセクション */
@Serializable
data class CourseSection(
    val id: Long = 0,
    val name: String = "",
    val summary: String = "",
    val section: Int = 0,
    val visible: Int = 1,
    val uservisible: Boolean = true,
    val modules: List<CourseModule> = emptyList(),
)

/** セクション内の1アクティビティ (課題・資料・URL・フォーラム等) */
@Serializable
data class CourseModule(
    val id: Long = 0,
    val name: String = "",
    val url: String? = null,
    @SerialName("modname") val modName: String = "",
    @SerialName("modplural") val modPlural: String = "",
    val modicon: String? = null,
    val description: String? = null,
    val uservisible: Boolean = true,
    val availabilityinfo: String? = null,
    val dates: List<ModuleDate> = emptyList(),
    val contents: List<ModuleContent> = emptyList(),
) {
    /** このモジュール内でプレビュー可能なPDFファイル (なければnull) */
    val pdfContent: ModuleContent?
        get() = contents.firstOrNull {
            it.mimetype == "application/pdf" ||
                it.filename?.endsWith(".pdf", ignoreCase = true) == true
        }

    val downloadableFiles: List<ModuleContent>
        get() = contents.filter { it.type == "file" && !it.fileurl.isNullOrBlank() }
}

@Serializable
data class ModuleDate(
    val label: String = "",
    val timestamp: Long = 0,
)

@Serializable
data class ModuleContent(
    val type: String = "",
    val filename: String? = null,
    val filepath: String? = null,
    val filesize: Long = 0,
    val fileurl: String? = null,
    val mimetype: String? = null,
    val timemodified: Long = 0,
)

/** tool_mobile_get_autologin_key の結果 */
@Serializable
data class AutologinKey(
    val key: String = "",
    val autologinurl: String = "",
)
