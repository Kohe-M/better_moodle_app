package dev.rits.bettermoodle

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.SessionStore
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.ui.LoadableMemoryCache
import dev.rits.bettermoodle.work.DeadlineWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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

class AppContainer(private val app: Application) {
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    val sessionStore = SessionStore(app)
    @Volatile private var currentToken: String? = null
    val moodleClient = MoodleClient(
        tokenProvider = { currentToken },
        privateTokenProvider = { sessionStore.privateToken() },
        onAuthError = { appScope.launch { clearAuthTokens() } },
    )
    val moodleRepository = MoodleRepository(moodleClient)
    val syllabusRepository = SyllabusRepository(sessionStore)

    init {
        appScope.launch {
            sessionStore.tokenFlow.collect { currentToken = it }
        }
    }

    fun appContext(): android.content.Context = app

    suspend fun syncTokenCache() {
        currentToken = sessionStore.token()
    }

    suspend fun logout() {
        sessionStore.logout()
        LoadableMemoryCache.clear()
        clearAllWebSessions()
        currentToken = null
    }

    suspend fun clearAuthTokens() {
        sessionStore.clearAuthTokens()
        LoadableMemoryCache.clear()
        currentToken = null
        clearAllWebSessions()
    }

    suspend fun clearAllWebSessions() = withContext(Dispatchers.Main) {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }
}
