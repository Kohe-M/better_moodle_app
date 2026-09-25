package dev.rits.bettermoodle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 通知の既読同期。画面の rememberCoroutineScope ではなくセッション (アプリ) scope で実行し、
 * 画面遷移で要求がキャンセルされないようにする。成功・失敗・進行中を公開し、失敗は再試行できる。
 *
 * 要求はセッションに束縛する (S03):
 * - 予約時点の世代と認証トークンをスナップショットし、送信はそのトークンで行う (送信中に
 *   セッションが切り替わっても新しいトークンへ付け替わらない)
 * - 送信直前に世代と所有権 (自分の要求が inFlight に残っているか) を確認し、失われていれば送らない
 * - [clear] は表示状態だけでなく未実行・進行中の Job もキャンセルする
 * - 応答後も所有権を確認し、旧要求が新要求の inFlight を消さない
 */
class NotificationReadSync(
    private val scope: CoroutineScope,
    private val markRead: suspend (notificationId: Long, token: String) -> Boolean,
    private val currentGeneration: () -> Long,
    private val currentToken: () -> String?,
) {
    data class State(
        /** サーバーで既読になったことを確認できたID */
        val readIds: Set<Long> = emptySet(),
        /** 送信中のID → 要求トークン */
        val inFlight: Map<Long, Long> = emptyMap(),
        /** 失敗したID → 理由 */
        val failed: Map<Long, String> = emptyMap(),
    )

    /** トークンを扱わない旧形式 (テスト・監査プローブ互換)。送信は空トークンのスナップショットで行う。 */
    constructor(
        scope: CoroutineScope,
        markRead: suspend (notificationId: Long) -> Boolean,
        currentGeneration: () -> Long,
    ) : this(scope, { id, _ -> markRead(id) }, currentGeneration, { "" })

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val sequence = AtomicLong(0)
    private val jobs = mutableMapOf<Long, Job>()

    /** 既読要求を出す。既に既読確認済み・送信中・未ログインなら何もしない。 */
    fun request(notificationId: Long): Job? {
        if (notificationId <= 0L) return null
        val current = _state.value
        if (notificationId in current.readIds || notificationId in current.inFlight) return null
        val generation = currentGeneration()
        val authToken = currentToken() ?: return null
        val requestToken = sequence.incrementAndGet()
        _state.update { it.copy(inFlight = it.inFlight + (notificationId to requestToken), failed = it.failed - notificationId) }
        val job = scope.launch {
            // 送信直前の確認: 予約後にログアウト/再ログインされていたら旧セッションの要求は送らない
            if (!owns(notificationId, requestToken) || currentGeneration() != generation) {
                release(notificationId, requestToken)
                return@launch
            }
            val result = try {
                if (markRead(notificationId, authToken)) null else "Moodleが既読化を拒否しました"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (e) {
                    is MoodleWsException -> e.message ?: "Moodleエラー"
                    is MoodleResponseParseException -> "応答の形式が想定と異なります"
                    else -> "通信に失敗しました"
                }
            }
            _state.update { s ->
                // 自分の要求でなくなっていれば (clear や新セッションの要求に置き換わっていれば) 触らない
                if (s.inFlight[notificationId] != requestToken) return@update s
                // 応答が返る前にログアウト/再ログインされていたら、新しいセッションの状態に混ぜない
                if (currentGeneration() != generation) return@update s.copy(inFlight = s.inFlight - notificationId)
                if (result == null) {
                    s.copy(readIds = s.readIds + notificationId, inFlight = s.inFlight - notificationId)
                } else {
                    s.copy(inFlight = s.inFlight - notificationId, failed = s.failed + (notificationId to result))
                }
            }
        }
        synchronized(jobs) { jobs[notificationId] = job }
        job.invokeOnCompletion { synchronized(jobs) { if (jobs[notificationId] === job) jobs.remove(notificationId) } }
        return job
    }

    private fun owns(notificationId: Long, requestToken: Long): Boolean =
        _state.value.inFlight[notificationId] == requestToken

    private fun release(notificationId: Long, requestToken: Long) {
        _state.update { s -> if (s.inFlight[notificationId] == requestToken) s.copy(inFlight = s.inFlight - notificationId) else s }
    }

    /** ログアウト時などに状態を破棄し、未実行・進行中の要求をキャンセルする。 */
    fun clear() {
        _state.value = State()
        val pending = synchronized(jobs) { jobs.values.toList().also { jobs.clear() } }
        pending.forEach { it.cancel() }
    }
}
