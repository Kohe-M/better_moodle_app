package dev.rits.bettermoodle.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.rits.bettermoodle.data.CourseColors
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.Timetable
import dev.rits.bettermoodle.data.TimetableEntry
import dev.rits.bettermoodle.data.currentSectionIndex
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.CourseColor
import dev.rits.bettermoodle.ui.theme.CoursePaletteNames
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Sizes
import dev.rits.bettermoodle.ui.theme.Spacing
import dev.rits.bettermoodle.ui.theme.TABULAR_NUMS
import java.time.LocalDate

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

    val listState = rememberLazyListState()
    val collapsedTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    var expanded by rememberSaveable(courseId) { mutableStateOf<Set<Long>?>(null) }
    var showColorSheet by remember { mutableStateOf(false) }
    val colorKey = CourseColors.key(courseCode, courseId, courseName)
    val cc = courseColor(colorKey)
    // 曜日時限・教室は時間割のキャッシュから引く (時間割にない授業は出さない)
    val slots = remember(courseId, courseCode) {
        LoadableMemoryCache.get<Timetable>("timetable")?.value?.entries.orEmpty()
            .filter { (courseCode != null && it.courseCode == courseCode) || it.courseId == courseId }
            .sortedWith(compareBy({ it.dayIndex }, { it.period }))
    }

    fun openSyllabus(code: String) {
        resolvingSyllabus = true
        scope.launch {
            try {
                when (val result = container.syllabusRepository.resolve(code)) {
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
    }

    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            TopAppBar(
                // 大きな見出しがスクロールで隠れたらインラインのタイトル (1行省略) に切り替える
                title = {
                    if (collapsedTitle) {
                        Text(courseName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
        StaleDataBanner(state, onRetry = refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            val sections = (state as? UiState.Success)?.data.orEmpty().filter { it.uservisible }.filter { section ->
                section.modules.any { it.uservisible } || htmlHasRenderableContent(section.summary)
            }
            // 現在週のセクションだけを自動で展開してそこへスクロールする。週が分からなければ全部展開
            val currentIndex = remember(sections) { currentSectionIndex(sections.map { it.name }, LocalDate.now()) }
            LaunchedEffect(sections.isNotEmpty()) {
                if (sections.isNotEmpty() && expanded == null) {
                    expanded = if (currentIndex != null) setOf(sections[currentIndex].id) else sections.map { it.id }.toSet()
                    if (currentIndex != null) listState.scrollToItem(1 + currentIndex)
                }
            }
            val open = expanded ?: emptySet()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = RowListPadding) {
                item(key = "header") {
                    CourseHeader(
                        name = courseName,
                        code = courseCode,
                        color = cc,
                        slots = slots,
                        resolvingSyllabus = resolvingSyllabus,
                        onSyllabus = courseCode?.let { code -> { openSyllabus(code) } },
                        onOpenInMoodle = {
                            onOpenMoodleWeb("${MoodleClient.SITE_URL}/course/view.php?id=$courseId", courseName)
                        },
                        onChangeColor = { showColorSheet = true },
                    )
                }
                when (val s = state) {
                    is UiState.Loading -> item(key = "loading") { Box(Modifier.height(320.dp)) { LoadingBox() } }
                    is UiState.Error -> item(key = "error") {
                        Box(Modifier.height(320.dp)) {
                            ErrorBox(s.message, onRetry = refresh, title = "コースの内容を読み込めませんでした")
                        }
                    }
                    is UiState.Success -> {
                        if (sections.isEmpty()) {
                            item(key = "empty") {
                                Box(Modifier.height(320.dp)) {
                                    EmptyBox("まだ資料や課題はありません", "先生が公開すると、ここに週ごとに並びます。", onRetry = refresh)
                                }
                            }
                        }
                        sections.forEachIndexed { index, section ->
                            val isOpen = section.id in open
                            item(key = "sec-${section.id}") {
                                SectionHeader(
                                    section = section,
                                    current = index == currentIndex,
                                    expanded = isOpen,
                                    count = section.modules.count { it.uservisible && it.modName != "label" },
                                    onToggle = {
                                        expanded = if (isOpen) open - section.id else open + section.id
                                    },
                                )
                            }
                            if (isOpen) {
                                if (htmlHasRenderableContent(section.summary)) {
                                    item(key = "sec-summary-${section.id}") { SectionSummary(section, onOpenUrl) }
                                }
                                val groups = groupSectionModules(section.modules.filter { it.uservisible })
                                groups.forEachIndexed { groupIndex, group ->
                                    val keyModule = group.module ?: group.labels.firstOrNull()
                                    item(key = "mod-${section.id}-${keyModule?.id ?: groupIndex}") {
                                        ModuleGroupItem(group = group, onOpenUrl = onOpenUrl, onOpenModule = ::openModule)
                                    }
                                }
                            }
                        }
                        item(key = "sync") { SyncFooter(s) }
                    }
                }
            }
        }
        }
    }

    if (showColorSheet) {
        CourseColorSheet(
            selected = LocalCourseColors.current.indexFor(colorKey),
            onSelect = { index ->
                container.courseColorStore.setOverride(colorKey, index)
                showColorSheet = false
            },
            onReset = {
                container.courseColorStore.setOverride(colorKey, null)
                showColorSheet = false
            },
            onDismiss = { showColorSheet = false },
        )
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
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item(key = "title") {
                Column(Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp)) {
                    Text(module.name, style = MaterialTheme.typography.titleLarge)
                    MetaText("${module.downloadableFiles.size}件のファイル")
                }
            }
            module.downloadableFiles.forEach { content ->
                item(key = content.fileurl ?: "${content.filename}-${content.filepath}") {
                    val filename = content.filename ?: "ファイル"
                    ListRow(onClick = { onOpenFile(content) }, verticalAlignment = Alignment.CenterVertically) {
                        LeadingIcon(fileIcon(filename, content.mimetype), contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(filename, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            MetaText(folderFileLabel(filename, content.mimetype))
                        }
                    }
                }
            }
        }
    }
}

private fun fileIcon(filename: String, mimeType: String?): ImageVector =
    when (previewKindFor(filename, mimeType)) {
        PreviewKind.Pdf -> Icons.Outlined.PictureAsPdf
        PreviewKind.Image -> Icons.Outlined.Image
        else -> Icons.Outlined.Description
    }

private fun folderFileLabel(filename: String, mimeType: String?): String =
    when (previewKindFor(filename, mimeType)) {
        PreviewKind.Pdf -> "PDF · アプリ内で表示"
        PreviewKind.Image -> "画像 · アプリ内で表示"
        PreviewKind.Text -> "テキスト · アプリ内で表示"
        PreviewKind.Html -> "Webページとして表示"
        PreviewKind.Unsupported -> "ダウンロードして開く"
    }

/**
 * コース詳細のヘッダー (DESIGN.md §8.4): コース名 (title-lg・2行)、曜日時限・教室、
 * シラバス / Moodleで開く。授業カラーのスクエアを押すと色を変えられる。
 */
@Composable
private fun CourseHeader(
    name: String,
    code: String?,
    color: CourseColor,
    slots: List<TimetableEntry>,
    resolvingSyllabus: Boolean,
    onSyllabus: (() -> Unit)?,
    onOpenInMoodle: () -> Unit,
    onChangeColor: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter).padding(top = Spacing.xs, bottom = Spacing.sm)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(Sizes.hitMin)
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable(onClickLabel = "授業カラーを変更", onClick = onChangeColor),
                contentAlignment = Alignment.Center,
            ) {
                CourseSquare(color, name, size = 32.dp)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTheme.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                code?.let { MetaText("授業コード $it", modifier = Modifier.padding(top = Spacing.xxs)) }
            }
        }
        if (slots.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            slots.groupBy { it.dayIndex }.forEach { (day, entries) ->
                val periods = entries.map { it.period }.sorted()
                val periodText = if (periods.size > 1 && periods.last() - periods.first() == periods.size - 1) {
                    "${periods.first()}–${periods.last()}限"
                } else {
                    periods.joinToString("・") { "${it}限" }
                }
                val room = entries.firstNotNullOfOrNull { it.room?.takeIf(String::isNotBlank) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.xxs)) {
                    Box(Modifier.size(8.dp).background(color.fg, CircleShape))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "${WeekdayNames.getOrElse(day) { "" }}曜 $periodText" + (room?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_NUMS),
                        color = AppTheme.colors.ink,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = Spacing.sm),
        ) {
            if (onSyllabus != null) {
                SecondaryButton(
                    if (resolvingSyllabus) "シラバスを検索中" else "シラバス",
                    onClick = onSyllabus,
                    enabled = !resolvingSyllabus,
                )
            }
            TertiaryButton("Moodleで開く", onClick = onOpenInMoodle)
        }
    }
}

/** 授業カラーの変更。パレット外の任意色は選ばせない (コントラスト保証のため) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseColorSheet(
    selected: Int,
    onSelect: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppTheme.colors.surface) {
        Column(Modifier.padding(horizontal = ScreenGutter).padding(bottom = Spacing.xl)) {
            Text("授業カラー", style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.ink)
            Spacer(Modifier.height(Spacing.lg))
            val palette = AppTheme.coursePalette
            palette.chunked(5).forEachIndexed { row, colors ->
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    colors.forEachIndexed { col, color ->
                        val index = row * 5 + col
                        Box(
                            Modifier
                                .size(Sizes.hitMin)
                                .clip(CircleShape)
                                .clickable(onClickLabel = "選択") { onSelect(index) }
                                .semantics {
                                    contentDescription = CoursePaletteNames[index] + if (index == selected) "、選択中" else ""
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            ColorSwatch(color, selected = index == selected)
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            TertiaryButton("自動に戻す", onClick = onReset)
        }
    }
}

/** セクション見出し (折りたたみ)。現在週には「今週」を添える */
@Composable
private fun SectionHeader(
    section: CourseSection,
    current: Boolean,
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = ScreenGutter, end = ScreenGutter, top = Spacing.lg, bottom = Spacing.sm)
            .semantics { stateDescription = if (expanded) "展開" else "折りたたみ" },
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    htmlToPlainText(section.name).ifBlank { "セクション ${section.section}" },
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTheme.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                )
                if (current) {
                    Spacer(Modifier.width(Spacing.sm))
                    StatusPill("今週", containerColor = AppTheme.colors.accentSoft, contentColor = AppTheme.colors.accent)
                }
            }
            if (!expanded && count > 0) MetaText("${count}件")
        }
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = AppTheme.colors.inkSecondary,
        )
    }
}

@Composable
private fun SectionSummary(
    section: CourseSection,
    onOpenUrl: (url: String, title: String) -> Unit,
) {
    HtmlText(
        html = section.summary,
        onOpenUrl = { onOpenUrl(it, section.name.ifBlank { "Moodle" }) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 4.dp),
    )
}

/** 行頭の印の幅。ラベル文は印の分だけ字下げしてモジュールに従属させる */
private val ModuleLeadingWidth = 36.dp

@Composable
private fun ModuleGroupItem(
    group: ModuleGroup,
    onOpenUrl: (url: String, title: String) -> Unit,
    onOpenModule: (CourseModule) -> Unit,
) {
    val module = group.module
    Column(Modifier.fillMaxWidth()) {
        if (module != null) {
            ModuleRow(module = module, onOpenUrl = onOpenUrl, onClick = { onOpenModule(module) })
        }
        if (group.labels.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(
                    start = if (module != null) ScreenGutter + ModuleLeadingWidth else ScreenGutter,
                    end = ScreenGutter,
                    top = 4.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.labels.forEach { LabelText(it, onOpenUrl) }
            }
        }
    }
}

@Composable
private fun LabelText(
    module: CourseModule,
    onOpenUrl: (url: String, title: String) -> Unit,
) {
    HtmlText(
        html = module.description,
        onOpenUrl = { onOpenUrl(it, module.name.ifBlank { "Moodle" }) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * モジュールの1行。課題・小テスト (やること) だけ藍のコマ印で目立たせ、
 * 資料・リンク等 (見るもの) は無彩色の小さなアイコンにする。
 */
@Composable
private fun ModuleRow(
    module: CourseModule,
    onOpenUrl: (url: String, title: String) -> Unit,
    onClick: () -> Unit,
) {
    val isTask = module.modName == "assign" || module.modName == "quiz"
    val typeLabel = module.modPlural.ifBlank { module.modName }
    ListRow(onClick = onClick) {
        Box(Modifier.width(ModuleLeadingWidth).padding(top = 2.dp)) {
            LeadingIcon(moduleIcon(module), contentDescription = null)
        }
        Column(Modifier.weight(1f)) {
            // Moodle はモジュール名を HTML エスケープして返す ("Q&amp;A")
            Text(
                htmlToPlainText(module.name).ifBlank { module.name },
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                append(typeLabel)
                // 課題・小テストは最後の日付 (締切・終了) を添える
                if (isTask) {
                    module.dates.lastOrNull { it.timestamp > 0 }?.let { date ->
                        append(" · ${date.label.trim().trimEnd(':', '：')} ${formatMoodleDateTime(date.timestamp)}")
                    }
                }
                module.availabilityinfo?.let { append(" · 公開条件あり") }
            }
            MetaText(sub, modifier = Modifier.padding(top = 2.dp))
            if (htmlHasRenderableContent(module.description)) {
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

/** 資料系モジュールのアイコン (無彩色で使う) */
private fun moduleIcon(module: CourseModule): ImageVector =
    when (module.modName) {
        "resource" -> if (module.pdfContent != null) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description
        "assign" -> Icons.AutoMirrored.Outlined.Assignment
        "quiz" -> Icons.Outlined.Quiz
        "folder" -> Icons.Outlined.Folder
        "url" -> Icons.Outlined.Link
        "forum" -> Icons.Outlined.Forum
        "lti", "url_video" -> Icons.Outlined.Videocam
        else -> Icons.AutoMirrored.Outlined.Article
    }
