package dev.rits.bettermoodle.ui

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Success<T>(
        val data: T,
        val refreshing: Boolean = false,
        /** 最終成功日時 (epoch millis)。null は不明 */
        val loadedAtMillis: Long? = null,
        /** キャッシュ表示中に再読込が失敗した理由。null なら最新 */
        val staleError: String? = null,
    ) : UiState<T>
}

val UiState<*>.isRefreshing: Boolean
    get() = (this as? UiState.Success<*>)?.refreshing == true

val UiState<*>.isStale: Boolean
    get() = (this as? UiState.Success<*>)?.staleError != null
