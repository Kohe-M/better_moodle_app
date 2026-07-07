package dev.rits.bettermoodle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.SyllabusRecord
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.data.SyllabusSearchResult
import kotlinx.coroutines.launch

/**
 * シラバス画面。
 * - 検索欄: 5桁の授業コードなら直接シラバスを開く。それ以外はキーワード検索
 *   (Salesforce Aura APIを直接呼び出し、結果をアプリ内に一覧表示)
 * - 検索していないときは履修中コースの一覧から1タップでシラバスへ
 */
@Composable
fun SyllabusScreen(container: AppContainer) {
    val (state, refresh) = rememberLoadable { container.moodleRepository.enrolledCourses() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SyllabusRecord>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resolvingCode by remember { mutableStateOf<String?>(null) }

    fun openByCode(code: String) {
        scope.launch {
            resolvingCode = code
            errorMessage = null
            val url = container.syllabusRepository.resolveUrl(code)
            resolvingCode = null
            if (url != null) {
                openInCustomTab(context, url)
            } else {
                errorMessage = "授業コード $code のシラバスが見つかりませんでした"
            }
        }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            return
        }
        if (q.matches(Regex("\\d{5}"))) {
            openByCode(q)
            return
        }
        scope.launch {
            searching = true
            errorMessage = null
            when (val result = container.syllabusRepository.searchResult(q)) {
                is SyllabusSearchResult.Success -> results = result.records
                is SyllabusSearchResult.NoResults -> results = emptyList()
                is SyllabusSearchResult.NetworkError -> {
                    results = null
                    errorMessage = "シラバス検索の通信に失敗しました"
                }
                is SyllabusSearchResult.InvalidResponse -> {
                    results = null
                    errorMessage = "シラバス検索の応答形式が変わった可能性があります"
                }
            }
            searching = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("授業コード(5桁) または キーワード") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::runSearch, enabled = !searching && resolvingCode == null) {
                Text(if (searching) "検索中…" else "検索")
            }
        }
        Row {
            TextButton(onClick = { openInCustomTab(context, SyllabusRepository.SYLLABUS_SEARCH_URL) }) {
                Text("検索サイトを開く ↗")
            }
            if (results != null) {
                TextButton(onClick = { results = null; query = "" }) {
                    Text("履修コース一覧に戻る")
                }
            }
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        val currentResults = results
        when {
            searching -> LoadingBox()

            // キーワード検索の結果表示
            currentResults != null -> {
                if (currentResults.isEmpty()) {
                    ErrorBox("該当するシラバスが見つかりませんでした")
                } else {
                    LazyColumn {
                        items(currentResults, key = { it.id }) { record ->
                            SyllabusResultCard(record) {
                                val url = container.syllabusRepository.detailUrl(record)
                                if (url != null) openInCustomTab(context, url)
                            }
                        }
                    }
                }
            }

            // 通常時: 履修中コース一覧
            else -> when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
                is UiState.Success -> {
                    val withCodes = s.data.filter { it.courseCode != null }
                    if (withCodes.isEmpty()) {
                        ErrorBox("授業コード付きの履修コースが見つかりません", onRetry = refresh)
                    } else {
                        LazyColumn {
                            items(withCodes, key = { it.id }) { course ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable(enabled = resolvingCode == null) {
                                            course.courseCode?.let(::openByCode)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(course.displayName, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                "コード: ${course.courseCode}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (resolvingCode == course.courseCode) {
                                            CircularProgressIndicator(modifier = Modifier.width(20.dp))
                                        } else {
                                            AssistChip(
                                                onClick = { course.courseCode?.let(::openByCode) },
                                                label = { Text("シラバス") },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyllabusResultCard(record: SyllabusRecord, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(record.courseName, style = MaterialTheme.typography.bodyLarge)
            val details = listOfNotNull(
                record.teacher,
                record.weekDayPeriod,
                record.campus,
                record.term,
                record.credits?.let { "${it}単位" },
            ).joinToString(" / ")
            if (details.isNotBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
