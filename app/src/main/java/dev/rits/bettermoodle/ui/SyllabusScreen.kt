package dev.rits.bettermoodle.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import dev.rits.bettermoodle.data.SyllabusResolveResult
import dev.rits.bettermoodle.data.SyllabusSearchResult
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow

/**
 * シラバス画面。
 * - 検索欄: 5桁の授業コードなら直接シラバスを開く。それ以外はキーワード検索
 *   (Salesforce Aura APIを直接呼び出し、結果をアプリ内に一覧表示)
 * - 検索していないときは履修中コースの一覧から1タップでシラバスへ
 */
@Composable
fun SyllabusScreen(container: AppContainer) {
    val (state, refresh) = rememberLoadable("courses") { container.moodleRepository.enrolledCourses() }
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
            val result = container.syllabusRepository.resolve(code)
            resolvingCode = null
            when (result) {
                is SyllabusResolveResult.Found -> openInCustomTab(context, result.url)
                SyllabusResolveResult.NotFound -> errorMessage = "授業コード $code のシラバスが見つかりませんでした"
                // 通信失敗・仕様変更は「見つからない」と区別し、検索サイトへの導線を残す
                else -> errorMessage = "${result.failureMessage} (授業コード $code)。再試行するか「検索サイトを開く」から探してください"
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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("授業コード / キーワード", maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::runSearch, enabled = !searching && resolvingCode == null) {
                Text(if (searching) "検索中…" else "検索")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            MessageBanner(it, isError = true, onDismiss = { errorMessage = null })
            Spacer(Modifier.height(8.dp))
        }

        val currentResults = results
        when {
            searching -> LoadingBox()

            // キーワード検索の結果表示
            currentResults != null -> {
                if (currentResults.isEmpty()) {
                    EmptyBox("該当するシラバスが見つかりませんでした", icon = Icons.Outlined.SearchOff)
                } else {
                    SectionHeading("検索結果", modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                        MetaText("${currentResults.size}件")
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(ListItemSpacing)) {
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
            else -> Column {
            StaleDataBanner(state, onRetry = refresh)
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
                is UiState.Success -> {
                    val withCodes = s.data.filter { it.courseCode != null }
                    if (withCodes.isEmpty()) {
                        EmptyBox("授業コード付きの履修コースが見つかりません", onRetry = refresh, icon = Icons.Outlined.School)
                    } else {
                        SectionHeading("履修中のコース", modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                            MetaText("タップでシラバスを開く")
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(ListItemSpacing)) {
                            items(withCodes, key = { it.id }) { course ->
                                AppCard(
                                    onClick = { course.courseCode?.let(::openByCode) },
                                    enabled = resolvingCode == null,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        LeadingIconBox(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                course.displayName,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            MetaText("授業コード ${course.courseCode}", modifier = Modifier.padding(top = 2.dp))
                                        }
                                        if (resolvingCode == course.courseCode) {
                                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Outlined.OpenInNew,
                                                contentDescription = "シラバスを開く",
                                                tint = MaterialTheme.colorScheme.outline,
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
}

@Composable
private fun SyllabusResultCard(record: SyllabusRecord, onOpen: () -> Unit) {
    AppCard(onClick = onOpen) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    record.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 教員名は本文の色で、開講情報はさらに薄い色で段階を付ける
                record.teacher?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                val details = listOfNotNull(
                    record.weekDayPeriod,
                    record.campus,
                    record.term,
                    record.credits?.let { "${it}単位" },
                ).joinToString(" · ")
                if (details.isNotBlank()) MetaText(details, maxLines = 2)
            }
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
