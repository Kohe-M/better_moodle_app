package dev.rits.bettermoodle.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** 画面ごとの簡易ローダ。refresh() で再読込。 */
@Composable
fun <T : Any> rememberLoadable(
    cacheKey: String? = null,
    loader: suspend () -> T,
): Pair<UiState<T>, () -> Unit> {
    var refreshCount by remember { mutableIntStateOf(0) }
    var state by remember(cacheKey) {
        mutableStateOf(LoadableMemoryCache.get<T>(cacheKey)?.let { UiState.Success(it, refreshing = true) } ?: UiState.Loading)
    }
    LaunchedEffect(cacheKey, refreshCount) {
        val cached = LoadableMemoryCache.get<T>(cacheKey)
        if (cached == null) {
            state = UiState.Loading
        } else {
            state = UiState.Success(cached, refreshing = true)
        }
        state = try {
            val loaded = loader()
            LoadableMemoryCache.put(cacheKey, loaded)
            UiState.Success(loaded)
        } catch (e: Exception) {
            if (cached != null) {
                UiState.Success(cached)
            } else {
                UiState.Error(e.message ?: "読み込みに失敗しました")
            }
        }
    }
    return state to { refreshCount++ }
}

object LoadableMemoryCache {
    private val entries = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String?): T? {
        if (key == null) return null
        return synchronized(entries) { entries[key] as? T }
    }

    fun put(key: String?, value: Any) {
        if (key == null) return
        synchronized(entries) { entries[key] = value }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
