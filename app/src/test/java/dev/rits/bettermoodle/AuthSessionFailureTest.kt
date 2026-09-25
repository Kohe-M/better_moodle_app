package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.AuthSession
import dev.rits.bettermoodle.data.AuthState
import dev.rits.bettermoodle.data.AuthTokenStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** G04: 保存領域・Keystore の障害でクラッシュせず、ログイン失敗を呼び出し側へ返す */
class AuthSessionFailureTest {

    private class FailingStore(
        initial: String? = null,
        var failSave: Boolean = false,
        var failLogout: Boolean = false,
        var failRead: Boolean = false,
    ) : AuthTokenStore {
        val state = MutableStateFlow(initial)
        override val tokenFlow: Flow<String?> = flow {
            if (failRead) throw IOException("datastore corrupted")
            state.collect { emit(it) }
        }
        override suspend fun token(): String? {
            if (failRead) throw IOException("datastore corrupted")
            return state.value
        }
        override suspend fun saveLogin(token: String, privateToken: String?) {
            if (failSave) throw IllegalStateException("keystore unavailable")
            state.value = token
        }
        override suspend fun clearAuthTokens() {
            state.value = null
        }
        override suspend fun logout() {
            if (failLogout) throw IOException("cannot write")
            state.value = null
        }
    }

    private val unhandled = Collections.synchronizedList(mutableListOf<Throwable>())
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> unhandled += e },
    )

    @After
    fun tearDown() = appScope.cancel()

    private suspend fun AuthSession.awaitState(predicate: (AuthState) -> Boolean): AuthState =
        withTimeout(2_000) { authState.first(predicate) }

    @Test
    fun `login returns false and stays logged out when saving the token fails`() = runBlocking {
        val store = FailingStore(failSave = true)
        val session = AuthSession(store, appScope).also { it.start() }
        session.awaitState { it is AuthState.LoggedOut }

        val ok = withTimeout(2_000) { session.login("A", null).await() }

        assertFalse(ok)
        assertEquals(AuthState.LoggedOut, session.authState.value)
        assertEquals(null, session.currentToken())
        assertTrue(unhandled.isEmpty())

        // 障害が解消すればやり直せる
        store.failSave = false
        assertTrue(withTimeout(2_000) { session.login("A", null).await() })
        session.awaitState { it is AuthState.LoggedIn }
        Unit
    }

    @Test
    fun `unreadable token store is treated as logged out without crashing`() = runBlocking {
        val store = FailingStore(initial = "A", failRead = true)
        val session = AuthSession(store, appScope).also { it.start() }

        session.awaitState { it is AuthState.LoggedOut }
        assertTrue(unhandled.isEmpty())
    }

    @Test
    fun `logout completes in memory even when clearing storage fails`() = runBlocking {
        val store = FailingStore(initial = "A", failLogout = true)
        var cleaned = false
        val session = AuthSession(store, appScope, onSessionCleared = { cleaned = true }).also { it.start() }
        session.awaitState { it is AuthState.LoggedIn }

        withTimeout(2_000) { session.logout().join() }

        assertEquals(AuthState.LoggedOut, session.authState.value)
        assertEquals(null, session.currentToken())
        assertTrue(cleaned)
        assertTrue(unhandled.isEmpty())
    }
}
