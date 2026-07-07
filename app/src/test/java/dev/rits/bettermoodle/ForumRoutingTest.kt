package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.CourseModule
import dev.rits.bettermoodle.data.ForumDiscussion
import dev.rits.bettermoodle.data.ForumLoadErrorKind
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.MoodleResponseParseException
import dev.rits.bettermoodle.data.MoodleWsException
import dev.rits.bettermoodle.data.classifyForumLoadError
import dev.rits.bettermoodle.data.diagnosticCode
import dev.rits.bettermoodle.data.discussionIdForPosts
import dev.rits.bettermoodle.data.forumLoadErrorMessage
import dev.rits.bettermoodle.data.toForumTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumRoutingTest {

    @Test
    fun `forum module uses instance not course module id`() {
        val module = CourseModule(id = 101, instance = 202, contextId = 303, modName = "forum")

        val target = module.toForumTarget(courseId = 77)
        assertEquals(101L, target.courseModuleId)
        assertEquals(202L, target.forumInstanceId)
        assertEquals(77L, target.courseId)
        assertEquals(303L, target.contextId)

        val params = MoodleRepository.forumDiscussionsParams(target.forumInstanceId)
        assertEquals("202", params["forumid"])
        assertFalse(params.values.contains("101"))
    }

    @Test
    fun `forum discussion list parameter uses forum instance id`() {
        val params = MoodleRepository.forumDiscussionsParams(202)

        assertEquals("202", params["forumid"])
        assertEquals("timemodified DESC", params["sortorder"])
    }

    @Test
    fun `discussion posts use discussion id from list response only`() {
        val discussion = ForumDiscussion(id = 303, discussion = 404)

        assertEquals(303L, discussion.discussionIdForPosts())
        assertEquals("303", MoodleRepository.forumPostsParams(discussion.discussionIdForPosts())["discussionid"])
        assertFalse(MoodleRepository.forumPostsParams(discussion.discussionIdForPosts()).values.contains("404"))
    }

    @Test
    fun `forum module without instance does not produce api id`() {
        assertFalse(CourseModule(id = 101, instance = 0, modName = "forum").toForumTarget(77).isValid)
        assertFalse(CourseModule(id = 101, instance = -1, modName = "forum").toForumTarget(77).isValid)
        assertFalse(CourseModule(id = 101, instance = 202, modName = "assign").toForumTarget(77).isValid)
    }

    @Test
    fun `course module decodes instance from core course contents json`() {
        val module = Json.decodeFromString<CourseModule>(
            """{"id":101,"instance":202,"contextid":303,"modname":"forum","name":"news"}""",
        )

        val target = module.toForumTarget(courseId = 77)
        assertEquals(101L, target.courseModuleId)
        assertEquals(202L, target.forumInstanceId)
        assertEquals(303L, target.contextId)
        assertTrue(target.isValid)
    }

    @Test
    fun `course module with null instance does not call forum api`() {
        val module = Json.decodeFromString<CourseModule>(
            """{"id":101,"instance":null,"contextid":303,"modname":"forum","name":"news"}""",
        )

        val target = module.toForumTarget(courseId = 77)
        assertEquals(101L, target.courseModuleId)
        assertEquals(0L, target.forumInstanceId)
        assertFalse(target.isValid)
    }

    @Test
    fun `initial forum load failure message does not mention posting`() {
        val message = forumLoadErrorMessage(ForumLoadErrorKind.InvalidParameter)

        assertTrue(message.contains("フォーラム"))
        assertFalse(message.contains("Posting"))
        assertFalse(message.contains("新規投稿"))
        assertFalse(message.contains("返信"))
    }

    @Test
    fun `forum api errors are classified without exposing parameters`() {
        assertEquals(
            ForumLoadErrorKind.InvalidParameter,
            classifyForumLoadError(MoodleWsException("invalidparameter", "bad value")),
        )
        assertEquals(
            ForumLoadErrorKind.InvalidRecord,
            classifyForumLoadError(MoodleWsException("invalidrecord", "not found")),
        )
        assertEquals(
            ForumLoadErrorKind.AccessDenied,
            classifyForumLoadError(MoodleWsException("accessexception", "denied")),
        )
        assertEquals(
            ForumLoadErrorKind.FunctionUnavailable,
            classifyForumLoadError(MoodleWsException("servicenotavailable", "disabled")),
        )
        assertEquals(
            ForumLoadErrorKind.ResponseParse,
            classifyForumLoadError(MoodleResponseParseException()),
        )
        assertEquals("INVALID_PARAMETER", ForumLoadErrorKind.InvalidParameter.diagnosticCode())
        assertEquals("FUNCTION_UNAVAILABLE", ForumLoadErrorKind.FunctionUnavailable.diagnosticCode())
    }
}
