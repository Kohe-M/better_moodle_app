package dev.rits.bettermoodle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.SyllabusRecord
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.data.SyllabusResolveResult
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
                else -> errorMessage = "${result.failureMessage} (授業コード $code)。もう一度試すか、「シラバス検索サイトで詳しく探す」から探してください"
            }
        }
    }

    fun runSearch() {
        // 検索ボタンがなくキーボードの検索キーで何度でも呼べるので、実行中は受け付けない
        // (後から返った古い応答で結果を上書きしない)
        if (searching || resolvingCode != null) return
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

    val currentResults = results

    Column(modifier = Modifier.fillMaxSize()) {
        // 検索欄が主操作。検索ボタンは置かず、キーボードの検索キーで実行する
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("授業コード(5桁)か科目名・教員名", maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    searching || resolvingCode != null ->
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    query.isNotEmpty() -> IconButton(onClick = { query = ""; results = null; errorMessage = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "検索をやめる")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenGutter, vertical = 8.dp),
        )
        errorMessage?.let {
            Column(Modifier.padding(horizontal = ScreenGutter, vertical = 4.dp)) {
                MessageBanner(it, isError = true, onDismiss = { errorMessage = null })
                // 一覧の末尾のリンクは履修コースの読み込み中・失敗時には出ないので、失敗の案内のすぐ下にも置く
                SyllabusSiteLink(context)
            }
        }

        when {
            searching -> LoadingBox()

            // キーワード検索の結果
            currentResults != null -> {
                if (currentResults.isEmpty()) {
                    EmptyBox(
                        "該当するシラバスはありません",
                        "別のキーワードか、授業コード5桁で探してください。",
                        onRetry = { openInCustomTab(context, SyllabusRepository.SYLLABUS_SEARCH_URL) },
                        retryLabel = "シラバス検索サイトで探す",
                    )
                } else {
                    LazyColumn(contentPadding = RowListPadding) {
                        item(key = "results-header") { SectionLabel("検索結果 ${currentResults.size}件") }
                        items(currentResults, key = { it.id }) { record ->
                            SyllabusResultRow(record) {
                                val url = container.syllabusRepository.detailUrl(record)
                                if (url != null) openInCustomTab(context, url)
                            }
                            RowDivider()
                        }
                        item(key = "site") { SyllabusSiteLink(context) }
                    }
                }
            }

            // 通常時: 履修中の授業から1タップでシラバスへ
            else -> Column {
                StaleDataBanner(state, onRetry = refresh)
                when (val s = state) {
                    is UiState.Loading -> LoadingBox()
                    is UiState.Error -> ErrorBox(
                        "${s.message}。履修中の授業の一覧を表示できません。上の欄から検索はできます。",
                        onRetry = refresh,
                        title = "履修中の授業を読み込めませんでした",
                    )
                    is UiState.Success -> {
                        val withCodes = s.data.filter { it.courseCode != null }
                        LazyColumn(contentPadding = RowListPadding) {
                            if (withCodes.isNotEmpty()) {
                                item(key = "courses-header") {
                                    SectionLabel("履修中の授業") { MetaText("ブラウザで開きます") }
                                }
                                items(withCodes, key = { it.id }) { course ->
                                    ListRow(
                                        onClick = { course.courseCode?.let(::openByCode) },
                                        enabled = resolvingCode == null,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            course.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        if (resolvingCode == course.courseCode) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            MetaText(course.courseCode.orEmpty())
                                        }
                                    }
                                    RowDivider()
                                }
                            }
                            item(key = "site") { SyllabusSiteLink(context) }
                        }
                    }
                }
            }
        }
    }
}

/** 一覧の末尾に置く外部検索サイトへの導線 (まれにしか使わないので上部に常設しない) */
@Composable
private fun SyllabusSiteLink(context: android.content.Context) {
    TextButton(
        onClick = { openInCustomTab(context, SyllabusRepository.SYLLABUS_SEARCH_URL) },
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
    ) {
        Text("シラバス検索サイトで詳しく探す")
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

/** 検索結果の1行: 科目名 / 教員 / 曜日時限・キャンパス・学期・単位 */
@Composable
private fun SyllabusResultRow(record: SyllabusRecord, onOpen: () -> Unit) {
    ListRow(onClick = onOpen) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                record.courseName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            record.teacher?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val details = listOfNotNull(
                record.weekDayPeriod,
                record.campus,
                record.term,
                record.credits?.let { "${it}単位" },
            ).joinToString(" · ")
            if (details.isNotBlank()) MetaText(details, maxLines = 2)
        }
    }
}
