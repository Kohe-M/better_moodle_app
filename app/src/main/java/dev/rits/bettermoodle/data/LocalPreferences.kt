package dev.rits.bettermoodle.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 利用者が選んだ授業カラーと、時間割から決めた自動割り当て。
 * 画面は [state] を読んで同じ授業を同じ色で描く。
 */
class CourseColorStore(context: Context) {
    private val prefs = context.getSharedPreferences("course_colors", Context.MODE_PRIVATE)

    data class State(
        /** 利用者が選んだ色 (コースキー → パレットのインデックス) */
        val overrides: Map<String, Int> = emptyMap(),
        /** 時間割から決めた色。時間割にない授業はハッシュで決める */
        val assigned: Map<String, Int> = emptyMap(),
    ) {
        fun indexFor(key: String): Int = overrides[key] ?: assigned[key] ?: CourseColors.hashIndex(key)
    }

    private val _state = MutableStateFlow(State(overrides = readOverrides()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var lastEntries: List<TimetableEntry> = emptyList()

    private fun readOverrides(): Map<String, Int> =
        prefs.all.mapNotNull { (k, v) -> (v as? Int)?.takeIf { it in 0 until CourseColors.PALETTE_SIZE }?.let { k to it } }
            .toMap()

    /** 時間割を読み込んだら呼ぶ。隣接コマの色ずらしをやり直す */
    fun updateTimetable(entries: List<TimetableEntry>) {
        lastEntries = entries
        _state.value = _state.value.copy(assigned = CourseColors.assign(entries, _state.value.overrides))
    }

    fun setOverride(key: String, index: Int?) {
        prefs.edit().apply { if (index == null) remove(key) else putInt(key, index) }.apply()
        val overrides = readOverrides()
        _state.value = State(overrides = overrides, assigned = CourseColors.assign(lastEntries, overrides))
    }
}

/**
 * 締切を「アプリ内で完了扱い」にした記録 (DESIGN.md §8.3)。Moodle 上の提出とは別物で、
 * 画面では提出済み (success の ✓) と区別して ink-secondary の ✓ で描く。
 */
class DeadlineDoneStore(context: Context) {
    private val prefs = context.getSharedPreferences("deadline_done", Context.MODE_PRIVATE)

    private val _done = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val done: StateFlow<Set<String>> = _done.asStateFlow()

    fun setDone(eventKey: String, done: Boolean) {
        val next = if (done) _done.value + eventKey else _done.value - eventKey
        _done.value = next
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun clear() {
        _done.value = emptySet()
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY = "done"

        fun keyOf(event: ActionEvent): String = "e:${event.id}"
    }
}
