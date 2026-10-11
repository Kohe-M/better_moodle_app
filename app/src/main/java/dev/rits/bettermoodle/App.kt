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
import dev.rits.bettermoodle.data.CourseColorStore
import dev.rits.bettermoodle.data.DeadlineDoneStore
import dev.rits.bettermoodle.data.FileDraftStore
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.NotificationReadSync
import dev.rits.bettermoodle.data.SessionStore
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.ui.LoadableMemoryCache
import dev.rits.bettermoodle.work.DeadlineWorker
import kotlinx.coroutines.CoroutineExceptionHandler
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
    /** 未捕捉の例外でプロセスを落とさない最終防衛 (個々の処理は自分で失敗を扱う。G04) */
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        android.util.Log.e("AppScope", "unhandled error in app scope", error)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
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

    /** 授業カラー (利用者の選択と時間割からの自動割り当て) */
    val courseColorStore = CourseColorStore(app)

    /** 締切の「アプリ内で完了扱い」 */
    val deadlineDoneStore = DeadlineDoneStore(app)

    /** 課題タブのバッジ: 24時間以内の未完了の締切の件数。締切を読み込んだ画面が更新する */
    val deadlineBadge = MutableStateFlow(0)

    init {
        authSession.start()
    }

    fun appContext(): android.content.Context = app

    /** 課題の下書きの保存先 (バックアップ対象外の私的領域。G02) */
    val draftDir: java.io.File get() = java.io.File(app.noBackupFilesDir, "assignment_drafts")

    /** 下書きファイルの書き込み専用スレッド (書き込み順を保つ) */
    val draftWriter: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 現在のアカウントの識別子 (下書きの所有者判定用。G02)。
     * Moodle のユーザーIDを優先する (同じ利用者が再ログインしても一致する)。取得できないとき
     * (オフラインで未保存など) はトークンのハッシュで代用する。未ログインなら null。
     */
    suspend fun currentAccount(): DraftAccount? {
        val token = authSession.currentToken() ?: return null
        val tokenHash = tokenHash(token)
        val userId = catchingNonCancel { sessionStore.cachedUserId(tokenHash) }
            ?: withTimeoutOrNull(USER_ID_TIMEOUT_MILLIS) {
                catchingNonCancel { moodleRepository.siteInfo().userid }
            }?.takeIf { it > 0L }?.also { id -> catchingNonCancel { sessionStore.saveUserId(tokenHash, id) } }
        return DraftAccount(
            id = userId?.let { "u:$it" } ?: "t:$tokenHash",
            // 旧版はトークンハッシュをそのまま識別子にしていた
            legacyIds = setOf(tokenHash, "t:$tokenHash"),
        )
    }

    /** 保存中の課題の下書きの件数 (ログアウト確認用) */
    fun savedDraftCount(): Int = FileDraftStore.count(draftDir)

    suspend fun syncTokenCache() = authSession.sync()

    /**
     * 利用者が明示的に行うログアウト。画面が破棄されても後片付けまで完了する。
     * 課題の下書きも削除する (invalidtoken による強制ログアウトでは残し、同じ利用者の再ログインで復元する)。
     */
    fun logout() {
        draftWriter.execute { FileDraftStore.deleteAll(draftDir) }
        authSession.logout()
    }

    private suspend fun clearSessionArtifacts() {
        LoadableMemoryCache.clear()
        deadlineDoneStore.clear()
        deadlineBadge.value = 0
        pendingDeepLink.value = null
        notificationReadSync.clear()
        moodleRepository.resetAutologinThrottle()
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
        private const val USER_ID_TIMEOUT_MILLIS = 5_000L

        fun tokenHash(token: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }

        private suspend fun <T> catchingNonCancel(block: suspend () -> T): T? =
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }
}

/** 下書きの所有者識別子。[legacyIds] は同じ利用者を指す旧形式の識別子 */
data class DraftAccount(val id: String, val legacyIds: Set<String>)
