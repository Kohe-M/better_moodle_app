package dev.rits.bettermoodle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

/**
 * コルーチンのキャンセルでOkHttp呼び出しも中断する実行 (ヘッダ受領まで)。
 * ブロッキングの execute() だと withTimeout や画面離脱によるキャンセルが
 * HTTPタイムアウトまで効かないため、enqueue ベースにする。
 *
 * - キャンセル時は [Call.cancel] を呼ぶ
 * - 継続が既にキャンセルされていて resume が無視される場合、および
 *   resume 後・実処理再開前にキャンセルされた場合は、onCancellation で
 *   未受領の [Response] を閉じて接続を返却する
 *
 * 本文の読み取りまで中断したい場合は [useResponse] を使う。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
    }

/**
 * ヘッダ受領から本文の消費・close までを一つのキャンセル対応処理にする。
 *
 * [block] は Dispatchers.IO 上でブロッキング読み取りしてよい。呼び出し元のコルーチンが
 * キャンセルされると [Call.cancel] でソケットを閉じ、ブロック中の read を IOException で
 * 解除する。その後は CancellationException として伝播するので、呼び出し側は通常の
 * キャンセルとして扱える。正常終了後は Call を cancel しない (接続を再利用できる)。
 */
suspend fun <T> Call.useResponse(block: (Response) -> T): T {
    val response = await()
    return response.use { resp ->
        coroutineScope {
            val finished = AtomicBoolean(false)
            // 親がキャンセルされた瞬間に Call を切る番人。正常完了時は何もしない。
            val guard = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) this@useResponse.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { block(resp) }
            } catch (e: IOException) {
                // ソケットを閉じたことによる IOException はキャンセルとして報告する
                coroutineContext.ensureActive()
                throw e
            } finally {
                finished.set(true)
                guard.cancel()
            }
        }
    }
}
