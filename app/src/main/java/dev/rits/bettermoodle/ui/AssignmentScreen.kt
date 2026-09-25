package dev.rits.bettermoodle.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.Assignment
import dev.rits.bettermoodle.data.AssignmentAction
import dev.rits.bettermoodle.data.AssignmentAvailabilityLabel
import dev.rits.bettermoodle.data.AssignmentGradingLabel
import dev.rits.bettermoodle.data.AssignmentEditorState
import dev.rits.bettermoodle.data.AssignmentLoad
import dev.rits.bettermoodle.data.AssignmentSubmissionLabel
import dev.rits.bettermoodle.data.AssignmentUiModel
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.SubmissionFileKey
import dev.rits.bettermoodle.data.SubmissionStatusResponse
import dev.rits.bettermoodle.data.SubmittedFileUi
import dev.rits.bettermoodle.data.previewKindFor
import java.util.Locale

/** 課題ページのMoodle URL (Web Service未対応時・書式付き編集時の逃げ道) */
fun assignmentWebUrl(moduleId: Long): String = "${MoodleClient.SITE_URL}/mod/assign/view.php?id=$moduleId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentScreen(
    container: AppContainer,
    courseId: Long,
    moduleId: Long,
    assignId: Long,
    title: String,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onOpenFilePreview: (SubmittedFileUi) -> Unit,
    onOpenMoodleWeb: (url: String, title: String) -> Unit,
) {
    val viewModel: AssignmentViewModel = viewModel(
        key = "assignment/$courseId/$moduleId/$assignId",
        factory = viewModelFactory {
            initializer {
                AssignmentViewModel(container, createSavedStateHandle(), courseId, moduleId, assignId)
            }
        },
    )
    val state by viewModel.state.collectAsState()
    val orphanedDraft by viewModel.orphanedDraft.collectAsState()
    var confirmSubmit by remember { mutableStateOf(false) }
    var acceptedStatement by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<DiscardReason?>(null) }
    var confirmLeaveWhileBusy by remember { mutableStateOf(false) }
    var removeFileTarget by remember { mutableStateOf<SubmittedFileUi?>(null) }
    var downloadToShare by remember { mutableStateOf<SubmittedFileUi?>(null) }
    val webUrl = assignmentWebUrl(moduleId)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addFile(uri)
    }

    fun requestBack() {
        when {
            // 送信中の離脱は結果確認を見られなくなるので確認する (送信自体は中断しない)
            state.busy -> confirmLeaveWhileBusy = true
            state.isDirty -> confirmDiscard = DiscardReason.Back
            else -> onBack()
        }
    }

    fun requestRefresh() {
        if (state.busy) return
        if (state.isDirty) confirmDiscard = DiscardReason.Refresh else viewModel.refresh()
    }

    BackHandler(onBack = ::requestBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenMoodleWeb(webUrl, title) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Moodleで開く")
                    }
                    IconButton(onClick = ::requestRefresh, enabled = !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
                    }
                },
                colors = appTopBarColors(),
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
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                state.busyLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            state.error?.let { message ->
                MessageBanner(message, isError = true, onDismiss = viewModel::clearMessages)
            }
            state.pendingWrite?.let { pending ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageBanner(
                        if (pending.acknowledged) {
                            "${pending.description}はサーバーに受理されましたが、反映を確認できていません。確認できるまで新しい送信は受け付けません。"
                        } else {
                            "${pending.description}の結果が未確認です (応答なし)。処理中の可能性があるため再送せず、確認できるまで新しい送信は受け付けません。"
                        },
                        isError = true,
                        onDismiss = null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.reconcile() }, enabled = !state.busy) { Text("結果を確認") }
                        OutlinedButton(onClick = { onOpenMoodleWeb(webUrl, title) }) { Text("Moodleで確認") }
                        // 未反映のままの書き込みは自動で解除しない。利用者が Moodle で確認したうえで解除し、
                        // 解除後は最新状態を取り直す
                        TextButton(onClick = { viewModel.dismissPendingWrite() }, enabled = !state.busy) { Text("確認済みとして解除") }
                    }
                }
            }
            // 保留草稿のバナー。本文は表示しない (所有者を確認できるまで別アカウントの文章を見せない)
            orphanedDraft?.let { orphan ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageBanner(
                        if (orphan.ownerMismatch) {
                            "別のアカウントで保存された未保存の文章がこの端末に残っています (${orphan.characters}文字" +
                                "${orphan.savedAtMillis?.let { "・" + formatMoodleDateTime(it / 1000) + " 保存" } ?: ""})。" +
                                "内容は表示せず保持しています。そのアカウントでログインすると復元できます。"
                        } else {
                            "以前の未保存の文章がこの端末に残っています (${orphan.characters}文字" +
                                "${orphan.savedAtMillis?.let { "・" + formatMoodleDateTime(it / 1000) + " 保存" } ?: ""})。" +
                                "保存時のアカウントを確認できないため内容は表示していません。" +
                                "復元すると入力欄に取り込み、Moodleの本文と差があれば競合として保護します。"
                        },
                        isError = false,
                        onDismiss = null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (orphan.canAdopt) {
                            OutlinedButton(onClick = { viewModel.adoptOrphanedDraft() }, enabled = !state.busy) { Text("この文章を復元") }
                        }
                        TextButton(onClick = { viewModel.discardOrphanedDraft() }) { Text("破棄") }
                    }
                }
            }
            if (state.textConflict) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageBanner(
                        "Web側で本文が変更されています。このアプリの入力内容で上書きしないよう、保存・最終提出を止めています。" +
                            "「再読込して破棄」で最新の本文を取り込むか、Moodleで内容を確認してください。",
                        isError = true,
                        onDismiss = null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmDiscard = DiscardReason.Refresh }, enabled = !state.busy) { Text("再読込して破棄") }
                        OutlinedButton(onClick = { onOpenMoodleWeb(webUrl, title) }) { Text("Moodleで確認") }
                    }
                }
            }
            state.notice?.let { message ->
                MessageBanner(message, isError = false, onDismiss = viewModel::clearMessages)
            }
            when (val load = state.load) {
                is AssignmentLoad.Loading -> LoadingBox()
                is AssignmentLoad.Failed -> {
                    ErrorBox(load.message, onRetry = { viewModel.refresh() })
                    OutlinedButton(
                        onClick = { onOpenMoodleWeb(webUrl, title) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Moodleで開く") }
                }
                is AssignmentLoad.Ready -> {
                    val assignment = load.data.assignment
                    val ui = load.data.ui
                    Text(assignment.name, style = MaterialTheme.typography.headlineSmall)
                    AssignmentStatusHero(assignment, ui)
                    ui.unsupportedReason?.let { reason ->
                        MessageBanner(reason, isError = false, onDismiss = null)
                    }
                    if (htmlHasRenderableContent(assignment.intro)) {
                        SubsectionTitle("課題の説明")
                        HtmlText(
                            html = assignment.intro,
                            onOpenUrl = { onOpenUrl(it, assignment.name) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IntroAttachmentsSection(
                        attachments = assignment.introattachments,
                        onOpenFilePreview = onOpenFilePreview,
                        onDownloadFile = { downloadToShare = it },
                    )
                    AssignmentSummaryCard(assignment, ui)
                    FeedbackBlock(load.data.status, onOpenUrl)
                    SubmittedTextSection(ui, onOpenUrl)
                    SubmittedFilesSection(
                        files = ui.files,
                        canRemove = AssignmentAction.Edit in ui.actions && assignment.supports("file") && !state.busy,
                        onOpenFilePreview = onOpenFilePreview,
                        onDownloadFile = { downloadToShare = it },
                        onRemoveFile = { removeFileTarget = it },
                    )

                    if (assignment.supports("onlinetext") && AssignmentAction.Edit in ui.actions) {
                        OnlineTextEditor(
                            state = state,
                            ui = ui,
                            onTextChange = viewModel::updateText,
                            onSave = { viewModel.saveText() },
                            onEditInMoodle = { onOpenMoodleWeb(webUrl, title) },
                        )
                    }

                    if (assignment.supports("file") && AssignmentAction.Edit in ui.actions) {
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ファイルを追加")
                        }
                        val max = assignment.maxFileSubmissions
                        if (max > 0) {
                            MetaText("提出できるファイル: ${ui.files.size} / $max 件")
                        }
                    }

                    AssignmentActions(
                        ui = ui,
                        busy = state.busy,
                        onStart = { confirmStart = true },
                        onFinalSubmit = {
                            acceptedStatement = false
                            confirmSubmit = true
                        },
                    )
                    TextButton(onClick = { onOpenMoodleWeb(webUrl, title) }) {
                        Text("Moodleでこの課題を開く")
                    }
                }
            }
        }
    }

    confirmDiscard?.let { reason ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("未保存の文章があります") },
            text = {
                Text(
                    when (reason) {
                        DiscardReason.Back -> "保存していない文章を破棄して戻りますか?"
                        DiscardReason.Refresh -> "保存していない文章を破棄して再読み込みしますか?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = null
                    when (reason) {
                        DiscardReason.Back -> onBack()
                        DiscardReason.Refresh -> viewModel.refresh(discardDraft = true)
                    }
                }) { Text("破棄する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = null }) { Text("キャンセル") }
            },
        )
    }

    if (confirmStart) {
        val data = state.data
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("提出を開始しますか") },
            text = {
                val limit = data?.assignment?.timelimit ?: 0L
                Text(
                    "この課題には制限時間 (${formatDuration(limit)}) があります。" +
                        "開始するとMoodle側で計測が始まり、途中で止められません。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    viewModel.start()
                }) { Text("開始する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text("キャンセル") }
            },
        )
    }

    if (confirmLeaveWhileBusy) {
        AlertDialog(
            onDismissRequest = { confirmLeaveWhileBusy = false },
            title = { Text("送信中です") },
            text = {
                Text(
                    "${state.busyLabel ?: "処理"}が終わっていません。送信は継続しますが、画面を離れると結果をここで確認できません。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeaveWhileBusy = false
                    onBack()
                }) { Text("離れる") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeaveWhileBusy = false }) { Text("待つ") }
            },
        )
    }

    removeFileTarget?.let { file ->
        val label = if (file.filepath == "/") file.filename else "${file.filepath}${file.filename}"
        AlertDialog(
            onDismissRequest = { removeFileTarget = null },
            title = { Text("ファイルを削除") },
            text = { Text("「$label」を提出物から削除しますか? 他のファイルは残ります。") },
            confirmButton = {
                TextButton(onClick = {
                    removeFileTarget = null
                    viewModel.removeFile(SubmissionFileKey(file.filepath, file.filename))
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { removeFileTarget = null }) { Text("キャンセル") }
            },
        )
    }

    if (confirmSubmit) {
        val current = state.data
        val requiresStatement = current?.ui?.submissionStatementRequired == true
        val statement = current?.ui?.submissionStatement.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("最終提出しますか") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text("最終提出後は、Moodle側の設定により編集できなくなる場合があります。")
                    if (state.isDirty) {
                        Text(
                            "未保存の文章があります。最終提出の前に保存してから提出します。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (requiresStatement) {
                        if (statement.isNotBlank()) {
                            HtmlText(
                                html = statement,
                                onOpenUrl = { onOpenUrl(it, "提出確認文") },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                    enabled = (!requiresStatement || acceptedStatement) && !state.busy,
                    onClick = {
                        confirmSubmit = false
                        viewModel.finalSubmit(acceptStatement = requiresStatement && acceptedStatement)
                    },
                ) { Text("最終提出する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSubmit = false }) { Text("キャンセル") }
            },
        )
    }

    downloadToShare?.let { file ->
        val url = file.sourceUrl
        if (!url.isNullOrBlank()) {
            DownloadShareDialog(
                container = container,
                fileUrl = url,
                filename = file.filename,
                mimeType = file.mimeType,
                onDismiss = { downloadToShare = null },
            )
        } else {
            downloadToShare = null
        }
    }
}

private enum class DiscardReason { Back, Refresh }

@Composable
private fun OnlineTextEditor(
    state: AssignmentEditorState,
    ui: AssignmentUiModel,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onEditInMoodle: () -> Unit,
) {
    if (ui.onlineTextIsRich) {
        MessageBanner(
            "保存済みの文章に画像・表・リンクなどの書式が含まれています。" +
                "このアプリの入力欄はプレーンテキストのみ対応のため、書式を保つにはMoodleで編集してください。",
            isError = false,
            onDismiss = null,
        )
        OutlinedButton(onClick = onEditInMoodle) { Text("Moodleで編集する") }
        return
    }
    OutlinedTextField(
        value = state.draftText,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (state.isDirty) "提出する文章 (未保存)" else "提出する文章") },
        supportingText = { Text("プレーンテキストとして保存します。空行で段落を区切ります。") },
        minLines = 6,
        enabled = !state.busy,
    )
    Button(
        onClick = onSave,
        enabled = !state.busy && state.isDirty,
    ) { Text("提出内容を保存") }
}

/**
 * 課題を開いてまず知りたい「いつまで」「今どういう状態か」を最上部に大きく出す。
 * 詳細な日付の一覧は [AssignmentSummaryCard] に任せる。
 */
@Composable
private fun AssignmentStatusHero(assignment: Assignment, ui: AssignmentUiModel) {
    val c = MaterialTheme.colorScheme
    val (container, onContainer, accent) = when (ui.availability) {
        AssignmentAvailabilityLabel.DueSoon,
        AssignmentAvailabilityLabel.Overdue,
        -> Triple(c.errorContainer, c.onErrorContainer, c.error)
        AssignmentAvailabilityLabel.Submitted,
        AssignmentAvailabilityLabel.Graded,
        -> Triple(c.secondaryContainer, c.onSecondaryContainer, c.secondary)
        else -> Triple(c.primaryContainer, c.onPrimaryContainer, c.primary)
    }
    AppCard(containerColor = container, contentColor = onContainer, border = null) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("提出期限", style = MaterialTheme.typography.labelMedium, color = onContainer.copy(alpha = 0.8f))
            Text(
                formatAssignmentTime(assignment.duedate),
                style = MaterialTheme.typography.titleLarge,
                color = onContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                StatusPill(ui.availability.text, containerColor = accent, contentColor = container)
                // 「提出済み / 提出済み」のように同じ語が並ぶのを避ける
                if (ui.submission.text != ui.availability.text) {
                    StatusPill(
                        ui.submission.text,
                        containerColor = onContainer.copy(alpha = 0.12f),
                        contentColor = onContainer,
                    )
                }
                if (ui.grading == AssignmentGradingLabel.Graded) {
                    StatusPill(
                        ui.grading.text,
                        containerColor = onContainer.copy(alpha = 0.12f),
                        contentColor = onContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignmentSummaryCard(assignment: Assignment, ui: AssignmentUiModel) {
    SubsectionTitle("詳細")
    AppCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryLine("提出状況", ui.submission.text)
            SummaryLine("採点状況", ui.grading.text)
            SummaryLine("提出可能期間", formatAssignmentTime(assignment.allowsubmissionsfromdate, suffix = " から"))
            SummaryLine("締切 (受付終了)", formatAssignmentTime(assignment.cutoffdate))
            if (ui.hasTimeLimit) SummaryLine("制限時間", formatDuration(assignment.timelimit))
            SummaryLine("提出後の編集", if (ui.canEdit) "可能" else "不可")
        }
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
            Button(onClick = onStart, enabled = !busy) { Text("提出を開始 (計測開始)") }
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
private fun SubmittedTextSection(ui: AssignmentUiModel, onOpenUrl: (String, String) -> Unit) {
    val text = ui.onlineText?.let(::htmlToPlainText)?.takeIf { it.isNotBlank() }
    if (text != null) {
        SubsectionTitle("提出した文章")
        AppCard {
            HtmlText(
                html = ui.onlineText,
                onOpenUrl = { onOpenUrl(it, "提出した文章") },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun IntroAttachmentsSection(
    attachments: List<MoodleFile>,
    onOpenFilePreview: (SubmittedFileUi) -> Unit,
    onDownloadFile: (SubmittedFileUi) -> Unit,
) {
    if (attachments.isEmpty()) return
    SubsectionTitle("課題の添付ファイル")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { file ->
            val ui = SubmittedFileUi(
                filename = file.filename,
                filepath = "/",
                typeLabel = file.mimetype?.substringAfter('/')?.uppercase() ?: "FILE",
                sizeBytes = file.filesize ?: -1L,
                modifiedAt = file.timemodified,
                sourceUrl = file.fileurl,
                mimeType = file.mimetype,
                previewKind = previewKindFor(file.filename, file.mimetype),
            )
            FileCard(ui, canRemove = false, onOpenFilePreview, onDownloadFile, onRemoveFile = {})
        }
    }
}

@Composable
private fun SubmittedFilesSection(
    files: List<SubmittedFileUi>,
    canRemove: Boolean,
    onOpenFilePreview: (SubmittedFileUi) -> Unit,
    onDownloadFile: (SubmittedFileUi) -> Unit,
    onRemoveFile: (SubmittedFileUi) -> Unit,
) {
    SubsectionTitle("提出したファイル")
    if (files.isEmpty()) {
        Text(
            "ファイル提出はありません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            FileCard(file, canRemove, onOpenFilePreview, onDownloadFile, onRemoveFile)
        }
    }
}

@Composable
private fun FileCard(
    file: SubmittedFileUi,
    canRemove: Boolean,
    onOpenFilePreview: (SubmittedFileUi) -> Unit,
    onDownloadFile: (SubmittedFileUi) -> Unit,
    onRemoveFile: (SubmittedFileUi) -> Unit,
) {
    val hasSource = !file.sourceUrl.isNullOrBlank()
    val canPreview = file.previewKind != PreviewKind.Unsupported && file.previewKind != PreviewKind.Html && hasSource
    AppCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeadingIconBox(
                    icon = if (file.previewKind == PreviewKind.Pdf) Icons.Filled.PictureAsPdf else Icons.Filled.Description,
                    contentDescription = null,
                    size = 36.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.filename, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    // ファイル名の下にメタ情報を1行にまとめ、個別行で並べない
                    MetaText(
                        listOf(file.typeLabel, formatBytes(file.sizeBytes), formatAssignmentTime(file.modifiedAt))
                            .joinToString(" · "),
                    )
                    if (file.filepath != "/") MetaText("フォルダ: ${file.filepath}")
                }
            }
            if (!canPreview) {
                Text(
                    previewLabel(file.previewKind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canPreview) {
                    Button(onClick = { onOpenFilePreview(file) }) { Text("プレビュー") }
                }
                if (hasSource) {
                    OutlinedButton(onClick = { onDownloadFile(file) }) { Text("ダウンロード") }
                }
                if (canRemove) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onRemoveFile(file) }) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackBlock(status: SubmissionStatusResponse, onOpenUrl: (String, String) -> Unit) {
    val grade = status.feedback?.grade?.grade?.takeIf { it.isNotBlank() }
    val feedback = status.feedback?.plugins
        ?.flatMap { it.editorfields }
        ?.firstOrNull { it.text.isNotBlank() }
        ?.text
    if (grade != null || !feedback.isNullOrBlank()) {
        SubsectionTitle("評価・フィードバック")
        AppCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (grade != null) {
                    Text("評価", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(grade, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
                }
                if (!feedback.isNullOrBlank()) {
                    HtmlText(
                        html = feedback,
                        onOpenUrl = { onOpenUrl(it, "評価・フィードバック") },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun formatAssignmentTime(epochSeconds: Long, suffix: String = ""): String =
    formatMoodleDateTime(epochSeconds, suffix)

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "なし"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return buildString {
        if (hours > 0) append("${hours}時間")
        if (minutes > 0 || hours == 0L) append("${minutes}分")
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes < 0L -> "不明"
        bytes == 0L -> "0B"
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    }

private fun previewLabel(kind: PreviewKind): String =
    when (kind) {
        PreviewKind.Pdf -> "PDFを表示できます"
        PreviewKind.Image -> "画像を表示できます"
        PreviewKind.Text -> "テキストを表示できます"
        PreviewKind.Html,
        PreviewKind.Unsupported,
        -> "この形式はアプリ内プレビューに未対応です。ダウンロードして他のアプリで開いてください"
    }
