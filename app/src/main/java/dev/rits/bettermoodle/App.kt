package dev.rits.bettermoodle

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleRepository
import dev.rits.bettermoodle.data.SessionStore
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.work.DeadlineWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        ).apply { description = "課題・小テストの締切リマインダー" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scheduleDeadlineWorker() {
        val request = PeriodicWorkRequestBuilder<DeadlineWorker>(6, TimeUnit.HOURS).build()
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
    val moodleClient = MoodleClient(
        tokenProvider = { runBlocking { sessionStore.token() } },
        onAuthError = { appScope.launch { sessionStore.clearAuthTokens() } },
    )
    val moodleRepository = MoodleRepository(moodleClient)
    val syllabusRepository = SyllabusRepository(sessionStore)

    fun appContext(): android.content.Context = app
}
