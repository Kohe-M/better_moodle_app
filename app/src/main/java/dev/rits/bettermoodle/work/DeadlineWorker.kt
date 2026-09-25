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
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.data.UrlPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定期的に締切イベントを取得し、24時間以内に迫った課題をローカル通知する。
 *
 * - 取得開始時の認証世代を記録し、取得中・通知直前・通知済み保存直前にセッションを再確認する
 *   (旧アカウントの締切を新セッションで通知せず、通知済み集合にも混ぜない)
 * - 24時間分の取得を試みる ([dev.rits.bettermoodle.data.MoodleRepository.deadlinesWithin])。
 *   ページ上限やカーソル異常で終端を確認できなかった場合 (complete=false) は、取得できた分だけ通知し、
 *   通知済みキーは削らずに残す (次回実行で続きを拾えるようにする)。この場合も Result.success で終える:
 *   Result.retry は同じ応答に対して指数バックオフで繰り返すだけで、次の定期実行より早く解決しないため。
 * - 通知済み判定はイベントID+締切時刻なので、締切変更時は再通知する
 * - 通知タップで対象の課題を開けるよう、検証済みURLと二重消費防止キーをIntentに載せる
 */
class DeadlineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val container = app.container
        val store = container.sessionStore
        if (store.token() == null) return Result.success() // 未ログイン
        container.syncTokenCache()
        val generation = container.authGeneration
        if (!container.authSession.isCurrent(generation)) return Result.success()

        val now = Instant.now().epochSecond
        val until = now + NOTIFY_WINDOW_SECONDS
        // 前回ページ上限で打ち切っていれば、その続きから取得する (先頭から取り直して同じ位置で止まらない)
        val cursor = store.deadlineCursor()
        val fromEpochSec = cursor?.fromEpochSec ?: now
        val window = runCatching {
            container.moodleRepository.deadlinesWithin(fromEpochSec, until, afterEventId = cursor?.afterEventId)
        }.getOrElse { return Result.retry() }

        // 取得中にセッションが変わっていたら、この結果は別アカウントのものかもしれない
        if (!container.authSession.isCurrent(generation)) return Result.success()

        val deadlines = window.events
        val currentKeys = deadlines.map { it.deadlineNotificationKey() }.toSet()
        val soon = deadlines.filter { it.timesort in now..until }
        val hasPermission = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        // 通知の発行と通知済み集合・カーソルの保存は、セッション境界 (ログアウト/再ログイン) と同じ
        // Mutex の中で行う。世代確認→ログアウト→旧Workerが通知、という順序をここで排除する
        // null が返るのはセッションが変わっていた場合 (何もせず終了)
        container.authSession.withCurrentSession(generation) {
            val notified = store.notifiedEventIds()
            val fresh = soon.filter { it.deadlineNotificationKey() !in notified }
            val newlyNotified = mutableSetOf<String>()
            if (fresh.isNotEmpty() && hasPermission) {
                val fmt = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())
                val manager = NotificationManagerCompat.from(applicationContext)
                for (event in fresh) {
                    val pending = PendingIntent.getActivity(
                        applicationContext,
                        event.id.toInt(),
                        buildOpenIntent(applicationContext, event),
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
                    newlyNotified += event.deadlineNotificationKey()
                }
            }
            // 先頭から取り切った実行だけが通知済み集合を絞り込める。継続実行・不完全な実行は既存を残す
            val prune = shouldPruneNotifiedKeys(window, continuedFromCursor = cursor != null)
            val retainedCurrent = if (prune) currentKeys else currentKeys + notified
            store.replaceNotifiedEventIds(
                retainedNotifiedEventIds(
                    existingNotifiedIds = notified,
                    currentDeadlineIds = retainedCurrent,
                    newlyNotifiedIds = newlyNotified,
                ),
            )
            store.saveDeadlineCursor(nextDeadlineCursor(fromEpochSec, window))
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "deadline_reminder"
        const val EXTRA_OPEN_URL = "dev.rits.bettermoodle.extra.OPEN_URL"
        const val EXTRA_OPEN_TITLE = "dev.rits.bettermoodle.extra.OPEN_TITLE"
        const val EXTRA_OPEN_KEY = "dev.rits.bettermoodle.extra.OPEN_KEY"
        private const val NOTIFY_WINDOW_SECONDS = 24L * 3600L

        /** 通知タップ用Intent。宛先URLはMoodleホストのhttps URLに限る。 */
        fun buildOpenIntent(context: Context, event: ActionEvent): Intent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val url = notificationTargetUrl(event)
            if (url != null) {
                intent.putExtra(EXTRA_OPEN_URL, url)
                intent.putExtra(EXTRA_OPEN_TITLE, event.activityname ?: event.name)
                intent.putExtra(EXTRA_OPEN_KEY, event.deadlineNotificationKey())
                // 同じ宛先のPendingIntentを区別する
                intent.data = android.net.Uri.parse("bettermoodle://deadline/${event.id}")
            }
            return intent
        }

        /** 通知から開く宛先。action.url を優先し、Moodleホストの https のみ許可。 */
        fun notificationTargetUrl(event: ActionEvent): String? {
            val candidates = listOfNotNull(event.action?.url, event.url)
                .map { UrlPolicy.normalizeMoodleUrl(it) }
            return candidates.firstOrNull { UrlPolicy.isAllowedMoodleWebViewUrl(it) }
        }
    }
}
