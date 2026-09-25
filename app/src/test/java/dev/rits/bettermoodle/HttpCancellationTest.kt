package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.useResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** N05 / T05: ヘッダ待ち・本文読み取りのどちらでもコルーチンのキャンセルで中断し、資源を解放する */
class HttpCancellationTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun elapsedMs(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test
    fun `cancellation interrupts response body read (T05)`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setBodyDelay(1500, TimeUnit.MILLISECONDS))
        val client = MoodleClient(
            siteUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "t" },
            httpClient = http,
        )
        val ms = elapsedMs {
            val result = withTimeoutOrNull(150) { client.call("review_function") }
            assertNull(result)
        }
        assertTrue("cancelled body read took ${ms}ms", ms < 900)
    }

    @Test
    fun `cancellation before headers arrive is immediate`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]").setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        val ms = elapsedMs {
            val result = withTimeoutOrNull(150) {
                http.newCall(Request.Builder().url(server.url("/slow")).build()).useResponse { it.body?.string() }
            }
            assertNull(result)
        }
        assertTrue("cancelled header wait took ${ms}ms", ms < 900)
    }

    @Test
    fun `stalled body stream is interrupted by cancellation`() = runBlocking {
        // 本文の途中で止まる応答 (throttle)
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(64 * 1024))
                .throttleBody(1024, 500, TimeUnit.MILLISECONDS),
        )
        val ms = elapsedMs {
            val result = withTimeoutOrNull(200) {
                http.newCall(Request.Builder().url(server.url("/stall")).build()).useResponse { resp ->
                    resp.body!!.byteStream().use { input ->
                        val buf = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                        }
                        total
                    }
                }
            }
            assertNull(result)
        }
        assertTrue("stalled body read took ${ms}ms", ms < 1500)
    }

    @Test
    fun `download to file is interrupted and leaves no temp file`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/pdf")
                .setBody("y".repeat(64 * 1024))
                .throttleBody(1024, 500, TimeUnit.MILLISECONDS),
        )
        val client = MoodleClient(
            siteUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "t" },
            httpClient = http,
        )
        val dest = File.createTempFile("download-cancel-", ".bin")
        val url = server.url("/pluginfile.php/1/a.pdf").toString()
        val result = withTimeoutOrNull(200) { client.downloadToFile(url, dest) }
        assertNull(result)
        assertTrue("一時ファイルは削除される", !dest.exists())
    }

    @Test
    fun `normal completion does not cancel the call and reads the full body`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello"))
        val body = http.newCall(Request.Builder().url(server.url("/ok")).build()).useResponse { it.body!!.string() }
        assertEquals("hello", body)
    }

    @Test
    fun `disconnect during body surfaces as IOException not cancellation`() = runBlocking {
        server.enqueue(MockResponse().setBody("partial").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        var io = false
        try {
            http.newCall(Request.Builder().url(server.url("/cut")).build()).useResponse { it.body!!.string() }
        } catch (e: java.io.IOException) {
            io = true
        }
        assertTrue(io)
    }
}
