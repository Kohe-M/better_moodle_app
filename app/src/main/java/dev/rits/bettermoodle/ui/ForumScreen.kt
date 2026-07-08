package dev.rits.bettermoodle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.BuildConfig
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.ForumDiscussion
import dev.rits.bettermoodle.data.ForumLoadErrorKind
import dev.rits.bettermoodle.data.ForumPost
import dev.rits.bettermoodle.data.ForumTarget
import dev.rits.bettermoodle.data.canFallbackToWebView
import dev.rits.bettermoodle.data.classifyForumLoadError
import dev.rits.bettermoodle.data.discussionIdForPosts
import dev.rits.bettermoodle.data.diagnosticCode
import dev.rits.bettermoodle.data.forumHttpStatus
import dev.rits.bettermoodle.data.forumLoadErrorMessage
import dev.rits.bettermoodle.data.forumMoodleErrorCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumScreen(
    container: AppContainer,
    target: ForumTarget,
    title: String,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onFallbackWeb: () -> Unit,
) {
    var selected by remember { mutableStateOf<ForumDiscussion?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var discussionState by remember { mutableStateOf<UiState<List<ForumDiscussion>>>(UiState.Loading) }
    var lastDiagnostic by remember { mutableStateOf<ForumDiagnostic?>(null) }

    fun refreshDiscussions() {
        refreshTick++
    }

    LaunchedEffect(target, refreshTick) {
        if (!target.isValid) {
            lastDiagnostic = ForumDiagnostic(
                stage = "TARGET",
                wsFunction = null,
                kind = ForumLoadErrorKind.InvalidForumTarget,
                moodleErrorCode = null,
                httpStatus = null,
                target = target,
            )
            discussionState = UiState.Error("フォーラムを読み込めませんでした。再試行してください")
            return@LaunchedEffect
        }
        discussionState = UiState.Loading
        lastDiagnostic = null
        discussionState = try {
            UiState.Success(container.moodleRepository.forumDiscussions(target.forumInstanceId))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val kind = classifyForumLoadError(error)
            lastDiagnostic = ForumDiagnostic(
                stage = "DISCUSSION_LIST",
                wsFunction = "mod_forum_get_forum_discussions",
                kind = kind,
                moodleErrorCode = forumMoodleErrorCode(error),
                httpStatus = forumHttpStatus(error),
                target = target,
            )
            UiState.Error(forumLoadErrorMessage(kind))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.subject?.ifBlank { title } ?: title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) selected = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        refreshDiscussions()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
                    }
                },
            )
        },
    ) { padding ->
        if (selected == null) {
            when (val s = discussionState) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(
                    s.message,
                    onRetry = { refreshDiscussions() },
                )
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        ErrorBox("ディスカッションはありません。", onRetry = { refreshDiscussions() })
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(s.data, key = { it.id }) { discussion ->
                                DiscussionCard(discussion) { selected = discussion }
                            }
                            item {
                                Text(
                                    "新規投稿・返信は、対応するMoodle Web Serviceを確認できるまでアプリ内WebViewで開きます。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onFallbackWeb)
                                        .padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (discussionState is UiState.Error) {
                ForumErrorActions(
                    diagnostic = lastDiagnostic,
                    onFallbackWeb = onFallbackWeb,
                )
            }
        } else {
            DiscussionPosts(
                container = container,
                discussionId = selected!!.discussionIdForPosts(),
                diagnosticTarget = target,
                refreshTick = refreshTick,
                onOpenUrl = onOpenUrl,
                onFallbackWeb = onFallbackWeb,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumDiscussionScreen(
    container: AppContainer,
    discussionId: Long,
    title: String,
    fallbackUrl: String,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onFallbackWeb: (String, String) -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val screenTitle = title.ifBlank { "フォーラム" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読込")
                    }
                },
            )
        },
    ) { padding ->
        DiscussionPosts(
            container = container,
            discussionId = discussionId,
            diagnosticTarget = null,
            refreshTick = refreshTick,
            onOpenUrl = onOpenUrl,
            onFallbackWeb = { onFallbackWeb(fallbackUrl, screenTitle) },
            alwaysAllowFallback = fallbackUrl.isNotBlank(),
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun DiscussionCard(discussion: ForumDiscussion, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (discussion.numunread > 0) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(discussion.subject.ifBlank { discussion.name }, fontWeight = FontWeight.Bold)
            Text(
                "${discussion.userfullname} / 返信 ${discussion.numreplies} / 未読 ${discussion.numunread}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            htmlToPlainText(discussion.message).takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiscussionPosts(
    container: AppContainer,
    discussionId: Long,
    diagnosticTarget: ForumTarget?,
    refreshTick: Int,
    onOpenUrl: (String, String) -> Unit,
    onFallbackWeb: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysAllowFallback: Boolean = false,
) {
    var diagnostic by remember { mutableStateOf<ForumDiagnostic?>(null) }
    val (state, refresh) = rememberLoadable("forumPosts:$discussionId") {
        try {
            diagnostic = null
            container.moodleRepository.forumPosts(discussionId)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val kind = classifyForumLoadError(error)
            diagnostic = ForumDiagnostic(
                stage = "DISCUSSION_POSTS",
                wsFunction = "mod_forum_get_discussion_posts",
                kind = kind,
                moodleErrorCode = forumMoodleErrorCode(error),
                httpStatus = forumHttpStatus(error),
                target = diagnosticTarget,
            )
            throw IllegalStateException(forumLoadErrorMessage(kind))
        }
    }
    var observedRefreshTick by remember(discussionId) { mutableIntStateOf(refreshTick) }
    LaunchedEffect(discussionId, refreshTick) {
        if (refreshTick != observedRefreshTick) {
            observedRefreshTick = refreshTick
            refresh()
        }
    }

    when (val s = state) {
        is UiState.Loading -> LoadingBox()
        is UiState.Error -> {
            ErrorBox(s.message, onRetry = refresh)
            ForumErrorActions(
                diagnostic = diagnostic,
                onFallbackWeb = onFallbackWeb,
                alwaysAllowFallback = alwaysAllowFallback,
            )
        }
        is UiState.Success -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(s.data, key = { it.id }) { post ->
                PostCard(post, onOpenUrl)
            }
        }
    }
}

private data class ForumDiagnostic(
    val stage: String,
    val wsFunction: String?,
    val kind: ForumLoadErrorKind,
    val moodleErrorCode: String?,
    val httpStatus: Int?,
    val target: ForumTarget?,
)

@Composable
private fun ForumErrorActions(
    diagnostic: ForumDiagnostic?,
    onFallbackWeb: () -> Unit,
    alwaysAllowFallback: Boolean = false,
) {
    val canFallback = alwaysAllowFallback || diagnostic?.kind?.canFallbackToWebView() == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canFallback) {
            Button(onClick = onFallbackWeb, modifier = Modifier.fillMaxWidth()) {
                Text("Moodle画面で開く")
            }
        }
        if (BuildConfig.DEBUG && diagnostic != null) {
            diagnostic.safeMoodleErrorCode()?.let { code ->
                Text(
                    "Moodle errorcode: $code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(Modifier.fillMaxWidth()) {
                Text(
                    diagnostic.toSafeText(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private fun ForumDiagnostic.safeMoodleErrorCode(): String? =
    moodleErrorCode?.takeIf { SAFE_MOODLE_ERROR_CODE.matches(it) }

private val SAFE_MOODLE_ERROR_CODE = Regex("[A-Za-z0-9_-]{1,64}")

private fun ForumDiagnostic.toSafeText(): String = buildString {
    appendLine("診断情報")
    appendLine("stage=$stage")
    appendLine("wsfunction=${wsFunction ?: "-"}")
    appendLine("kind=${kind.diagnosticCode()}")
    appendLine("moodleErrorCode=${moodleErrorCode ?: "-"}")
    appendLine("httpStatus=${httpStatus ?: "-"}")
    appendLine("courseId=${target?.courseId ?: "-"}")
    appendLine("courseModuleId=${target?.courseModuleId ?: "-"}")
    appendLine("forumInstanceId=${target?.forumInstanceId ?: "-"}")
    appendLine("contextId=${target?.contextId ?: "-"}")
    appendLine("modName=${target?.modName ?: "-"}")
}

@Composable
private fun PostCard(post: ForumPost, onOpenUrl: (String, String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(post.subject, style = MaterialTheme.typography.titleMedium)
            Text(
                "${post.author?.fullname ?: "投稿者不明"} / ${formatTime(post.timecreated)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HtmlText(
                html = post.message,
                onOpenUrl = { onOpenUrl(it, post.subject.ifBlank { "フォーラム" }) },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatTime(epochSeconds: Long): String =
    formatMoodleDateTime(epochSeconds)
