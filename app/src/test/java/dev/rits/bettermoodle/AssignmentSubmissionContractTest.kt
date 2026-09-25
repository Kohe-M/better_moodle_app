package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.AssignmentSubmissionRejectedException
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.MoodleWsException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/**
 * 課題提出のWS契約 (HTTPリクエスト本文と応答の扱い) をモックサーバーで固定する。
 * F01: 保存・最終提出は `assignmentid`、取得・開始は `assignid`。
 * F04: HTTP 200 の warning を成功扱いしない。
 */
class AssignmentSubmissionContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: MoodleRepository
    private val authErrors = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = MoodleClient(
            siteUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "token-A" },
            onAuthError = { authErrors += it },
        )
        repository = MoodleRepository(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun RecordedRequest.form(): Map<String, String> =
        body.readUtf8()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

    private fun multipartValue(body: String, name: String): String? {
        val marker = "name=\"$name\""
        val start = body.indexOf(marker).takeIf { it >= 0 } ?: return null
        val crlf = "\r\n"
        val blank = body.indexOf(crlf + crlf, start).takeIf { it >= 0 } ?: return null
        val end = body.indexOf(crlf, blank + 4).takeIf { it >= 0 } ?: body.length
        return body.substring(blank + 4, end)
    }

    @Test
    fun `save online text uses assignmentid and html format`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        repository.saveOnlineTextSubmission(42L, "<p>hello &amp; bye</p>")

        val form = server.takeRequest().form()
        assertEquals("mod_assign_save_submission", form["wsfunction"])
        assertEquals("42", form["assignmentid"])
        assertNull("save must not send assignid", form["assignid"])
        assertEquals("<p>hello &amp; bye</p>", form["plugindata[onlinetext_editor][text]"])
        assertEquals("1", form["plugindata[onlinetext_editor][format]"])
        assertEquals("0", form["plugindata[onlinetext_editor][itemid]"])
        assertEquals("token-A", form["wstoken"])
    }

    @Test
    fun `save file submission uses assignmentid and draft item id`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        repository.saveFileSubmission(42L, 987654L)

        val form = server.takeRequest().form()
        assertEquals("mod_assign_save_submission", form["wsfunction"])
        assertEquals("42", form["assignmentid"])
        assertEquals("987654", form["plugindata[files_filemanager]"])
        assertNull(form["assignid"])
    }

    @Test
    fun `submit for grading uses assignmentid and statement flag`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        repository.submitAssignmentForGrading(42L, acceptSubmissionStatement = true)

        val form = server.takeRequest().form()
        assertEquals("mod_assign_submit_for_grading", form["wsfunction"])
        assertEquals("42", form["assignmentid"])
        assertEquals("1", form["acceptsubmissionstatement"])
        assertNull(form["assignid"])
    }

    @Test
    fun `status and start keep assignid contract`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"lastattempt":{"canedit":true},"warnings":[]}"""))
        server.enqueue(MockResponse().setBody("""{"submissionid":1,"attemptnumber":0,"timestarted":1,"timelimit":600,"warnings":[]}"""))

        repository.assignmentStatus(42L)
        repository.startAssignmentSubmission(42L)

        val status = server.takeRequest().form()
        assertEquals("mod_assign_get_submission_status", status["wsfunction"])
        assertEquals("42", status["assignid"])
        assertNull(status["assignmentid"])

        val start = server.takeRequest().form()
        assertEquals("mod_assign_start_submission", start["wsfunction"])
        assertEquals("42", start["assignid"])
        assertNull(start["assignmentid"])
    }

    @Test
    fun `warning array on save is treated as rejection with message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"item":"assignment","itemid":42,"warningcode":"couldnotsavesubmission","message":"Could not save submission."}]""",
            ),
        )

        try {
            repository.saveOnlineTextSubmission(42L, "<p>x</p>")
            fail("warning must not be treated as success")
        } catch (e: AssignmentSubmissionRejectedException) {
            assertEquals("couldnotsavesubmission", e.warnings.single().warningcode)
            assertTrue(e.message!!.contains("Could not save submission."))
        }
    }

    @Test
    fun `start submission warning is rejection`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"submissionid":0,"attemptnumber":0,"timestarted":0,"timelimit":0,"warnings":[{"item":"assignment","itemid":42,"warningcode":"timelimitnotenabled","message":"Time limit is not enabled"}]}""",
            ),
        )

        try {
            repository.startAssignmentSubmission(42L)
            fail("warning must not be treated as success")
        } catch (e: AssignmentSubmissionRejectedException) {
            assertEquals("timelimitnotenabled", e.warnings.single().warningcode)
        }
    }

    @Test
    fun `empty warning array is success but null body is a contract violation`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        repository.submitAssignmentForGrading(42L, acceptSubmissionStatement = false)

        // save/submit は warning 配列を返す契約。null 本文を成功扱いにしない (N08)
        server.enqueue(MockResponse().setBody("null"))
        try {
            repository.saveFileSubmission(42L, 1L)
            fail("null body must not be treated as success")
        } catch (_: dev.rits.bettermoodle.data.MoodleResponseParseException) {
            // expected
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `moodle exception body raises ws exception without clearing auth`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"exception":"invalid_parameter_exception","errorcode":"invalidparameter","message":"Invalid parameter value detected"}""",
            ),
        )

        try {
            repository.saveOnlineTextSubmission(42L, "<p>x</p>")
            fail("expected MoodleWsException")
        } catch (e: MoodleWsException) {
            assertEquals("invalidparameter", e.errorCode)
        }
        assertTrue(authErrors.isEmpty())
    }

    @Test
    fun `invalidtoken reports the token that failed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"exception":"moodle_exception","errorcode":"invalidtoken","message":"Invalid token"}""",
            ),
        )

        try {
            repository.assignmentStatus(42L)
            fail("expected MoodleWsException")
        } catch (e: MoodleWsException) {
            assertEquals("invalidtoken", e.errorCode)
        }
        assertEquals(listOf("token-A"), authErrors)
    }

    @Test
    fun `upload into existing draft sends itemid and rejects mismatched draft`() = runBlocking {
        val tmp = File.createTempFile("upload", ".txt").apply { writeText("abc") }
        try {
            server.enqueue(MockResponse().setBody("""[{"filename":"a.txt","itemid":555}]"""))
            val uploaded = repository.uploadSubmissionFile(tmp, "a.txt", draftItemId = 555L)
            assertEquals(555L, uploaded.itemid)
            val body = server.takeRequest().body.readUtf8()
            // multipart の各パートは name= の後に Content-Length 等のヘッダが続く
            assertEquals("555", multipartValue(body, "itemid"))
            assertEquals("draft", multipartValue(body, "filearea"))

            server.enqueue(MockResponse().setBody("""[{"filename":"a.txt","itemid":777}]"""))
            try {
                repository.uploadSubmissionFile(tmp, "a.txt", draftItemId = 555L)
                fail("upload to a different draft must fail")
            } catch (e: MoodleWsException) {
                assertEquals("uploadfailed", e.errorCode)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `unused draft itemid is parsed`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"component":"user","contextid":5,"userid":1,"filearea":"draft","itemid":123456,"warnings":[]}"""))

        assertEquals(123456L, repository.unusedDraftItemId())
        assertEquals("core_files_get_unused_draft_itemid", server.takeRequest().form()["wsfunction"])
    }

    @Test
    fun `mark notification read returns success`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"notificationid":9,"warnings":[]}"""))

        assertTrue(repository.markNotificationRead(9L))
        val form = server.takeRequest().form()
        assertEquals("core_message_mark_notification_read", form["wsfunction"])
        assertEquals("9", form["notificationid"])
        assertFalse(authErrors.isNotEmpty())
    }
}
