package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.CourseModule
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.MoodleResponseParseException
import dev.rits.bettermoodle.data.MoodleWsException
import dev.rits.bettermoodle.data.QuizLoadErrorKind
import dev.rits.bettermoodle.data.classifyQuizLoadError
import dev.rits.bettermoodle.data.diagnosticCode
import dev.rits.bettermoodle.data.quizAttemptsLimitLabel
import dev.rits.bettermoodle.data.quizGradeMethodLabel
import dev.rits.bettermoodle.data.quizTimeLimitLabel
import dev.rits.bettermoodle.data.toQuizTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizRoutingTest {

    @Test
    fun `quiz module keeps course module id separate from instance id`() {
        val module = CourseModule(id = 101, instance = 202, contextId = 303, modName = "quiz")

        val target = module.toQuizTarget(courseId = 77)

        assertEquals(101L, target.courseModuleId)
        assertEquals(202L, target.quizInstanceId)
        assertEquals(77L, target.courseId)
        assertEquals(303L, target.contextId)
        assertTrue(target.isValid)
    }

    @Test
    fun `quiz module without instance still carries course module target`() {
        val target = CourseModule(id = 101, instance = null, modName = "quiz").toQuizTarget(77)

        assertEquals(101L, target.courseModuleId)
        assertEquals(0L, target.quizInstanceId)
        assertTrue(target.isValid)
    }

    @Test
    fun `non quiz module is not a valid quiz target`() {
        assertFalse(CourseModule(id = 101, instance = 202, modName = "assign").toQuizTarget(77).isValid)
        assertFalse(CourseModule(id = 101, instance = 202, modName = "quiz").toQuizTarget(0).isValid)
        assertFalse(CourseModule(id = 0, instance = 202, modName = "quiz").toQuizTarget(77).isValid)
    }

    @Test
    fun `course module decodes quiz instance from core course contents json`() {
        val module = Json.decodeFromString<CourseModule>(
            """{"id":101,"instance":202,"contextid":303,"modname":"quiz","name":"quiz"}""",
        )

        val target = module.toQuizTarget(courseId = 77)

        assertEquals(101L, target.courseModuleId)
        assertEquals(202L, target.quizInstanceId)
        assertEquals(303L, target.contextId)
        assertTrue(target.isValid)
    }

    @Test
    fun `quiz api params use only documented keys`() {
        assertEquals(
            mapOf("courseids[0]" to "77"),
            MoodleRepository.quizzesByCoursesParams(77),
        )
        assertEquals(
            mapOf("quizid" to "202", "status" to "all"),
            MoodleRepository.quizUserAttemptsParams(202),
        )
        assertEquals(
            mapOf("quizid" to "202"),
            MoodleRepository.quizBestGradeParams(202),
        )
        assertEquals(
            mapOf("quizid" to "202"),
            MoodleRepository.quizAccessInformationParams(202),
        )
    }

    @Test
    fun `quizzes by courses params do not include inferred optional params`() {
        val params = MoodleRepository.quizzesByCoursesParams(77)

        assertFalse(params.containsKey("cmid"))
        assertFalse(params.containsKey("quizid"))
        assertFalse(params.containsKey("sortorder"))
        assertFalse(params.values.contains("101"))
    }

    @Test
    fun `quiz api errors are classified without exposing parameters`() {
        assertEquals(
            QuizLoadErrorKind.InvalidParameter,
            classifyQuizLoadError(MoodleWsException("invalidparameter", "bad value")),
        )
        assertEquals(
            QuizLoadErrorKind.InvalidRecord,
            classifyQuizLoadError(MoodleWsException("invalidrecord", "not found")),
        )
        assertEquals(
            QuizLoadErrorKind.AccessDenied,
            classifyQuizLoadError(MoodleWsException("accessexception", "denied")),
        )
        assertEquals(
            QuizLoadErrorKind.FunctionUnavailable,
            classifyQuizLoadError(MoodleWsException("servicenotavailable", "disabled")),
        )
        assertEquals(
            QuizLoadErrorKind.ResponseParse,
            classifyQuizLoadError(MoodleResponseParseException()),
        )
        assertEquals("INVALID_PARAMETER", QuizLoadErrorKind.InvalidParameter.diagnosticCode())
        assertEquals("FUNCTION_UNAVAILABLE", QuizLoadErrorKind.FunctionUnavailable.diagnosticCode())
    }

    @Test
    fun `quiz label helpers render Japanese labels`() {
        assertEquals("最高評点", quizGradeMethodLabel(1))
        assertEquals("平均評点", quizGradeMethodLabel(2))
        assertEquals("最初の受験", quizGradeMethodLabel(3))
        assertEquals("最後の受験", quizGradeMethodLabel(4))
        assertEquals("未設定", quizGradeMethodLabel(99))

        assertEquals("なし", quizTimeLimitLabel(0))
        assertEquals("1分", quizTimeLimitLabel(1))
        assertEquals("2分", quizTimeLimitLabel(61))

        assertEquals("無制限", quizAttemptsLimitLabel(0))
        assertEquals("3回", quizAttemptsLimitLabel(3))
    }
}
