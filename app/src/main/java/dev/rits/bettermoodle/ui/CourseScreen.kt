package dev.rits.bettermoodle.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
                        IconButton(onClick = {
                            scope.launch {
                                val syllabus = container.syllabusRepository.resolveUrl(courseCode)
                                if (syllabus != null) openInCustomTab(context, syllabus)
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "シラバス")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
                is UiState.Success -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    s.data.filter { it.uservisible && it.modules.isNotEmpty() }.forEach { section ->
                        item(key = "sec-${section.id}") { SectionHeader(section) }
                        val visibleModules = section.modules.filter { it.uservisible }
                        groupSectionModules(visibleModules).forEachIndexed { index, group ->
                            val keyModule = group.module ?: group.labels.firstOrNull()
                            item(key = "mod-${section.id}-${keyModule?.id ?: index}") {
                                ModuleGroupItem(
                                    group = group,
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
                Text(module.name, style = MaterialTheme.typography.titleMedium)
            }
            module.downloadableFiles.forEach { content ->
                item(key = content.fileurl ?: "${content.filename}-${content.filepath}") {
                    Surface(
                        onClick = { onOpenFile(content) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Description, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                val filename = content.filename ?: "ファイル"
                                Text(filename, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                                Text(
                                    folderFileLabel(filename, content.mimetype),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
private fun SectionHeader(section: CourseSection) {
    Text(
        text = section.name.ifBlank { "セクション ${section.section}" },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun ModuleGroupItem(
    group: ModuleGroup,
    onOpenUrl: (url: String, title: String) -> Unit,
    onOpenModule: (CourseModule) -> Unit,
) {
    val module = group.module
    if (module == null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            group.labels.forEach { LabelText(it, onOpenUrl) }
        }
    } else if (group.labels.isEmpty()) {
        ModuleRow(
            module = module,
            onOpenUrl = onOpenUrl,
            onClick = { onOpenModule(module) },
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            ModuleRow(
                module = module,
                onOpenUrl = onOpenUrl,
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomEnd = 0.dp,
                    bottomStart = 0.dp,
                ),
                onClick = { onOpenModule(module) },
            )
            Surface(
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 12.dp,
                ),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
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

@Composable
fun ModuleRow(
    module: CourseModule,
    onOpenUrl: (url: String, title: String) -> Unit,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = moduleIcon(module),
                    contentDescription = module.modPlural,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(module.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                val sub = buildString {
                    append(module.modPlural.ifBlank { module.modName })
                    module.pdfContent?.let { append(" · PDFプレビュー可") }
                    module.availabilityinfo?.let { append(" · 制限あり") }
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (htmlToPlainText(module.description).isNotBlank()) {
                    HtmlText(
                        html = module.description,
                        onOpenUrl = { onOpenUrl(it, module.name.ifBlank { "Moodle" }) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun moduleIcon(module: CourseModule): ImageVector = when (module.modName) {
    "assign" -> Icons.AutoMirrored.Filled.Assignment
    "quiz" -> Icons.Filled.Quiz
    "resource" -> if (module.pdfContent != null) Icons.Filled.PictureAsPdf else Icons.AutoMirrored.Filled.Article
    "folder" -> Icons.Filled.Folder
    "url" -> Icons.Filled.Link
    "forum" -> Icons.Filled.Forum
    "page" -> Icons.AutoMirrored.Filled.Article
    "lti", "url_video" -> Icons.Filled.Videocam
    else -> Icons.AutoMirrored.Filled.Article
}
