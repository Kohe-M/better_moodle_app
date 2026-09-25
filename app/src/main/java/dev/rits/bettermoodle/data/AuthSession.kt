package dev.rits.bettermoodle.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 認証トークンの永続化。テストでは遅延・失敗を注入できるよう差し替える。 */
interface AuthTokenStore {
    val tokenFlow: Flow<String?>
    suspend fun token(): String?
    suspend fun saveLogin(token: String, privateToken: String?)
    suspend fun clearAuthTokens()
    suspend fun logout()
}

/**
 * 認証状態。トークン文字列そのものではなく「世代」で識別する。
 * ログイン・ログアウトのたびに世代が進み、旧世代のリクエストが返す invalidtoken や
 * 旧世代のWorkerが取得した締切は現在のセッションに影響させない。
 */
sealed interface AuthState {
    /** DataStore 読み込み待ち */
    data object Unknown : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val generation: Long) : AuthState
}

/**
 * セッション境界を一箇所で管理する。
 *
 * - ログイン・ログアウト・認証エラー処理・DataStore 同期は同じ Mutex 下で直列に行う
 * - ログアウト/消去は画面の scope ではなくアプリ scope (NonCancellable) が所有し、
 *   永続状態→メモリ→Cookie/通知の後片付けを終えてから LoggedOut を発行する
 * - 認証エラーは「失敗したトークンが今も現在のトークンか」を破壊操作の直前 (ロック内) に確認する
 */
class AuthSession(
    private val store: AuthTokenStore,
    private val scope: CoroutineScope,
    /** ログアウト・認証消去時の後片付け (メモリキャッシュ・Cookie・通知など) */
    private val onSessionCleared: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    /** リクエストに使うトークン。ログアウト開始時点で即 null にして以降の通信を止める */
    @Volatile private var currentToken: String? = null

    /** 状態 (世代) に反映済みのトークン */
    private var appliedToken: String? = null

    /** 現在の認証世代。0 は未初期化/未ログイン。 */
    @Volatile var authGeneration: Long = 0L
        private set

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)

    /** 画面切替用。トークンキャッシュ更新「後」に発行されるので、認証済み画面の最初の取得が notoken にならない。 */
    val authState: StateFlow<AuthState> = _authState

    fun currentToken(): String? = currentToken

    /** 現在も同じセッションか (Worker などが取得結果を適用する直前に確認する) */
    fun isCurrent(generation: Long): Boolean = authGeneration == generation && currentToken != null

    /**
     * 永続化されたトークンの購読を開始する。
     * 発行された値ではなくロック内で読み直した現在値を適用する (ログアウト完了後に、
     * その前に発行されていた旧トークンの通知が遅れて処理されても再ログイン状態にならない)。
     */
    fun start(): Job = scope.launch {
        store.tokenFlow.collect { mutex.withLock { applyTokenLocked(store.token()) } }
    }

    /** 永続化と状態更新を同じ排他区間で行う (保存直後に旧 invalidtoken が届いても消されない) */
    fun login(token: String, privateToken: String?): Job = scope.launch(NonCancellable) {
        mutex.withLock {
            store.saveLogin(token, privateToken)
            applyTokenLocked(token)
        }
    }

    /** ログアウト。呼び出し元の画面が破棄されても完了する。 */
    fun logout(): Job = scope.launch(NonCancellable) {
        mutex.withLock { clearLocked(clearAll = true) }
    }

    /**
     * invalidtoken を受けたトークンが、削除を実行する時点でも現在のものと一致する場合だけ認証を消す。
     */
    fun onAuthError(failedToken: String): Job = scope.launch(NonCancellable) {
        mutex.withLock {
            if (failedToken != currentToken) return@withLock
            clearLocked(clearAll = false)
        }
    }

    /** DataStore の内容をトークンキャッシュへ同期する (Worker の起動時など) */
    suspend fun sync() {
        mutex.withLock { applyTokenLocked(store.token()) }
    }

    /**
     * 世代 [generation] のセッションが現在も有効なときだけ [block] を実行する。
     * ログイン/ログアウト/認証消去と同じ Mutex を取るので、block の実行中にセッション境界が
     * 動くことはない (Worker の通知発行・通知済み保存など、セッションに紐づく副作用に使う)。
     * セッションが変わっていれば block を実行せず null を返す。
     */
    suspend fun <T> withCurrentSession(generation: Long, block: suspend () -> T): T? =
        mutex.withLock {
            if (!isCurrent(generation)) null else block()
        }

    private suspend fun clearLocked(clearAll: Boolean) {
        currentToken = null
        try {
            if (clearAll) store.logout() else store.clearAuthTokens()
        } finally {
            runCatching { onSessionCleared() }
            applyTokenLocked(null)
        }
    }

    private fun applyTokenLocked(token: String?) {
        val changed = token != appliedToken || _authState.value is AuthState.Unknown
        appliedToken = token
        currentToken = token
        if (changed) authGeneration += 1
        _authState.value = if (token == null) AuthState.LoggedOut else AuthState.LoggedIn(authGeneration)
    }
}
