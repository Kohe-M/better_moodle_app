package dev.rits.bettermoodle.data

import kotlinx.serialization.Serializable

/** mod_page_get_pages_by_courses のレスポンス */
@Serializable
data class PagesResponse(
    val pages: List<MoodlePage> = emptyList(),
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class MoodlePage(
    val id: Long = 0,
    val coursemodule: Long = 0,
    val course: Long = 0,
    val name: String = "",
    val intro: String = "",
    val content: String = "",
    val revision: Long = 0,
    val contentfiles: List<MoodleFile> = emptyList(),
)
