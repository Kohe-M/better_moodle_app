package dev.rits.bettermoodle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Spacing

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
                    // ネイティブ表示で欠ける添付・埋込のために常時 Moodle へ抜けられるようにする
                    if (!target.url.isNullOrBlank()) {
                        IconButton(onClick = onFallbackWeb) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Moodleで開く")
                        }
                    }
                    IconButton(onClick = {
                        refreshDiscussions()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
                    }
                },
                colors = appTopBarColors(),
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
                        EmptyBox(
                            "まだ投稿はありません",
                            "新しいディスカッションが始まるとここに並びます。",
                            onRetry = { refreshDiscussions() },
                        )
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentPadding = RowListPadding,
                        ) {
                            item { SectionLabel("ディスカッション ${s.data.size}件") }
                            itemsIndexed(s.data, key = { _, it -> it.id }) { index, discussion ->
                                DiscussionRow(
                                    discussion,
                                    Modifier.groupedItem(GroupPosition.of(index, s.data.size), dividerInset = AvatarTextInset),
                                ) { selected = discussion }
                            }
                            item {
                                TertiaryButton(
                                    "投稿・返信はMoodleで開く",
                                    onClick = onFallbackWeb,
                                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.lg),
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
                    // 通知から直接開いた投稿でも、添付・返信のために常時 Moodle へ抜けられるようにする
                    if (fallbackUrl.isNotBlank()) {
                        IconButton(onClick = { onFallbackWeb(fallbackUrl, screenTitle) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Moodleで開く")
                        }
                    }
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読込")
                    }
                },
                colors = appTopBarColors(),
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

/** 投稿者の頭文字の丸。装飾なので読み上げない (名前は隣に文字で出す) */
@Composable
private fun Avatar(name: String?, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .background(AppTheme.colors.accentSoft, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(courseInitial(name.orEmpty()), style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.accent)
    }
}

/** 行の本文の開始位置 (頭文字の丸の分)。区切り線もここから引く */
private val AvatarTextInset = ScreenGutter + 36.dp + Spacing.md

/** ディスカッションの1行。未読は件数の札 (状態なので札を使う) と本文色の件名で示す */
@Composable
private fun DiscussionRow(discussion: ForumDiscussion, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = AppTheme.colors
    val unread = discussion.numunread > 0
    ListRow(onClick = onClick, modifier = modifier) {
        Avatar(discussion.userfullname)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                discussion.subject.ifBlank { discussion.name },
                style = MaterialTheme.typography.titleSmall,
                color = if (unread) c.ink else c.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaText(discussion.userfullname)
            htmlToPlainText(discussion.message).takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.xs))
                MetaText("返信 ${discussion.numreplies}")
            }
        }
        if (unread) {
            StatusPill("未読 ${discussion.numunread}", Tone.Accent, modifier = Modifier.padding(start = Spacing.md, top = 2.dp))
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
        is UiState.Success -> Column(modifier.fillMaxSize()) {
            // キャッシュ表示中の更新失敗を投稿一覧でも隠さない
            StaleDataBanner(s, onRetry = refresh)
            LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = RowListPadding,
        ) {
            itemsIndexed(s.data, key = { _, post -> post.id }) { index, post ->
                if (index == 1) SectionLabel("返信 ${s.data.size - 1}件")
                PostBlock(
                    post,
                    isRoot = index == 0,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.xs),
                )
            }
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
            PrimaryButton("Moodle画面で開く", onClick = onFallbackWeb, modifier = Modifier.fillMaxWidth())
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

/**
 * 投稿。1件ずつカードにする。最初の投稿 (ディスカッションの本文) は件名を大きく出し、
 * 以降の返信は投稿者と日時を先頭に置いて会話として読めるようにする。
 */
@Composable
private fun PostBlock(post: ForumPost, isRoot: Boolean, onOpenUrl: (String, String) -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.padding(top = if (isRoot) Spacing.md else 0.dp)) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (isRoot && post.subject.isNotBlank()) {
                Text(
                    post.subject,
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppTheme.colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.author?.fullname, size = if (isRoot) 40.dp else 32.dp)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        post.author?.fullname ?: "投稿者不明",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaText(formatTime(post.timecreated))
                }
            }
            HtmlText(
                html = post.message,
                onOpenUrl = { onOpenUrl(it, post.subject.ifBlank { "フォーラム" }) },
                style = if (isRoot) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.ink,
            )
        }
    }
}

private fun formatTime(epochSeconds: Long): String =
    formatMoodleDateTime(epochSeconds)
