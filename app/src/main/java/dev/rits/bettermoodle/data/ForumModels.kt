package dev.rits.bettermoodle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ForumDiscussionsResponse(
    val discussions: List<ForumDiscussion> = emptyList(),
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class ForumDiscussion(
    val id: Long = 0,
    val discussion: Long = 0,
    val name: String = "",
    val subject: String = "",
    val message: String = "",
    val messageformat: Int = 1,
    val userfullname: String = "",
    val created: Long = 0,
    val modified: Long = 0,
    val timemodified: Long = 0,
    val numreplies: Int = 0,
    val numunread: Int = 0,
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val canreply: Boolean = false,
)

@Serializable
data class ForumPostsResponse(
    val posts: List<ForumPost> = emptyList(),
    val warnings: List<WsWarning> = emptyList(),
)

@Serializable
data class ForumPost(
    val id: Long = 0,
    val discussionid: Long = 0,
    val parentid: Long = 0,
    val subject: String = "",
    val message: String = "",
    val messageformat: Int = 1,
    val author: ForumAuthor? = null,
    val timecreated: Long = 0,
    val unread: Boolean? = null,
)

@Serializable
data class ForumAuthor(
    val id: Long = 0,
    val fullname: String = "",
)
