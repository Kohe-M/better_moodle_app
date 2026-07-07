package dev.rits.bettermoodle.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.Assignment
import dev.rits.bettermoodle.data.AssignmentAction
import dev.rits.bettermoodle.data.AssignmentSubmissionLabel
import dev.rits.bettermoodle.data.AssignmentUiModel
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.SubmissionStatusResponse
import dev.rits.bettermoodle.data.SubmittedFileUi
import dev.rits.bettermoodle.data.buildAssignmentUiModel
import dev.rits.bettermoodle.data.extractOnlineText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class AssignmentUiData(
    val assignment: Assignment,
    val status: SubmissionStatusResponse,
    val ui: AssignmentUiModel,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentScreen(
    container: AppContainer,
    courseId: Long,
    moduleId: Long,
    assignId: Long,
    title: String,
    onBack: () -> Unit,
    onOpenFilePreview: (SubmittedFileUi) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState<AssignmentUiData>>(UiState.Loading) }
    var onlineText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var acceptedStatement by remember { mutableStateOf(false) }

    fun refresh() {
        refreshTick++
    }

    LaunchedEffect(courseId, moduleId, assignId, refreshTick) {
        state = UiState.Loading
        state = try {
            val assignment = container.moodleRepository.assignment(courseId, moduleId, assignId)
                ?: throw IllegalStateException("この課題はMoodle Web Serviceから取得できません。")
            val status = container.moodleRepository.assignmentStatus(assignment.id)
            onlineText = extractOnlineText(status)
            UiState.Success(
                AssignmentUiData(
                    assignment = assignment,
                    status = status,
                    ui = buildAssignmentUiModel(assignment, status),
                ),
            )
        } catch (_: Exception) {
            UiState.Error("課題を読み込めませんでした。再試行してください。")
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val current = (state as? UiState.Success)?.data?.assignment ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val saved = runCatching {
                val name = displayName(context, uri) ?: "submission.bin"
                val tmp = withContext(Dispatchers.IO) {
                    File.createTempFile("assignment_upload_", ".bin", context.cacheDir).also { file ->
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "選択したファイルを読み込めません。" }
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                try {
                    val uploaded = container.moodleRepository.uploadSubmissionFile(tmp, name)
                    container.moodleRepository.saveFileSubmission(current.id, uploaded.itemid)
                } finally {
                    tmp.delete()
                }
            }.onFailure {
                state = UiState.Error("ファイル提出に失敗しました。再試行してください。")
            }.isSuccess
            busy = false
            if (saved) refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = ::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, onRetry = ::refresh)
                is UiState.Success -> {
                    val assignment = s.data.assignment
                    val ui = s.data.ui
                    Text(assignment.name, style = MaterialTheme.typography.headlineSmall)
                    htmlToPlainText(assignment.intro).takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    AssignmentSummaryCard(assignment, ui)
                    FeedbackBlock(s.data.status)
                    SubmittedTextSection(ui)
                    SubmittedFilesSection(ui.files, onOpenFilePreview)

                    if (assignment.supports("onlinetext") && AssignmentAction.Edit in ui.actions) {
                        OutlinedTextField(
                            value = onlineText,
                            onValueChange = { onlineText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("提出する文章") },
                            minLines = 6,
                            enabled = !busy && ui.canEdit,
                        )
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    val saved = runCatching {
                                        container.moodleRepository.saveOnlineTextSubmission(assignment.id, onlineText)
                                    }.onFailure {
                                        state = UiState.Error("提出内容を保存できませんでした。再試行してください。")
                                    }.isSuccess
                                    busy = false
                                    if (saved) refresh()
                                }
                            },
                            enabled = !busy && ui.canEdit,
                        ) { Text("提出内容を保存") }
                    }

                    if (assignment.supports("file") && AssignmentAction.Edit in ui.actions) {
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = !busy && ui.canEdit,
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ファイルを追加")
                        }
                    }

                    AssignmentActions(
                        ui = ui,
                        busy = busy,
                        onStart = {
                            busy = true
                            scope.launch {
                                val started = runCatching {
                                    container.moodleRepository.startAssignmentSubmission(assignment.id)
                                }.onFailure {
                                    state = UiState.Error("提出を開始できませんでした。再試行してください。")
                                }.isSuccess
                                busy = false
                                if (started) refresh()
                            }
                        },
                        onFinalSubmit = {
                            acceptedStatement = false
                            confirmSubmit = true
                        },
                    )
                }
            }
        }
    }

    if (confirmSubmit) {
        val current = (state as? UiState.Success)?.data
        val requiresStatement = current?.ui?.submissionStatementRequired == true
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("最終提出しますか") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最終提出後は、Moodle側の設定により編集できなくなる場合があります。")
                    if (requiresStatement) {
                        Row {
                            Checkbox(
                                checked = acceptedStatement,
                                onCheckedChange = { acceptedStatement = it },
                            )
                            Text("提出確認文に同意します")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !requiresStatement || acceptedStatement,
                    onClick = {
                        val assignment = current?.assignment ?: return@TextButton
                        confirmSubmit = false
                        busy = true
                        scope.launch {
                            val submitted = runCatching {
                                container.moodleRepository.submitAssignmentForGrading(
                                    assignment.id,
                                    acceptSubmissionStatement = requiresStatement && acceptedStatement,
                                )
                            }.onFailure {
                                state = UiState.Error("最終提出に失敗しました。再試行してください。")
                            }.isSuccess
                            busy = false
                            if (submitted) refresh()
                        }
                    },
                ) { Text("最終提出する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSubmit = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun AssignmentSummaryCard(assignment: Assignment, ui: AssignmentUiModel) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("課題サマリー", style = MaterialTheme.typography.titleMedium)
            SummaryLine("現在の状態", ui.availability.text)
            SummaryLine("提出状況", ui.submission.text)
            SummaryLine("採点状況", ui.grading.text)
            SummaryLine("提出期限", formatAssignmentTime(assignment.duedate))
            SummaryLine("提出可能期間", formatAssignmentTime(assignment.allowsubmissionsfromdate, suffix = " から"))
            SummaryLine("締切", formatAssignmentTime(assignment.cutoffdate))
            SummaryLine("提出後の編集", if (ui.canEdit) "可能" else "不可")
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AssignmentActions(
    ui: AssignmentUiModel,
    busy: Boolean,
    onStart: () -> Unit,
    onFinalSubmit: () -> Unit,
) {
    if (ui.actions.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (AssignmentAction.Start in ui.actions) {
            Button(onClick = onStart, enabled = !busy) { Text("提出を開始") }
        }
        if (AssignmentAction.Edit in ui.actions && ui.submission == AssignmentSubmissionLabel.Submitted) {
            OutlinedButton(onClick = {}, enabled = false) { Text("提出済み") }
        }
        if (AssignmentAction.FinalSubmit in ui.actions) {
            Button(onClick = onFinalSubmit, enabled = !busy) { Text("最終提出する") }
        }
    }
}

@Composable
private fun SubmittedTextSection(ui: AssignmentUiModel) {
    val text = ui.onlineText?.let(::htmlToPlainText)?.takeIf { it.isNotBlank() }
    if (text != null) {
        Text("提出した文章", style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SubmittedFilesSection(files: List<SubmittedFileUi>, onOpenFilePreview: (SubmittedFileUi) -> Unit) {
    Text("提出したファイル", style = MaterialTheme.typography.titleMedium)
    if (files.isEmpty()) {
        Text("ファイル提出はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Description, contentDescription = null)
                        Text(file.filename, style = MaterialTheme.typography.titleSmall)
                    }
                    Text("種類: ${file.typeLabel}")
                    Text("サイズ: ${formatBytes(file.sizeBytes)}")
                    Text("更新日時: ${formatAssignmentTime(file.modifiedAt)}")
                    Text("プレビュー: ${previewLabel(file.previewKind)}")
                    if (file.previewKind != PreviewKind.Unsupported && !file.sourceUrl.isNullOrBlank()) {
                        OutlinedButton(onClick = { onOpenFilePreview(file) }) {
                            Text("プレビュー")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackBlock(status: SubmissionStatusResponse) {
    val grade = status.feedback?.grade?.grade?.takeIf { it.isNotBlank() }
    val feedback = status.feedback?.plugins
        ?.flatMap { it.editorfields }
        ?.firstOrNull { it.text.isNotBlank() }
        ?.text
    if (grade != null || !feedback.isNullOrBlank()) {
        Text("評価・フィードバック", style = MaterialTheme.typography.titleMedium)
        if (grade != null) Text("評価: $grade")
        htmlToPlainText(feedback).takeIf { it.isNotBlank() }?.let { Text(it) }
    }
}

private fun formatAssignmentTime(epochSeconds: Long, suffix: String = ""): String {
    if (epochSeconds <= 0) return "未設定"
    val text = Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日（E）H:mm", Locale.JAPANESE))
    return text + suffix
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes <= 0L -> "不明"
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    }

private fun previewLabel(kind: PreviewKind): String =
    when (kind) {
        PreviewKind.Pdf -> "PDFを表示できます"
        PreviewKind.Image -> "画像を表示できます"
        PreviewKind.Text -> "テキストを表示できます"
        PreviewKind.Unsupported -> "この形式はアプリ内プレビューに未対応です"
    }

private fun displayName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
    }
