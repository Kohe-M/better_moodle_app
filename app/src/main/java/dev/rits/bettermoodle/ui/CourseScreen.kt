package dev.rits.bettermoodle.ui

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.data.toForumTarget
import dev.rits.bettermoodle.data.toQuizTarget
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
    onOpenMoodleWeb: (url: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (state, refresh) = rememberLoadable { container.moodleRepository.courseContents(courseId) }
    var externalToConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }

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
        }
        scope.launch {
            val pdf = module.pdfContent
            if (pdf?.fileurl != null) {
                onOpenPdf(pdf.fileurl, module.name.ifBlank { pdf.filename ?: "PDF" })
                return@launch
            }
            val url = module.url ?: module.downloadableFiles.firstOrNull()?.fileurl
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
        when (val s = state) {
            is UiState.Loading -> LoadingBox()
            is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
            is UiState.Success -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (courseCode != null) {
                    item {
                        SyllabusBanner {
                            scope.launch {
                                val syllabus = container.syllabusRepository.resolveUrl(courseCode)
                                if (syllabus != null) openInCustomTab(context, syllabus)
                            }
                        }
                    }
                }
                s.data.filter { it.uservisible && it.modules.isNotEmpty() }.forEach { section ->
                    item(key = "sec-${section.id}") { SectionHeader(section) }
                    section.modules
                        .filter { it.uservisible }
                        .forEach { module ->
                            item(key = "mod-${section.id}-${module.id}") {
                                if (module.modName == "label") {
                                    LabelText(module)
                                } else {
                                    ModuleRow(module) { openModule(module) }
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
}

@Composable
private fun SyllabusBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "この科目のシラバスを開く",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
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
private fun LabelText(module: CourseModule) {
    Text(
        text = htmlToPlainText(module.description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
fun ModuleRow(module: CourseModule, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
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
