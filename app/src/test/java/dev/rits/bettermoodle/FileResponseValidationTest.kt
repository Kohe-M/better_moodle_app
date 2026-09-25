package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleWsException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** G12: プレビュー・共有を含むすべてのファイル取得で、エラー応答をファイルとして扱わない */
class FileResponseValidationTest {

    private val server = MockWebServer().also { it.start() }
    private val authErrors = mutableListOf<String>()
    private val client = MoodleClient(
        siteUrl = server.url("/").toString().trimEnd('/'),
        tokenProvider = { "tok" },
        onAuthError = { authErrors += it },
    )

    @After
    fun tearDown() = server.shutdown()

    private fun fileUrl() = server.url("/webservice/pluginfile.php/1/mod_resource/content/0/a.pdf").toString() + "?token=tok"

    private fun fetch(allowHtml: Boolean = false): String = runBlocking {
        client.fetchFile(fileUrl(), allowHtml) { it.body!!.string() }
    }

    @Test
    fun `moodle json error is rejected and invalidtoken is reported`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Invalid token","errorcode":"invalidtoken","stacktrace":null}"""),
        )
        try {
            fetch()
            fail("error response must not be returned as a file")
        } catch (e: MoodleWsException) {
            assertEquals("invalidtoken", e.errorCode)
        }
        assertEquals(listOf("tok"), authErrors)
    }

    @Test
    fun `html login page is rejected unless the file itself is html`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>login</html>"))
        try {
            fetch()
            fail("html must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("HTML"))
        }

        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<p>report</p>"))
        assertEquals("<p>report</p>", fetch(allowHtml = true))
    }

    @Test
    fun `plain json file without errorcode is accepted`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"answer":42}"""))
        assertEquals("""{"answer":42}""", fetch())
        assertTrue(authErrors.isEmpty())
    }

    @Test
    fun `redirect away from pluginfile is rejected`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/login/index.php").toString()))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.4"))
        try {
            fetch()
            fail("redirect to login must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("リダイレクト"))
        }
    }
}
