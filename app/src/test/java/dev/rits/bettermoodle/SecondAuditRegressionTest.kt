package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.data.AuthSession
import dev.rits.bettermoodle.data.AuthState
import dev.rits.bettermoodle.data.AuthTokenStore
import dev.rits.bettermoodle.data.DeadlineWindow
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.NotificationReadSync
import dev.rits.bettermoodle.work.DeadlineCursor
import dev.rits.bettermoodle.work.deadlineNotificationKey
import dev.rits.bettermoodle.work.nextDeadlineCursor
import dev.rits.bettermoodle.work.retainedNotifiedEventIds
import dev.rits.bettermoodle.work.shouldPruneNotifiedKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** SECOND_FOLLOWUP_AUDIT R05 / R06 / R09 の回帰テスト */
class SecondAuditRegressionTest {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = appScope.cancel()

    // ---- R05: セッション境界と Worker 副作用の原子性 ----

    private class FakeStore(initial: String?) : AuthTokenStore {
        private val state = MutableStateFlow(initial)
        override val tokenFlow = state
        override suspend fun token(): String? = state.value
        override suspend fun saveLogin(token: String, privateToken: String?) { state.value = token }
        override suspend fun clearAuthTokens() { state.value = null }
        override suspend fun logout() { state.value = null }
    }

    @Test
    fun `worker side effects inside withCurrentSession are not interleaved with logout`() = runBlocking {
        val session = AuthSession(FakeStore("A"), appScope).also { it.start() }
        val gen = (withTimeout(2_000) { session.authState.first { it is AuthState.LoggedIn } } as AuthState.LoggedIn).generation

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val notifiedDuring = mutableListOf<String>()
        // Worker: 世代確認を通過した直後で止める
        val worker = async(Dispatchers.Default) {
            session.withCurrentSession(gen) {
                entered.complete(Unit)
                release.await()
                notifiedDuring += "notify+persist"
                true
            }
        }
        withTimeout(2_000) { entered.await() }

        // その間にログアウト → B でログイン を要求する
        val logout = session.logout()
        delay(50)
        assertFalse("Worker の副作用が終わるまでログアウトは完了しない", logout.isCompleted)
        assertTrue(session.authState.value is AuthState.LoggedIn)

        release.complete(Unit)
        assertEquals(true, withTimeout(2_000) { worker.await() })
        withTimeout(2_000) { logout.join() }
        assertEquals(AuthState.LoggedOut, session.authState.value)
        withTimeout(2_000) { session.login("B", null).join() }

        // ログアウト後・B ログイン後に旧世代で再開した Worker は何もしない
        var ran = false
        val result = session.withCurrentSession(gen) { ran = true; true }
        assertNull(result)
        assertFalse(ran)
        assertEquals(listOf("notify+persist"), notifiedDuring)
    }

    @Test
    fun `old read request completing later does not remove a newer request for the same id`() = runBlocking {
        val generation = MutableStateFlow(1L)
        val entered = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var calls = 0
        val sync = NotificationReadSync(
            scope = appScope,
            markRead = { _, _ ->
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    // 旧要求は通信中 (キャンセルされない前提で応答を待つ)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { firstGate.await() }
                    true
                } else {
                    secondGate.await()
                    true
                }
            },
            currentGeneration = { generation.value },
            currentToken = { "tok" },
        )
        val old = sync.request(9L)!!
        withTimeout(2_000) { entered.await() }
        // 通信中にログアウト → 再ログイン → 同じ ID を新セッションで要求
        generation.value = 2L
        sync.clear()
        val fresh = sync.request(9L)!!
        assertTrue(9L in sync.state.value.inFlight)

        firstGate.complete(Unit)
        withTimeout(2_000) { old.join() }
        assertTrue("旧要求の完了で新要求の inFlight を消さない", 9L in sync.state.value.inFlight)
        assertFalse(9L in sync.state.value.readIds)

        secondGate.complete(Unit)
        withTimeout(2_000) { fresh.join() }
        assertTrue(9L in sync.state.value.readIds)
        assertFalse(9L in sync.state.value.inFlight)
    }

    // ---- R06: HTML 提出物の再取得 ----

    private fun clientFor(server: MockWebServer) =
        MoodleRepository(MoodleClient(siteUrl = server.url("/").toString().trimEnd('/'), tokenProvider = { "test" }))

    /** 監査プローブ legitimateHtmlSubmissionMustRemainDownloadable */
    @Test
    fun `legitimate html submission must remain downloadable`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val tmp = File.createTempFile("audit-html-", ".tmp")
        try {
            val body = "<html><body>my homework</body></html>"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(body))
            clientFor(server).downloadSubmissionFile(
                MoodleFile(
                    filename = "homework.html",
                    filepath = "/",
                    filesize = body.length.toLong(),
                    mimetype = "text/html",
                    fileurl = server.url("/pluginfile.php/1/homework.html").toString(),
                ),
                tmp,
                1024,
            )
            assertEquals(body, tmp.readText())
        } finally {
            server.shutdown()
            tmp.delete()
        }
    }

    @Test
    fun `login page html for a pdf is still rejected and missing mime is accepted`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val tmp = File.createTempFile("audit-pdf-", ".tmp")
        try {
            val login = "<html><body>Please log in</body></html>"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(login))
            try {
                clientFor(server).downloadSubmissionFile(
                    MoodleFile(filename = "report.pdf", filepath = "/", filesize = login.length.toLong(), mimetype = "application/pdf", fileurl = server.url("/pluginfile.php/1/report.pdf").toString()),
                    tmp,
                    1024,
                )
                fail("login HTML must not be re-uploaded as a PDF")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("HTML"))
            }

            // Content-Type が無い応答はサイズ一致なら受け入れる
            server.enqueue(MockResponse().setBody("%PDF-1.4 x").removeHeader("Content-Type"))
            clientFor(server).downloadSubmissionFile(
                MoodleFile(filename = "report.pdf", filepath = "/", filesize = 10, mimetype = null, fileurl = server.url("/pluginfile.php/1/report.pdf").toString()),
                tmp,
                1024,
            )
            assertEquals("%PDF-1.4 x", tmp.readText())

            // サイズ不明のファイルは検証できないので取得しない
            try {
                clientFor(server).downloadSubmissionFile(
                    MoodleFile(filename = "x.pdf", filepath = "/", filesize = null, fileurl = server.url("/pluginfile.php/1/x.pdf").toString()),
                    tmp,
                    1024,
                )
                fail("unknown size must not be verified")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("サイズが不明"))
            }
        } finally {
            server.shutdown()
            tmp.delete()
        }
    }

    @Test
    fun `redirect away from pluginfile is rejected`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val tmp = File.createTempFile("audit-redirect-", ".tmp")
        try {
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", server.url("/login/index.php").toString()),
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("fake"))
            try {
                clientFor(server).downloadSubmissionFile(
                    MoodleFile(filename = "a.pdf", filepath = "/", filesize = 4, mimetype = "application/pdf", fileurl = server.url("/pluginfile.php/1/a.pdf").toString()),
                    tmp,
                    1024,
                )
                fail("redirect to login must be rejected")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("リダイレクト"))
            }
        } finally {
            server.shutdown()
            tmp.delete()
        }
    }

    // ---- R09: Worker の継続カーソル ----

    private fun eventsPage(ids: LongRange, timesortBase: Long = 0): String {
        val events = ids.joinToString(",") { """{"id":$it,"name":"e$it","modulename":"assign","timesort":${timesortBase + it}}""" }
        return """{"events":[$events],"firstid":${ids.first},"lastid":${ids.last}}"""
    }

    @Test
    fun `deadlinesWithin continues from a saved cursor and the worker policy reaches later pages`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            val repository = clientFor(server)
            // 24時間窓に 200 件 (4 ページ)。上限 2 ページで打ち切る
            server.enqueue(MockResponse().setBody(eventsPage(1L..50L)))
            server.enqueue(MockResponse().setBody(eventsPage(51L..100L)))
            val run1 = repository.deadlinesWithin(0, 10_000, maxPages = 2)
            assertFalse(run1.complete)
            assertEquals(100L, run1.nextAfterEventId)
            val cursor1 = nextDeadlineCursor(0, run1)
            assertEquals(DeadlineCursor(0, 100L), cursor1)
            assertFalse("不完全な実行では通知済みを絞らない", shouldPruneNotifiedKeys(run1, continuedFromCursor = false))

            // 2 回目: 保存したカーソルから続ける (先頭から取り直さない)
            server.enqueue(MockResponse().setBody(eventsPage(101L..150L)))
            server.enqueue(MockResponse().setBody(eventsPage(151L..200L)))
            val run2 = repository.deadlinesWithin(cursor1!!.fromEpochSec, 10_000, maxPages = 2, afterEventId = cursor1.afterEventId)
            assertEquals((101L..200L).toList(), run2.events.map { it.id })
            assertFalse(run2.complete)
            val cursor2 = nextDeadlineCursor(cursor1.fromEpochSec, run2)
            assertEquals(DeadlineCursor(0, 200L), cursor2)

            // 3 回目: 終端に到達 → カーソル解除。継続実行なので通知済みは絞らない
            server.enqueue(MockResponse().setBody(eventsPage(201L..210L)))
            val run3 = repository.deadlinesWithin(cursor2!!.fromEpochSec, 10_000, maxPages = 2, afterEventId = cursor2.afterEventId)
            assertTrue(run3.complete)
            assertNull(nextDeadlineCursor(cursor2.fromEpochSec, run3))
            assertFalse(shouldPruneNotifiedKeys(run3, continuedFromCursor = true))

            // 4 回目: 先頭から取り切れた実行だけが通知済みを絞れる
            server.enqueue(MockResponse().setBody(eventsPage(1L..10L)))
            val run4 = repository.deadlinesWithin(0, 10_000, maxPages = 2)
            assertTrue(run4.complete)
            assertTrue(shouldPruneNotifiedKeys(run4, continuedFromCursor = false))

            // 通知済みキーの扱い: 継続実行の後も先頭側のキーは残る
            val notifiedAfterRun1 = run1.events.map { it.deadlineNotificationKey() }.toSet()
            val retainedAfterRun3 = retainedNotifiedEventIds(
                existingNotifiedIds = notifiedAfterRun1,
                currentDeadlineIds = run3.events.map { it.deadlineNotificationKey() }.toSet() + notifiedAfterRun1,
                newlyNotifiedIds = run3.events.map { it.deadlineNotificationKey() }.toSet(),
            )
            assertTrue(notifiedAfterRun1.all { it in retainedAfterRun3 })
            assertEquals(6, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cursor is not kept for cursor anomalies or completed windows`() {
        val anomaly = DeadlineWindow(events = emptyList(), complete = false, incompleteReason = "lastid が進みません", nextAfterEventId = null)
        assertNull(nextDeadlineCursor(5, anomaly))
        val done = DeadlineWindow(events = listOf(ActionEvent(id = 1)), complete = true)
        assertNull(nextDeadlineCursor(5, done))
        assertNotNull(nextDeadlineCursor(5, DeadlineWindow(emptyList(), complete = false, incompleteReason = "cap", nextAfterEventId = 9)))
    }
}

/** THIRD_FOLLOWUP_AUDIT S03: 既読要求のセッション束縛 */
class ReadRequestSessionBindingTest {

    /** 監査プローブ readRequestQueuedInOldSessionMustNotSendWithNewSession */
    @Test
    fun `read request queued in old session must not send with new session`() = runBlocking {
        var generation = 1L
        val sentAs = mutableListOf<Long>()
        val sync = NotificationReadSync(this, markRead = { _, _ -> sentAs += generation; true }, currentGeneration = { generation }, currentToken = { "tok$generation" })
        val job = sync.request(9)!! // このイベントループ上で予約 (まだ実行されていない)
        sync.clear()
        generation = 2L
        job.join()
        assertTrue("old request sent with generation $sentAs", sentAs.isEmpty())
        assertFalse(9L in sync.state.value.inFlight)
    }

    @Test
    fun `generation change before send without clear also suppresses the send`() = runBlocking {
        var generation = 1L
        val sent = mutableListOf<Long>()
        val sync = NotificationReadSync(this, markRead = { id, _ -> sent += id; true }, currentGeneration = { generation }, currentToken = { "tok" })
        val job = sync.request(9)!!
        generation = 2L
        job.join()
        assertTrue(sent.isEmpty())
        assertFalse(9L in sync.state.value.inFlight)
    }

    @Test
    fun `request is sent with the token snapshot taken at request time`() = runBlocking {
        var token = "A"
        val usedTokens = mutableListOf<String>()
        val sync = NotificationReadSync(this, markRead = { _, t -> usedTokens += t; true }, currentGeneration = { 1L }, currentToken = { token })
        val job = sync.request(9)!!
        token = "B" // 送信前にトークンが切り替わっても、予約時点のトークンで送る (旧トークンは invalidtoken で拒否される)
        job.join()
        assertEquals(listOf("A"), usedTokens)

        // 未ログイン (トークン無し) では予約しない
        token = ""
        val none = NotificationReadSync(this, markRead = { _, _ -> true }, currentGeneration = { 1L }, currentToken = { null })
        assertNull(none.request(5))
    }
}
