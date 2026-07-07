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
fun <T> rememberLoadable(loader: suspend () -> T): Pair<UiState<T>, () -> Unit> {
    var refreshCount by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState<T>>(UiState.Loading) }
    LaunchedEffect(refreshCount) {
        state = UiState.Loading
        state = try {
            UiState.Success(loader())
        } catch (e: Exception) {
            UiState.Error(e.message ?: "読み込みに失敗しました")
        }
    }
    return state to { refreshCount++ }
}
