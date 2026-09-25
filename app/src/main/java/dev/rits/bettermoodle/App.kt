package dev.rits.bettermoodle

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.rits.bettermoodle.data.AuthSession
import dev.rits.bettermoodle.data.AuthState
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.NotificationReadSync
import dev.rits.bettermoodle.data.SessionStore
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.ui.LoadableMemoryCache
import dev.rits.bettermoodle.work.DeadlineWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
        scheduleDeadlineWorker()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DEADLINE_CHANNEL_ID,
            "課題の締切",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "課題・小テストの締切リマインダー"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scheduleDeadlineWorker() {
        val request = PeriodicWorkRequestBuilder<DeadlineWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DeadlineWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val DEADLINE_CHANNEL_ID = "deadlines"
    }
}

/**
 * 通知タップなどで起動時に開くべき宛先 (検証済みURL)。
 * [key] は同じ通知の二重消費を防ぐ識別子 (イベントID+締切時刻)。
 * [explicit] は利用者がいま明示的に開いた (onNewIntent / 新規起動) ことを示す。
 * プロセス再生成による OS の再配達 (false) だけを消費済みキーで抑止し、明示的な再タップは常に開く。
 */
data class PendingDeepLink(val url: String, val title: String, val key: String, val explicit: Boolean)

class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessionStore = SessionStore(app)

    /** セッション境界の唯一の所有者。ログイン/ログアウト/認証エラーはここを通す。 */
    val authSession = AuthSession(
        store = sessionStore,
        scope = appScope,
        onSessionCleared = { clearSessionArtifacts() },
    )

    val authState: StateFlow<AuthState> get() = authSession.authState
    val authGeneration: Long get() = authSession.authGeneration

    /** 通知タップなどで受け取った宛先。ログイン後に消費される。 */
    val pendingDeepLink = MutableStateFlow<PendingDeepLink?>(null)

    val moodleClient = MoodleClient(
        tokenProvider = { authSession.currentToken() },
        privateTokenProvider = { sessionStore.privateToken() },
        onAuthError = { failedToken -> authSession.onAuthError(failedToken) },
    )
    val moodleRepository = MoodleRepository(moodleClient)
    val syllabusRepository = SyllabusRepository(sessionStore)

    /** 通知の既読同期。画面の寿命に依存せずアプリ scope で完了させる。 */
    val notificationReadSync = NotificationReadSync(
        scope = appScope,
        // 予約時点のトークンで送る (送信中にセッションが切り替わっても新トークンへ付け替わらない)
        markRead = { id, token -> moodleRepository.markNotificationRead(id, token) },
        currentGeneration = { authSession.authGeneration },
        currentToken = { authSession.currentToken() },
    )

    init {
        authSession.start()
    }

    fun appContext(): android.content.Context = app

    suspend fun syncTokenCache() = authSession.sync()

    /** ログアウト。画面が破棄されても後片付けまで完了する。 */
    fun logout() = authSession.logout()

    private suspend fun clearSessionArtifacts() {
        LoadableMemoryCache.clear()
        pendingDeepLink.value = null
        notificationReadSync.clear()
        runCatching { NotificationManagerCompat.from(app).cancelAll() }
        clearAllWebSessions()
    }

    /** Cookie削除の完了を待ってから戻る (直後の再読込との競合防止) */
    suspend fun clearAllWebSessions() = withContext(Dispatchers.Main) {
        withTimeoutOrNull(COOKIE_CLEAR_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    CookieManager.getInstance().removeAllCookies { if (cont.isActive) cont.resume(Unit) }
                }.onFailure { if (cont.isActive) cont.resume(Unit) }
            }
        }
        runCatching { CookieManager.getInstance().flush() }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    companion object {
        private const val COOKIE_CLEAR_TIMEOUT_MILLIS = 3_000L
    }
}
