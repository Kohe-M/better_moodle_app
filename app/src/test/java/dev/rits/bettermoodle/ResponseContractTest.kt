package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.AssignmentSubmissionRejectedException
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.MoodleResponseParseException
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.data.SyllabusSearchResult
import dev.rits.bettermoodle.data.previewKindFor
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * N08 / T08: 不正応答を「成功」「0件」「通信失敗」に丸めない。
 * N07 / T07: 拡張子だけの画像判定。
 */
class ResponseContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: MoodleRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = MoodleRepository(
            MoodleClient(siteUrl = server.url("/").toString().trimEnd('/'), tokenProvider = { "t" }),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun expectParseError(block: suspend () -> Unit) {
        try {
            block()
            fail("malformed response was accepted")
        } catch (_: MoodleResponseParseException) {
            // expected
        }
    }

    @Test
    fun `malformed warnings must not become success (T08)`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"warnings":["invalid warning structure"]}"""))
        expectParseError { repository.saveFileSubmission(42, 7) }

        server.enqueue(MockResponse().setBody("""["not an object"]"""))
        expectParseError { repository.saveOnlineTextSubmission(42, "<p>x</p>") }

        server.enqueue(MockResponse().setBody("""{"submissionid":1,"attemptnumber":0,"timestarted":1,"timelimit":10,"warnings":"oops"}"""))
        expectParseError { repository.startAssignmentSubmission(42) }
    }

    @Test
    fun `save and submit accept only warning arrays`() = runBlocking {
        // 正しい形: 空配列 / 正しい warning 要素
        server.enqueue(MockResponse().setBody("[]"))
        repository.submitAssignmentForGrading(42, false)
        server.enqueue(MockResponse().setBody("""[{"item":"assignment","itemid":42,"warningcode":"x","message":"m"}]"""))
        try {
            repository.saveFileSubmission(42, 7)
            fail("warning must reject")
        } catch (e: AssignmentSubmissionRejectedException) {
            assertEquals("x", e.warnings.single().warningcode)
        }

        // 配列以外 (null 本文・オブジェクト・空本文・不正JSON) は形式エラー
        server.enqueue(MockResponse().setBody("null"))
        expectParseError { repository.saveFileSubmission(42, 7) }
        server.enqueue(MockResponse().setBody("{}"))
        expectParseError { repository.submitAssignmentForGrading(42, false) }
        server.enqueue(MockResponse().setBody(""))
        expectParseError { repository.saveOnlineTextSubmission(42, "<p>x</p>") }
        server.enqueue(MockResponse().setBody("{not json"))
        expectParseError { repository.saveOnlineTextSubmission(42, "<p>x</p>") }
    }

    @Test
    fun `typed reads reject wrong types instead of coercing`() = runBlocking {
        server.enqueue(MockResponse().setBody("null"))
        expectParseError { repository.assignmentStatus(42) }
        server.enqueue(MockResponse().setBody("""{"itemid":"not-a-number"}"""))
        expectParseError { repository.unusedDraftItemId() }
        server.enqueue(MockResponse().setBody("""[{"filename":1}]"""))
        expectParseError { repository.assignments(1) }
    }

    @Test
    fun `mark notification read contract is strict`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"notificationid":9,"warnings":[]}"""))
        assertTrue(repository.markNotificationRead(9))

        server.enqueue(MockResponse().setBody("""{"notificationid":9,"warnings":[{"item":"n","itemid":9,"warningcode":"x","message":"m"}]}"""))
        assertFalse(repository.markNotificationRead(9))

        server.enqueue(MockResponse().setBody("""{"notificationid":10,"warnings":[]}"""))
        expectParseError { repository.markNotificationRead(9) }

        server.enqueue(MockResponse().setBody("""{"notificationid":9,"warnings":"bad"}"""))
        expectParseError { repository.markNotificationRead(9) }

        server.enqueue(MockResponse().setBody("null"))
        expectParseError { repository.markNotificationRead(9) }
    }

    @Test
    fun `image link without mime type routes to image (T07)`() {
        assertEquals(PreviewKind.Image, previewKindFor("figure.png", null))
        assertEquals(PreviewKind.Image, previewKindFor("photo.JPG", null))
        assertEquals(PreviewKind.Image, previewKindFor("a.webp", ""))
        assertEquals(PreviewKind.Pdf, previewKindFor("doc.pdf", null))
        assertEquals(PreviewKind.Unsupported, previewKindFor("a.png", "application/octet-stream"))
    }
}

/** N08: シラバス応答の区別 (0件 / 通信失敗 / 仕様変更) */
class SyllabusResponseContractTest {

    private val repository = SyllabusRepository(store = InMemorySyllabusCache())

    private fun aura(result: String) =
        """{"actions":[{"id":"1;a","state":"SUCCESS","returnValue":{"returnValue":{"result":$result}}}]}"""

    @Test
    fun `真の0件はNoResults`() {
        assertEquals(SyllabusSearchResult.NoResults, repository.parseRecords(aura("[]")))
    }

    @Test
    fun `全レコードが解釈できないのは0件ではなく応答形式エラー`() {
        assertEquals(SyllabusSearchResult.InvalidResponse, repository.parseRecords(aura("""[{"unexpected":1},{"Id":""}]""")))
    }

    @Test
    fun `構造不正は通信失敗ではなく応答形式エラー`() {
        assertEquals(SyllabusSearchResult.InvalidResponse, repository.parseRecords("""{"actions":null}"""))
        assertEquals(SyllabusSearchResult.InvalidResponse, repository.parseRecords("""{"actions":"str"}"""))
        assertEquals(SyllabusSearchResult.InvalidResponse, repository.parseRecords("""{"actions":[{"state":"ERROR"}]}"""))
        assertEquals(SyllabusSearchResult.InvalidResponse, repository.parseRecords("""{"actions":[{"state":"SUCCESS","returnValue":[]}]}"""))
        assertEquals(SyllabusSearchResult.InvalidResponse, repository.parseRecords("not json at all"))
        assertEquals(SyllabusSearchResult.InvalidResponse, repository.parseRecords("{broken"))
    }

    @Test
    fun `正常なレコードはSuccess`() {
        val result = repository.parseRecords(aura("""[{"Id":"a0i","R_SlCourseName__c":"10001:教育原理"}]"""))
        assertTrue(result is SyllabusSearchResult.Success)
        assertEquals("10001", (result as SyllabusSearchResult.Success).records.single().courseCode)
    }
}

/** テスト用のメモリ実装 */
class InMemorySyllabusCache : dev.rits.bettermoodle.data.SyllabusUrlCache {
    val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    var saveDelayMillis = 0L
    override suspend fun syllabusCache(): Map<String, String> = map.toMap()
    override suspend fun saveSyllabusUrl(code: String, year: Int, url: String) {
        if (saveDelayMillis > 0) kotlinx.coroutines.delay(saveDelayMillis)
        map[syllabusCacheKey(code, year)] = url
    }
}
