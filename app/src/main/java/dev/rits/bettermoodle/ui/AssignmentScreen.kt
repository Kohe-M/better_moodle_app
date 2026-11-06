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
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.rits.bettermoodle.data.AssignmentUiModel
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.SubmissionFileKey
import dev.rits.bettermoodle.data.SubmissionStatusResponse
import dev.rits.bettermoodle.data.SubmittedFileUi
import dev.rits.bettermoodle.data.previewKindFor
import dev.rits.bettermoodle.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Grade
import androidx.compose.material.icons.automirrored.outlined.Grading
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.ui.text.style.TextOverflow
import dev.rits.bettermoodle.data.AssignmentSubmissionLabel
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing
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
                    val canEdit = AssignmentAction.Edit in ui.actions
                    DetailTitle(assignment.name, typeLabel = "課題", typeIcon = Icons.AutoMirrored.Outlined.Assignment)
                    AssignmentStatusHero(assignment, ui)
                    ui.unsupportedReason?.let { reason ->
                        MessageBanner(reason, isError = false, onDismiss = null)
                    }
                    if (htmlHasRenderableContent(assignment.intro)) {
                        DetailCard(title = "課題の説明", icon = Icons.Outlined.Description) {
                            HtmlText(
                                html = assignment.intro,
                                onOpenUrl = { onOpenUrl(it, assignment.name) },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    IntroAttachmentsSection(
                        attachments = assignment.introattachments,
                        onOpenFilePreview = onOpenFilePreview,
                        onDownloadFile = { downloadToShare = it },
                    )
                    AssignmentDetails(assignment, ui)
                    FeedbackBlock(load.data.status, onOpenUrl)

                    DetailCard(title = "あなたの提出", icon = Icons.Outlined.Upload) {
                        SubmittedTextSection(ui, onOpenUrl)
                        SubmittedFilesSection(
                            files = ui.files,
                            canRemove = canEdit && assignment.supports("file") && !state.busy,
                            onOpenFilePreview = onOpenFilePreview,
                            onDownloadFile = { downloadToShare = it },
                            onRemoveFile = { removeFileTarget = it },
                        )
                        if (assignment.supports("file") && canEdit) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = Spacing.md),
                            ) {
                                SecondaryButton(
                                    "ファイルを追加",
                                    onClick = { filePicker.launch(arrayOf("*/*")) },
                                    enabled = !state.busy,
                                    leading = { Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                                val max = assignment.maxFileSubmissions
                                if (max > 0) {
                                    Spacer(Modifier.width(Spacing.md))
                                    MetaText("${ui.files.size} / $max 件")
                                }
                            }
                        }
                        if (assignment.supports("onlinetext") && canEdit) {
                            Spacer(Modifier.height(Spacing.lg))
                            OnlineTextEditor(
                                state = state,
                                ui = ui,
                                onTextChange = viewModel::updateText,
                                onSave = { viewModel.saveText() },
                                onEditInMoodle = { onOpenMoodleWeb(webUrl, title) },
                            )
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
                    TertiaryButton(
                        "Moodleでこの課題を開く",
                        onClick = { onOpenMoodleWeb(webUrl, title) },
                        modifier = Modifier.fillMaxWidth(),
                    )
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
    SubsectionTitle("提出する文章")
    Spacer(Modifier.height(Spacing.sm))
    if (ui.onlineTextIsRich) {
        MessageBanner(
            "保存済みの文章に画像・表・リンクなどの書式が含まれています。" +
                "このアプリの入力欄はプレーンテキストのみ対応のため、書式を保つにはMoodleで編集してください。",
            isError = false,
            onDismiss = null,
        )
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton("Moodleで編集する", onClick = onEditInMoodle)
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
    SecondaryButton(
        "提出内容を保存",
        onClick = onSave,
        enabled = !state.busy && state.isDirty,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

/**
 * 課題を開いてまず知りたい「いつまで」「今どういう状態か」を最上部の状態カードに出す。
 * 面の色調は意味色 (24時間以内 = 琥珀、期限切れ = 朱、提出済み = 緑、それ以外 = accent) で、札の文言と併用する。
 * 詳細な日付の一覧は [AssignmentDetails] に任せる。
 */
@Composable
private fun AssignmentStatusHero(assignment: Assignment, ui: AssignmentUiModel) {
    val t = AppTheme.colors
    val tone = when (ui.availability) {
        AssignmentAvailabilityLabel.DueSoon -> Tone.Warning
        AssignmentAvailabilityLabel.Overdue -> Tone.Danger
        AssignmentAvailabilityLabel.Submitted,
        AssignmentAvailabilityLabel.Graded,
        -> Tone.Success
        else -> Tone.Accent
    }
    val stateColor = toneColors(tone).second
    val nowSec = System.currentTimeMillis() / 1000
    val remaining = assignment.duedate.takeIf { it > 0L && it >= nowSec }?.let { remainingLabel(nowSec, it) }
    StatusHeroCard(
        tone = tone,
        icon = Icons.Outlined.Schedule,
        label = "提出期限",
        value = formatAssignmentTime(assignment.duedate),
        remaining = remaining,
    ) {
        HeroPill(ui.availability.text, color = stateColor)
        // 「提出済み / 提出済み」のように同じ語が並ぶのを避ける
        if (ui.submission.text != ui.availability.text) {
            HeroPill(
                ui.submission.text,
                color = if (ui.submission == AssignmentSubmissionLabel.Submitted) t.success else t.ink,
            )
        }
        if (ui.grading == AssignmentGradingLabel.Graded && ui.availability != AssignmentAvailabilityLabel.Graded) {
            HeroPill(ui.grading.text, color = t.success)
        }
    }
}

/** 詳細はアイコン付きの「ラベル … 値」の行。提出状況は最上部の札に出しているので繰り返さない */
@Composable
private fun AssignmentDetails(assignment: Assignment, ui: AssignmentUiModel) {
    DetailCard(title = "詳細", icon = Icons.Outlined.Info) {
        InfoRow(Icons.AutoMirrored.Outlined.Grading, "採点状況", ui.grading.text)
        CardDivider(inset = InfoRowTextInset)
        InfoRow(Icons.Outlined.EventAvailable, "提出開始", formatAssignmentTime(assignment.allowsubmissionsfromdate, suffix = " から"))
        CardDivider(inset = InfoRowTextInset)
        InfoRow(Icons.Outlined.EventBusy, "受付終了", formatAssignmentTime(assignment.cutoffdate))
        if (ui.hasTimeLimit) {
            CardDivider(inset = InfoRowTextInset)
            InfoRow(Icons.Outlined.Timer, "制限時間", formatDuration(assignment.timelimit))
        }
        CardDivider(inset = InfoRowTextInset)
        InfoRow(Icons.Outlined.Edit, "提出後の編集", if (ui.canEdit) "できる" else "できない")
    }
}

/** 詳細カードの行の区切り線はアイコンの右から引く */
private val InfoRowTextInset = 32.dp

@Composable
private fun AssignmentActions(
    ui: AssignmentUiModel,
    busy: Boolean,
    onStart: () -> Unit,
    onFinalSubmit: () -> Unit,
) {
    if (ui.actions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
        if (AssignmentAction.Start in ui.actions) {
            PrimaryButton("提出を開始 (計測開始)", onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
        if (AssignmentAction.FinalSubmit in ui.actions) {
            PrimaryButton(
                "最終提出する",
                onClick = onFinalSubmit,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                leading = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun SubmittedTextSection(ui: AssignmentUiModel, onOpenUrl: (String, String) -> Unit) {
    val text = ui.onlineText?.let(::htmlToPlainText)?.takeIf { it.isNotBlank() }
    if (text != null) {
        SubsectionTitle("提出した文章")
        Spacer(Modifier.height(Spacing.sm))
        HtmlText(
            html = ui.onlineText,
            onOpenUrl = { onOpenUrl(it, "提出した文章") },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surfaceSunken, RoundedCornerShape(Radius.sm))
                .padding(Spacing.md),
        )
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun IntroAttachmentsSection(
    attachments: List<MoodleFile>,
    onOpenFilePreview: (SubmittedFileUi) -> Unit,
    onDownloadFile: (SubmittedFileUi) -> Unit,
) {
    if (attachments.isEmpty()) return
    DetailCard(
        title = "課題の添付ファイル",
        icon = Icons.Filled.AttachFile,
        trailing = { MetaText("${attachments.size}件") },
    ) {
        attachments.forEachIndexed { index, file ->
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
            if (index > 0) CardDivider(inset = FileTextInset)
            FileItem(ui, canRemove = false, onOpenFilePreview, onDownloadFile, onRemoveFile = {})
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.sm)) {
            IconTile(Icons.Outlined.FolderOpen, size = 32.dp, iconSize = 18.dp)
            Spacer(Modifier.width(Spacing.md))
            Text(
                "まだファイルを提出していません",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.inkSecondary,
            )
        }
        return
    }
    files.forEachIndexed { index, file ->
        if (index > 0) CardDivider(inset = FileTextInset)
        FileItem(file, canRemove, onOpenFilePreview, onDownloadFile, onRemoveFile)
    }
}

/** ファイル行の本文の開始位置 (種類のタイルの分)。区切り線もここから引く */
private val FileTextInset = 40.dp + Spacing.md

private fun fileIcon(kind: PreviewKind) = when (kind) {
    PreviewKind.Pdf -> Icons.Outlined.PictureAsPdf
    PreviewKind.Image -> Icons.Outlined.Image
    else -> Icons.Outlined.Description
}

@Composable
private fun FileItem(
    file: SubmittedFileUi,
    canRemove: Boolean,
    onOpenFilePreview: (SubmittedFileUi) -> Unit,
    onDownloadFile: (SubmittedFileUi) -> Unit,
    onRemoveFile: (SubmittedFileUi) -> Unit,
) {
    val hasSource = !file.sourceUrl.isNullOrBlank()
    val canPreview = file.previewKind != PreviewKind.Unsupported && file.previewKind != PreviewKind.Html && hasSource
    // 下の余白は操作ボタンの高さ (48) が担う
    Column(Modifier.padding(top = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 赤・緑は状態 (期限切れ・提出済み) 専用なので、種類はアイコンの形で見分ける
            IconTile(fileIcon(file.previewKind), tone = Tone.Accent)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    file.filename,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppTheme.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                color = AppTheme.colors.inkSecondary,
                modifier = Modifier.padding(start = FileTextInset, top = Spacing.xs),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(start = FileTextInset - Spacing.md, top = Spacing.xs),
        ) {
            if (canPreview) {
                TertiaryButton("プレビュー", onClick = { onOpenFilePreview(file) })
            }
            if (hasSource) {
                TertiaryButton("ダウンロード", onClick = { onDownloadFile(file) })
            }
            if (canRemove) {
                Spacer(Modifier.weight(1f))
                TertiaryButton("削除", onClick = { onRemoveFile(file) }, destructive = true)
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
        DetailCard(title = "評価・フィードバック", icon = Icons.Outlined.Grade) {
            if (grade != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(AppTheme.colors.successSoft, RoundedCornerShape(Radius.sm))
                        .padding(Spacing.md),
                ) {
                    Text("評価", style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.success)
                    Text(
                        grade,
                        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                        color = AppTheme.colors.ink,
                    )
                }
            }
            if (!feedback.isNullOrBlank()) {
                if (grade != null) Spacer(Modifier.height(Spacing.md))
                HtmlText(
                    html = feedback,
                    onOpenUrl = { onOpenUrl(it, "評価・フィードバック") },
                    style = MaterialTheme.typography.bodyMedium,
                )
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
