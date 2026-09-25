package dev.rits.bettermoodle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 画面ごとの簡易ローダ。refresh() で再読込。
 *
 * キャッシュがある状態で再読込に失敗した場合は、キャッシュを表示したまま
 * [UiState.Success.staleError] に失敗理由と [UiState.Success.loadedAtMillis] に
 * 最終成功日時を載せる (古いデータを「最新」と誤認させない)。
 */
@Composable
fun <T : Any> rememberLoadable(
    cacheKey: String? = null,
    loader: suspend () -> T,
): Pair<UiState<T>, () -> Unit> {
    var refreshCount by remember { mutableIntStateOf(0) }
    var state by remember(cacheKey) {
        mutableStateOf(
            LoadableMemoryCache.get<T>(cacheKey)
                ?.let { UiState.Success(it.value, refreshing = true, loadedAtMillis = it.loadedAtMillis) }
                ?: UiState.Loading,
        )
    }
    LaunchedEffect(cacheKey, refreshCount) {
        val cached = LoadableMemoryCache.get<T>(cacheKey)
        state = if (cached == null) {
            UiState.Loading
        } else {
            UiState.Success(cached.value, refreshing = true, loadedAtMillis = cached.loadedAtMillis)
        }
        state = try {
            val loaded = loader()
            val now = System.currentTimeMillis()
            LoadableMemoryCache.put(cacheKey, loaded, now)
            UiState.Success(loaded, loadedAtMillis = now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val message = loadErrorMessage(e)
            if (cached != null) {
                UiState.Success(cached.value, loadedAtMillis = cached.loadedAtMillis, staleError = message)
            } else {
                UiState.Error(message)
            }
        }
    }
    return state to { refreshCount++ }
}

fun loadErrorMessage(e: Exception): String = when (e) {
    is dev.rits.bettermoodle.data.MoodleWsException -> e.message ?: "Moodleエラー"
    is dev.rits.bettermoodle.data.MoodleHttpException -> "サーバーエラー (HTTP ${e.status})"
    is java.io.IOException -> "通信に失敗しました"
    else -> e.message ?: "読み込みに失敗しました"
}

object LoadableMemoryCache {
    data class Entry<T : Any>(val value: T, val loadedAtMillis: Long)

    private val entries = mutableMapOf<String, Entry<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String?): Entry<T>? {
        if (key == null) return null
        return synchronized(entries) { entries[key] as? Entry<T> }
    }

    fun put(key: String?, value: Any, loadedAtMillis: Long = System.currentTimeMillis()) {
        if (key == null) return
        synchronized(entries) { entries[key] = Entry(value, loadedAtMillis) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
