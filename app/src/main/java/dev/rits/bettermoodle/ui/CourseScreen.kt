package dev.rits.bettermoodle.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.CourseModule
import dev.rits.bettermoodle.data.CourseSection
import dev.rits.bettermoodle.data.ForumTarget
import dev.rits.bettermoodle.data.ModuleContent
import dev.rits.bettermoodle.data.ModuleGroup
import dev.rits.bettermoodle.data.PageTarget
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.data.SyllabusResolveResult
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.data.groupSectionModules
import dev.rits.bettermoodle.data.previewKindFor
import dev.rits.bettermoodle.data.toForumTarget
import dev.rits.bettermoodle.data.toPageTarget
import dev.rits.bettermoodle.data.toQuizTarget
import dev.rits.bettermoodle.data.urlContentFileUrl
import kotlinx.coroutines.launch

/**
 * アプリ内コース画面。core_course_get_contents のセクション/アクティビティを表示。
 * - PDF資料はアプリ内でプレビュー
 * - 課題・小テスト等は自動ログインでブラウザ (ログイン済みなので提出まで完結)
 * - 右上にシラバスへのリンク
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseScreen(
    container: AppContainer,
    courseId: Long,
    courseName: String,
    courseCode: String?,
    onBack: () -> Unit,
    onOpenPdf: (fileUrl: String, title: String) -> Unit,
    onOpenAssignment: (moduleId: Long, assignId: Long, title: String) -> Unit,
    onOpenForum: (target: ForumTarget, title: String) -> Unit,
    onOpenQuiz: (target: QuizTarget, title: String) -> Unit,
    onOpenPage: (target: PageTarget, title: String) -> Unit,
    onOpenFilePreview: (fileUrl: String, title: String, kind: PreviewKind) -> Unit,
    onOpenMoodleWeb: (url: String, title: String) -> Unit,
    onOpenUrl: (url: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (state, refresh) = rememberLoadable("course:$courseId") { container.moodleRepository.courseContents(courseId) }
    var externalToConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var folderToShow by remember { mutableStateOf<CourseModule?>(null) }
    var downloadToShare by remember { mutableStateOf<CourseDownloadFile?>(null) }
    // シラバスURLの解決中は再度押せないようにする (連打で並行検索しない。G18)
    var resolvingSyllabus by remember { mutableStateOf(false) }

    fun showCannotOpenLink() {
        Toast.makeText(context, "このリンクは開けません", Toast.LENGTH_SHORT).show()
    }

    fun openContentFile(module: CourseModule, content: ModuleContent) {
        val url = content.fileurl ?: return
        val moduleTitle = module.name
        val title = content.filename?.takeIf { it.isNotBlank() } ?: moduleTitle
        when (val kind = previewKindFor(title, content.mimetype)) {
            PreviewKind.Pdf -> onOpenPdf(url, title)
            PreviewKind.Image,
            PreviewKind.Text,
            -> onOpenFilePreview(url, title, kind)
            PreviewKind.Html -> {
                val viewUrl = module.url?.takeIf { it.isNotBlank() }
                if (viewUrl != null) {
                    onOpenMoodleWeb(viewUrl, module.name)
                } else {
                    downloadToShare = CourseDownloadFile(
                        fileUrl = url,
                        filename = title,
                        mimeType = content.mimetype,
                    )
                }
            }
            PreviewKind.Unsupported -> {
                downloadToShare = CourseDownloadFile(
                    fileUrl = url,
                    filename = title,
                    mimeType = content.mimetype,
                )
            }
        }
    }

    fun openModule(module: CourseModule) {
        when (module.modName) {
            "assign" -> {
                onOpenAssignment(module.id, module.instance ?: 0L, module.name)
                return
            }
            "forum" -> {
                onOpenForum(module.toForumTarget(courseId), module.name)
                return
            }
            "quiz" -> {
                onOpenQuiz(module.toQuizTarget(courseId), module.name)
                return
            }
            "page" -> {
                onOpenPage(module.toPageTarget(courseId), module.name)
                return
            }
            "url" -> {
                val url = urlContentFileUrl(module)
                when {
                    url == null -> showCannotOpenLink()
                    UrlPolicy.isAllowedMoodleWebViewUrl(url) -> onOpenMoodleWeb(url, module.name)
                    UrlPolicy.canOpenExternally(url) -> externalToConfirm = url to module.name
                    else -> showCannotOpenLink()
                }
                return
            }
        }
        scope.launch {
            val files = module.downloadableFiles
            if (module.modName == "folder" && files.isNotEmpty()) {
                folderToShow = module
                return@launch
            }
            val pdf = module.pdfContent
            if (pdf?.fileurl != null) {
                onOpenPdf(pdf.fileurl, module.name.ifBlank { pdf.filename ?: "PDF" })
                return@launch
            }
            val file = files.firstOrNull()
            if (file?.fileurl != null) {
                openContentFile(module, file)
                return@launch
            }
            val url = module.url
            if (url != null) {
                when {
                    UrlPolicy.isAllowedMoodleWebViewUrl(url) -> onOpenMoodleWeb(url, module.name)
                    UrlPolicy.canOpenExternally(url) -> externalToConfirm = url to module.name
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(courseName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (courseCode != null) {
                        IconButton(
                            enabled = !resolvingSyllabus,
                            onClick = {
                                resolvingSyllabus = true
                                scope.launch {
                                    try {
                                        when (val result = container.syllabusRepository.resolve(courseCode)) {
                                            is SyllabusResolveResult.Found -> openInCustomTab(context, result.url)
                                            else -> {
                                                // 失敗理由を知らせ、検索サイトで探せるようにする
                                                Toast.makeText(context, result.failureMessage, Toast.LENGTH_LONG).show()
                                                openInCustomTab(context, SyllabusRepository.SYLLABUS_SEARCH_URL)
                                            }
                                        }
                                    } finally {
                                        resolvingSyllabus = false
                                    }
                                }
                            },
                        ) {
                            if (resolvingSyllabus) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "シラバス")
                            }
                        }
                    }
                },
                colors = appTopBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        // 他の一覧画面と同じく、再読込に失敗して古い内容を表示していることを知らせる (G18)
        StaleDataBanner(state, onRetry = refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
                is UiState.Success -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ListContentPadding,
                ) {
                    if (s.isStale) item(key = "stale") { StaleDataBanner(s, onRetry = refresh) }
                    // セクション = 見出し + 連結カードスタック (概要・ラベル・アクティビティ)。
                    // スタック内は2dpの隙間と小さい角丸で「ひとまとまり」に見せる。
                    var firstSection = true
                    s.data.filter { it.uservisible }.forEach { section ->
                        val hasSummary = htmlHasRenderableContent(section.summary)
                        if (section.modules.isEmpty() && !hasSummary) return@forEach
                        val isFirst = firstSection
                        firstSection = false
                        item(key = "sec-${section.id}") { SectionHeader(section, isFirst) }
                        val groups = groupSectionModules(section.modules.filter { it.uservisible })
                        val stackSize = groups.size + if (hasSummary) 1 else 0
                        if (hasSummary) {
                            val position = sectionStackPosition(0, stackSize)
                            item(key = "sec-summary-${section.id}") {
                                SectionSummary(section, onOpenUrl, position)
                            }
                        }
                        val offset = if (hasSummary) 1 else 0
                        groups.forEachIndexed { index, group ->
                            val keyModule = group.module ?: group.labels.firstOrNull()
                            val position = sectionStackPosition(index + offset, stackSize)
                            item(key = "mod-${section.id}-${keyModule?.id ?: index}") {
                                ModuleGroupItem(
                                    group = group,
                                    position = position,
                                    onOpenUrl = onOpenUrl,
                                    onOpenModule = ::openModule,
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }

    externalToConfirm?.let { (url, title) ->
        AlertDialog(
            onDismissRequest = { externalToConfirm = null },
            title = { Text("外部サイトを開きます") },
            text = { Text("$title\n$url") },
            confirmButton = {
                TextButton(onClick = {
                    externalToConfirm = null
                    openInCustomTab(context, url)
                }) { Text("開く") }
            },
            dismissButton = {
                TextButton(onClick = { externalToConfirm = null }) { Text("キャンセル") }
            },
        )
    }

    folderToShow?.let { module ->
        FolderFilesSheet(
            module = module,
            onDismiss = { folderToShow = null },
            onOpenFile = { content ->
                folderToShow = null
                openContentFile(module, content)
            },
        )
    }

    downloadToShare?.let { file ->
        DownloadShareDialog(
            container = container,
            fileUrl = file.fileUrl,
            filename = file.filename,
            mimeType = file.mimeType,
            onDismiss = { downloadToShare = null },
        )
    }
}

private data class CourseDownloadFile(
    val fileUrl: String,
    val filename: String,
    val mimeType: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderFilesSheet(
    module: CourseModule,
    onDismiss: () -> Unit,
    onOpenFile: (ModuleContent) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(module.name, style = MaterialTheme.typography.titleLarge)
                    MetaText("${module.downloadableFiles.size}件のファイル")
                }
            }
            module.downloadableFiles.forEach { content ->
                item(key = content.fileurl ?: "${content.filename}-${content.filepath}") {
                    AppCard(
                        onClick = { onOpenFile(content) },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LeadingIconBox(Icons.Filled.Description, contentDescription = null, size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                val filename = content.filename ?: "ファイル"
                                Text(filename, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                MetaText(folderFileLabel(filename, content.mimetype))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun folderFileLabel(filename: String, mimeType: String?): String =
    when (previewKindFor(filename, mimeType)) {
        PreviewKind.Pdf -> "PDFプレビュー"
        PreviewKind.Image -> "画像プレビュー"
        PreviewKind.Text -> "テキストプレビュー"
        PreviewKind.Html -> "HTMLをWebViewで表示"
        PreviewKind.Unsupported -> "ダウンロードして開く"
    }

@Composable
private fun SectionHeader(section: CourseSection, isFirst: Boolean) {
    SectionHeading(
        text = section.name.ifBlank { "セクション ${section.section}" },
        modifier = Modifier.padding(top = if (isFirst) 4.dp else 24.dp, bottom = 8.dp),
    )
}

/**
 * セクション内スタックでの位置。外側の角は大きく、隣接する内側の角は
 * 小さく丸めることで、複数カードを1つのまとまりとして見せる。
 */
private data class SectionStackPosition(val isFirst: Boolean, val isLast: Boolean) {
    private val topRadius: Dp get() = if (isFirst) 12.dp else 4.dp
    private val bottomRadius: Dp get() = if (isLast) 12.dp else 4.dp

    fun shape() = RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomEnd = bottomRadius,
        bottomStart = bottomRadius,
    )

    fun topShape() = RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomEnd = 0.dp,
        bottomStart = 0.dp,
    )

    fun bottomShape() = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = bottomRadius,
        bottomStart = bottomRadius,
    )

    /** スタック内の連結を保ったまま、要素間に細い隙間を空ける */
    fun itemSpacing(): Dp = if (isFirst) 0.dp else 2.dp
}

private fun sectionStackPosition(index: Int, count: Int) =
    SectionStackPosition(isFirst = index == 0, isLast = index == count - 1)

@Composable
private fun SectionSummary(
    section: CourseSection,
    onOpenUrl: (url: String, title: String) -> Unit,
    position: SectionStackPosition,
) {
    Surface(
        shape = position.shape(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = stackBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = position.itemSpacing()),
    ) {
        HtmlText(
            html = section.summary,
            onOpenUrl = { onOpenUrl(it, section.name.ifBlank { "Moodle" }) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ModuleGroupItem(
    group: ModuleGroup,
    position: SectionStackPosition,
    onOpenUrl: (url: String, title: String) -> Unit,
    onOpenModule: (CourseModule) -> Unit,
) {
    val module = group.module
    val stackModifier = Modifier
        .fillMaxWidth()
        .padding(top = position.itemSpacing())
    if (module == null) {
        Surface(
            shape = position.shape(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = stackBorder(),
            modifier = stackModifier,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.labels.forEach { LabelText(it, onOpenUrl, modifier = Modifier) }
            }
        }
    } else if (group.labels.isEmpty()) {
        ModuleRow(
            module = module,
            onOpenUrl = onOpenUrl,
            shape = position.shape(),
            modifier = stackModifier,
            onClick = { onOpenModule(module) },
        )
    } else {
        Column(stackModifier) {
            ModuleRow(
                module = module,
                onOpenUrl = onOpenUrl,
                shape = position.topShape(),
                onClick = { onOpenModule(module) },
            )
            Surface(
                shape = position.bottomShape(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = stackBorder(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.labels.forEach { LabelText(it, onOpenUrl, modifier = Modifier) }
                }
            }
        }
    }
}

@Composable
private fun LabelText(
    module: CourseModule,
    onOpenUrl: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
) {
    HtmlText(
        html = module.description,
        onOpenUrl = { onOpenUrl(it, module.name.ifBlank { "Moodle" }) },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** スタック内カードの細い枠線。背景との境界を確実に見せる。 */
@Composable
private fun stackBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
fun ModuleRow(
    module: CourseModule,
    onOpenUrl: (url: String, title: String) -> Unit,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (icon, tint) = moduleIcon(module)
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = stackBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeadingIconBox(
                icon = icon,
                contentDescription = module.modPlural,
                tint = tint,
                containerColor = tint.copy(alpha = 0.13f),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // Moodle はモジュール名を HTML エスケープして返す ("Q&amp;A")
                Text(
                    htmlToPlainText(module.name).ifBlank { module.name },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = buildString {
                    append(module.modPlural.ifBlank { module.modName })
                    module.pdfContent?.let { append(" · PDFプレビュー可") }
                    module.availabilityinfo?.let { append(" · 制限あり") }
                }
                MetaText(sub, modifier = Modifier.padding(top = 2.dp))
                if (htmlHasRenderableContent(module.description)) {
                    HtmlText(
                        html = module.description,
                        onOpenUrl = { onOpenUrl(it, module.name.ifBlank { "Moodle" }) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 種別ごとのアイコンと色。課題・小テストは目立たせ、資料系は落ち着いた色にする。 */
@Composable
private fun moduleIcon(module: CourseModule): Pair<ImageVector, Color> {
    val c = MaterialTheme.colorScheme
    return when (module.modName) {
        "assign" -> Icons.AutoMirrored.Filled.Assignment to c.error
        "quiz" -> Icons.Filled.Quiz to c.tertiary
        "resource" -> if (module.pdfContent != null) Icons.Filled.PictureAsPdf to c.error else Icons.AutoMirrored.Filled.Article to c.primary
        "folder" -> Icons.Filled.Folder to c.tertiary
        "url" -> Icons.Filled.Link to c.secondary
        "forum" -> Icons.Filled.Forum to c.secondary
        "page" -> Icons.AutoMirrored.Filled.Article to c.primary
        "lti", "url_video" -> Icons.Filled.Videocam to c.secondary
        else -> Icons.AutoMirrored.Filled.Article to c.primary
    }
}
