package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.AssignmentFileDraft
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.MoodleWsException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/**
 * N01: draft 組み立てを HTTP レベルで確認する。
 * unused draft 取得 → 既存ファイルの取得 (トークン付き pluginfile) → 同じ draft・同じパスへ再アップロード
 * → 追加ファイルのアップロード、という順序と multipart の内容を検証する。
 */
class AssignmentFileDraftHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: MoodleRepository
    private lateinit var tempDir: File
    private val requests = mutableListOf<RecordedRequest>()

    /** 取得ファイルの本文とヘッダを差し替えられる簡易サーバー */
    private var fileBodies = mutableMapOf<String, Pair<String, String?>>() // path -> (body, contentType)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/webservice/rest/server.php") -> {
                        val form = request.form()
                        when (form["wsfunction"]) {
                            "core_files_get_unused_draft_itemid" ->
                                MockResponse().setBody("""{"component":"user","contextid":5,"userid":1,"filearea":"draft","itemid":555,"warnings":[]}""")
                            else -> MockResponse().setResponseCode(500)
                        }
                    }
                    path.startsWith("/webservice/pluginfile.php/") -> {
                        val key = path.substringBefore('?')
                        val (body, type) = fileBodies[key] ?: return MockResponse().setResponseCode(404)
                        MockResponse().setBody(body).apply { type?.let { setHeader("Content-Type", it) } }
                    }
                    path.startsWith("/webservice/upload.php") -> {
                        val body = request.body.clone().readUtf8()
                        val itemid = multipartValue(body, "itemid")
                        val filepath = multipartValue(body, "filepath")
                        val filename = Regex("filename=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                        MockResponse().setBody(
                            """[{"component":"user","contextid":5,"userid":1,"filearea":"draft","filename":"$filename","filepath":"$filepath","itemid":$itemid}]""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val client = MoodleClient(
            siteUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "tok" },
        )
        repository = MoodleRepository(client)
        tempDir = File.createTempFile("draft-test-", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun RecordedRequest.form(): Map<String, String> =
        body.clone().readUtf8().split('&').filter { it.isNotBlank() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
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

    private fun fileUrl(path: String) = server.url("/pluginfile.php/99/assignsubmission_file/submission_files/7$path").toString()

    @Test
    fun `build downloads kept files with token and re-uploads them to the same draft and path`() = runBlocking {
        fileBodies["/webservice/pluginfile.php/99/assignsubmission_file/submission_files/7/chapter1/report.pdf"] = "AAAA" to "application/pdf"
        fileBodies["/webservice/pluginfile.php/99/assignsubmission_file/submission_files/7/chapter2/report.pdf"] = "BBBBBB" to "application/pdf"
        val existing = listOf(
            MoodleFile(filename = "report.pdf", filepath = "/chapter1/", filesize = 4, fileurl = fileUrl("/chapter1/report.pdf")),
            MoodleFile(filename = "report.pdf", filepath = "/chapter2/", filesize = 6, fileurl = fileUrl("/chapter2/report.pdf")),
        )
        val local = File(tempDir, "new.pdf").apply { writeText("NEW") }
        val plan = AssignmentFileDraft.plan(existing, AssignmentFileDraft.LocalFile(local, "new.pdf"))

        val itemId = AssignmentFileDraft(repository, tempDir).build(plan, maxBytesPerFile = 1024)

        assertEquals(555L, itemId)
        val recorded = synchronized(requests) { requests.toList() }
        assertEquals("core_files_get_unused_draft_itemid", recorded[0].form()["wsfunction"])

        // 既存ファイルはトークン付きの webservice/pluginfile から取得する
        val download1 = recorded[1].path.orEmpty()
        assertTrue(download1, download1.startsWith("/webservice/pluginfile.php/99/") && download1.contains("token=tok"))
        val upload1 = recorded[2].body.clone().readUtf8()
        assertEquals("555", multipartValue(upload1, "itemid"))
        assertEquals("/chapter1/", multipartValue(upload1, "filepath"))
        assertTrue(upload1.contains("filename=\"report.pdf\""))
        assertTrue("再アップロード本文は取得した内容", upload1.contains("\r\n\r\nAAAA\r\n"))

        val upload2 = recorded[4].body.clone().readUtf8()
        assertEquals("555", multipartValue(upload2, "itemid"))
        assertEquals("/chapter2/", multipartValue(upload2, "filepath"))

        val upload3 = recorded[5].body.clone().readUtf8()
        assertEquals("555", multipartValue(upload3, "itemid"))
        assertEquals("/", multipartValue(upload3, "filepath"))
        assertTrue(upload3.contains("filename=\"new.pdf\""))
        assertTrue(upload3.contains("\r\n\r\nNEW\r\n"))
        assertEquals(6, recorded.size)
        assertTrue("一時ファイルが残らない", tempDir.listFiles().orEmpty().none { it.name.startsWith("assignment_keep_") })
    }

    @Test
    fun `build refuses html responses and size mismatches for kept files`() = runBlocking {
        fileBodies["/webservice/pluginfile.php/99/assignsubmission_file/submission_files/7/a.pdf"] = "<html>login</html>" to "text/html"
        val html = listOf(MoodleFile(filename = "a.pdf", filepath = "/", filesize = 18, fileurl = fileUrl("/a.pdf")))
        try {
            AssignmentFileDraft(repository, tempDir).build(AssignmentFileDraft.plan(html, null), 1024)
            fail("HTML must not be re-uploaded as a submission")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTML"))
        }

        fileBodies["/webservice/pluginfile.php/99/assignsubmission_file/submission_files/7/b.pdf"] = "short" to "application/pdf"
        val mismatch = listOf(MoodleFile(filename = "b.pdf", filepath = "/", filesize = 100, fileurl = fileUrl("/b.pdf")))
        try {
            AssignmentFileDraft(repository, tempDir).build(AssignmentFileDraft.plan(mismatch, null), 1024)
            fail("size mismatch must not be re-uploaded")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("一致しません"))
        }
        val uploads = synchronized(requests) { requests.count { it.path.orEmpty().startsWith("/webservice/upload.php") } }
        assertEquals(0, uploads)
    }

    @Test
    fun `upload response with a different path or name is rejected`() = runBlocking {
        val local = File(tempDir, "c.pdf").apply { writeText("C") }
        // サーバーが別のパスへ保存したと応答する
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""[{"filename":"c.pdf","filepath":"/other/","itemid":555}]""")
        }
        try {
            repository.uploadSubmissionFile(local, "c.pdf", draftItemId = 555L, filepath = "/")
            fail("path mismatch must fail")
        } catch (e: MoodleWsException) {
            assertEquals("uploadfailed", e.errorCode)
        }
    }
}
