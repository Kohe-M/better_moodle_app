package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/** F09: 取得上限・カーソル未進行・24時間分の取り切りを HTTP レベルで確認する */
class RepositoryPagingTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: MoodleRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = MoodleRepository(
            MoodleClient(
                siteUrl = server.url("/").toString().trimEnd('/'),
                tokenProvider = { "t" },
            ),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun eventsPage(ids: LongRange, timesortBase: Long = 1_000L, module: String = "assign"): String {
        val events = ids.joinToString(",") { id ->
            """{"id":$id,"name":"e$id","modulename":"$module","timesort":${timesortBase + id},"timestart":${timesortBase + id}}"""
        }
        val last = ids.lastOrNull() ?: 0
        val first = ids.firstOrNull() ?: 0
        return """{"events":[$events],"firstid":$first,"lastid":$last}"""
    }

    private fun lastForm(): Map<String, String> =
        server.takeRequest().body.readUtf8().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun `reports hasMore when page limit is reached`() = runBlocking {
        server.enqueue(MockResponse().setBody(eventsPage(1L..50L)))
        server.enqueue(MockResponse().setBody(eventsPage(51L..100L)))
        server.enqueue(MockResponse().setBody(eventsPage(101L..150L)))

        val page = repository.upcomingDeadlines(fromEpochSec = 0L)

        assertEquals(150, page.events.size)
        assertTrue("3ページ取得しても続きがある場合は打ち切りを知らせる", page.hasMore)
        assertEquals(150L, page.nextAfterEventId)
        assertEquals(3, server.requestCount)
        lastForm()
        assertEquals("50", lastForm()["aftereventid"])
        assertEquals("100", lastForm()["aftereventid"])
    }

    @Test
    fun `continues from cursor and stops on short page`() = runBlocking {
        server.enqueue(MockResponse().setBody(eventsPage(151L..160L)))

        val page = repository.upcomingDeadlines(fromEpochSec = 0L, afterEventId = 150L)

        assertEquals(10, page.events.size)
        assertFalse(page.hasMore)
        assertNull(page.nextAfterEventId)
        assertEquals("150", lastForm()["aftereventid"])
    }

    @Test
    fun `stalled cursor does not loop and is reported as incomplete not end`() = runBlocking {
        // lastid が進まない応答 (=0) が満杯ページで返る
        val full = (1L..50L).joinToString(",") { """{"id":$it,"name":"e","modulename":"assign","timesort":$it}""" }
        server.enqueue(MockResponse().setBody("""{"events":[$full],"firstid":0,"lastid":0}"""))

        val page = repository.upcomingEvents(fromEpochSec = 0L)

        assertEquals(1, server.requestCount)
        assertEquals(50, page.events.size)
        assertFalse(page.hasMore)
        assertTrue("満杯ページでカーソル不在は終端ではない", page.isIncomplete)
    }

    /** T06 */
    @Test
    fun `stalled full page must not be reported complete by deadlinesWithin`() = runBlocking {
        val full = (1L..50L).joinToString(",") { """{"id":$it,"modulename":"assign","timesort":$it}""" }
        server.enqueue(MockResponse().setBody("""{"events":[$full],"lastid":0}"""))

        val window = repository.deadlinesWithin(0, 10_000)

        assertFalse(window.complete)
        assertNotNull(window.incompleteReason)
        assertEquals(50, window.events.size)
    }

    @Test
    fun `cycling cursor that repeats known events is incomplete`() = runBlocking {
        server.enqueue(MockResponse().setBody(eventsPage(1L..50L)))
        // lastid は進むが同じイベントしか返らない
        val repeat = (1L..50L).joinToString(",") { """{"id":$it,"name":"e$it","modulename":"assign","timesort":${1000 + it}}""" }
        server.enqueue(MockResponse().setBody("""{"events":[$repeat],"firstid":1,"lastid":51}"""))
        server.enqueue(MockResponse().setBody(eventsPage(51L..100L)))

        val page = repository.upcomingEvents(fromEpochSec = 0L)

        assertEquals(2, server.requestCount)
        assertEquals(50, page.events.size)
        assertFalse(page.hasMore)
        assertTrue(page.isIncomplete)
    }

    @Test
    fun `all pages outside deadline modules still complete normally`() = runBlocking {
        server.enqueue(MockResponse().setBody(eventsPage(1L..50L, module = "forum")))
        server.enqueue(MockResponse().setBody(eventsPage(51L..60L, module = "forum")))

        val page = repository.upcomingDeadlines(fromEpochSec = 0L)
        assertTrue(page.events.isEmpty())
        assertFalse(page.hasMore)
        assertFalse(page.isIncomplete)
    }

    @Test
    fun `deadlinesWithin fetches until the window is exceeded`() = runBlocking {
        server.enqueue(MockResponse().setBody(eventsPage(1L..50L, timesortBase = 0)))
        server.enqueue(MockResponse().setBody(eventsPage(51L..100L, timesortBase = 0)))
        // 3ページ目は 24h を超えるイベントを含む
        server.enqueue(MockResponse().setBody(eventsPage(101L..150L, timesortBase = 100_000)))
        server.enqueue(MockResponse().setBody(eventsPage(151L..200L, timesortBase = 100_000)))

        val window = repository.deadlinesWithin(fromEpochSec = 0L, untilEpochSec = 120L)

        assertTrue(window.complete)
        assertEquals((1L..100L).toList(), window.events.map { it.id }.take(100))
        assertEquals(100, window.events.count { it.timesort <= 120L })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `deadlinesWithin marks incomplete when page cap is hit`() = runBlocking {
        repeat(3) { i -> server.enqueue(MockResponse().setBody(eventsPage((i * 50L + 1)..(i * 50L + 50), timesortBase = 0))) }

        val window = repository.deadlinesWithin(fromEpochSec = 0L, untilEpochSec = 1_000_000L, maxPages = 2)

        assertFalse(window.complete)
        assertEquals(100, window.events.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `notifications expose hasMore and next offset`() = runBlocking {
        val items = (1..50).joinToString(",") { """{"id":$it,"subject":"s$it","timecreated":1,"read":false}""" }
        server.enqueue(MockResponse().setBody("""{"notifications":[$items],"unreadcount":50}"""))
        server.enqueue(MockResponse().setBody("""{"notifications":[{"id":51,"subject":"s51","timecreated":1,"read":true}],"unreadcount":50}"""))

        val first = repository.notifications()
        assertTrue(first.hasMore)
        assertEquals(50, first.nextOffset)
        val second = repository.notifications(offset = first.nextOffset)
        assertFalse(second.hasMore)
        assertEquals(1, second.notifications.size)

        assertEquals("0", lastForm()["offset"])
        assertEquals("50", lastForm()["offset"])
    }

    @Test
    fun `courses accept timeline classification`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"courses":[{"id":1,"fullname":"12345:過去科目"}],"nextoffset":1}"""))

        val courses = repository.enrolledCourses("past")

        assertEquals(1, courses.size)
        assertEquals("past", lastForm()["classification"])
    }
}
