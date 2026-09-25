package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.AuthSession
import dev.rits.bettermoodle.data.AuthState
import dev.rits.bettermoodle.data.AuthTokenStore
import dev.rits.bettermoodle.data.NotificationReadSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N04: ログアウトの所有 scope、認証エラーと新ログインの競合、世代の整合性 */
class AuthSessionTest {

    /** 遅延・ゲートを注入できる永続化の Fake */
    private class FakeStore : AuthTokenStore {
        private val state = MutableStateFlow<String?>(null)
        var saveGate: CompletableDeferred<Unit>? = null
        var logoutGate: CompletableDeferred<Unit>? = null
        var logoutCalls = 0
        var clearCalls = 0

        fun seed(token: String?) { state.value = token }
        override val tokenFlow = state
        override suspend fun token(): String? = state.value
        override suspend fun saveLogin(token: String, privateToken: String?) {
            saveGate?.await()
            state.value = token
        }
        override suspend fun clearAuthTokens() {
            clearCalls++
            state.value = null
        }
        override suspend fun logout() {
            logoutGate?.await()
            logoutCalls++
            state.value = null
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleared = mutableListOf<Long>()

    private fun session(store: FakeStore): AuthSession =
        AuthSession(store, appScope, onSessionCleared = { cleared += System.nanoTime() }).also { it.start() }

    private suspend fun AuthSession.awaitState(predicate: (AuthState) -> Boolean): AuthState =
        withTimeout(2_000) { authState.first(predicate) }

    @After
    fun tearDown() = appScope.cancel()

    @Test
    fun `logout completes even when the caller scope is destroyed and emits LoggedOut last`() = runBlocking {
        val store = FakeStore().apply { seed("A") }
        val session = session(store)
        session.awaitState { it is AuthState.LoggedIn }

        store.logoutGate = CompletableDeferred()
        // 画面 scope からログアウトを起動し、直後に画面 scope を破棄する。
        // logout() が返す Job はアプリ scope のものなので画面破棄で止まらない
        val screenScope = CoroutineScope(Job())
        val started = CompletableDeferred<Job>()
        screenScope.launch { started.complete(session.logout()) }
        val logoutJob = withTimeout(2_000) { started.await() }
        screenScope.cancel()
        delay(50)

        // 永続化が遅延している間は現在トークンだけ null、状態はまだ LoggedIn
        assertNull(session.currentToken())
        assertTrue(session.authState.value is AuthState.LoggedIn)
        assertEquals(0, store.logoutCalls)

        store.logoutGate!!.complete(Unit)
        withTimeout(2_000) { logoutJob.join() }
        assertEquals(1, store.logoutCalls)
        assertEquals(1, cleared.size)
        assertEquals(AuthState.LoggedOut, session.authState.value)
    }

    @Test
    fun `stale invalidtoken does not clear a newer login`() = runBlocking {
        val store = FakeStore().apply { seed("A") }
        val session = session(store)
        val genA = (session.awaitState { it is AuthState.LoggedIn } as AuthState.LoggedIn).generation

        // 新しいログインが保存中 (DataStore 遅延) の間に、旧トークン A の invalidtoken が届く
        store.saveGate = CompletableDeferred()
        val login = session.login("B", null)
        delay(50)
        val authError = session.onAuthError("A")
        delay(50)
        store.saveGate!!.complete(Unit)
        withTimeout(2_000) { login.join(); authError.join() }

        assertEquals("B", session.currentToken())
        assertEquals(0, store.clearCalls)
        val genB = (session.authState.value as AuthState.LoggedIn).generation
        assertNotEquals(genA, genB)
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun `invalidtoken for the current token clears once and later duplicates are ignored`() = runBlocking {
        val store = FakeStore().apply { seed("A") }
        val session = session(store)
        session.awaitState { it is AuthState.LoggedIn }

        val first = session.onAuthError("A")
        val second = session.onAuthError("A")
        withTimeout(2_000) { first.join(); second.join() }

        assertEquals(1, store.clearCalls)
        assertEquals(AuthState.LoggedOut, session.authState.value)
        assertEquals(1, cleared.size)
    }

    @Test
    fun `account switch bumps generation and old generation is no longer current`() = runBlocking {
        val store = FakeStore().apply { seed("A") }
        val session = session(store)
        val genA = (session.awaitState { it is AuthState.LoggedIn } as AuthState.LoggedIn).generation
        assertTrue(session.isCurrent(genA))

        withTimeout(2_000) { session.logout().join() }
        assertFalse(session.isCurrent(genA))
        withTimeout(2_000) { session.login("B", "p").join() }
        val genB = (session.authState.value as AuthState.LoggedIn).generation
        assertTrue(genB > genA)
        assertFalse("旧世代の Worker 結果は適用しない", session.isCurrent(genA))
        assertTrue(session.isCurrent(genB))
    }

    @Test
    fun `restart after logout starts logged out`() = runBlocking {
        val store = FakeStore().apply { seed("A") }
        val session = session(store)
        session.awaitState { it is AuthState.LoggedIn }
        withTimeout(2_000) { session.logout().join() }

        // 再起動 = 同じ永続化で新しいセッションを作る
        val restarted = session(store)
        assertEquals(AuthState.LoggedOut, restarted.awaitState { it !is AuthState.Unknown })
        assertNull(restarted.currentToken())
    }

    @Test
    fun `read sync result from an old session is dropped`() = runBlocking {
        val generation = MutableStateFlow(1L)
        val gate = CompletableDeferred<Unit>()
        val sync = NotificationReadSync(
            scope = appScope,
            markRead = { _, _ -> gate.await(); true },
            currentGeneration = { generation.value },
            currentToken = { "tok" },
        )
        val job = sync.request(9L)!!
        assertTrue(9L in sync.state.value.inFlight)
        // 応答前にログアウト→再ログイン
        generation.value = 2L
        gate.complete(Unit)
        withTimeout(2_000) { job.join() }
        assertFalse(9L in sync.state.value.readIds)
        assertFalse(9L in sync.state.value.inFlight)

        // 現セッションの要求は失敗も記録し、再要求で再試行できる
        var attempts = 0
        val flaky = NotificationReadSync(appScope, markRead = { _, _ -> attempts++; if (attempts == 1) throw java.io.IOException("x") else true }, currentGeneration = { 2L }, currentToken = { "tok" })
        withTimeout(2_000) { flaky.request(5L)!!.join() }
        assertTrue(flaky.state.value.failed.containsKey(5L))
        withTimeout(2_000) { flaky.request(5L)!!.join() }
        assertTrue(5L in flaky.state.value.readIds)
        assertFalse(flaky.state.value.failed.containsKey(5L))
        delay(1)
    }
}
