package dev.rits.bettermoodle.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.rits.bettermoodle.App
import dev.rits.bettermoodle.MainActivity
import dev.rits.bettermoodle.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定期的に締切イベントを取得し、24時間以内に迫った課題をローカル通知する。
 */
class DeadlineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val store = app.container.sessionStore
        if (store.token() == null) return Result.success() // 未ログイン
        app.container.syncTokenCache()

        val now = Instant.now().epochSecond
        val deadlines = runCatching {
            app.container.moodleRepository.upcomingDeadlines(now)
        }.getOrElse { return Result.retry() }

        val currentDeadlineIds = deadlines.map { it.id.toString() }.toSet()
        val soon = deadlines.filter { it.timesort in now..(now + 24 * 3600) }
        val notified = store.notifiedEventIds()
        val fresh = soon.filter { it.id.toString() !in notified }
        val newlyNotified = mutableSetOf<String>()

        if (fresh.isNotEmpty() && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val fmt = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())
            val manager = NotificationManagerCompat.from(applicationContext)
            for (event in fresh) {
                val intent = Intent(applicationContext, MainActivity::class.java)
                val pending = PendingIntent.getActivity(
                    applicationContext, event.id.toInt(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(applicationContext, App.DEADLINE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("締切: ${event.activityname ?: event.name}")
                    .setContentText("${event.course?.fullname ?: ""} — ${fmt.format(Instant.ofEpochSecond(event.timesort))} まで")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
                manager.notify(event.id.toInt(), notification)
                newlyNotified += event.id.toString()
            }
        }
        store.replaceNotifiedEventIds(
            retainedNotifiedEventIds(
                existingNotifiedIds = notified,
                currentDeadlineIds = currentDeadlineIds,
                newlyNotifiedIds = newlyNotified,
            ),
        )
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "deadline_reminder"
    }
}
