package dev.rits.bettermoodle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.BuildConfig
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.data.MoodleWsException
import dev.rits.bettermoodle.data.Quiz
import dev.rits.bettermoodle.data.QuizAccessInformationResponse
import dev.rits.bettermoodle.data.QuizAttempt
import dev.rits.bettermoodle.data.QuizBestGradeResponse
import dev.rits.bettermoodle.data.QuizLoadErrorKind
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.canFallbackToWebView
import dev.rits.bettermoodle.data.classifyQuizLoadError
import dev.rits.bettermoodle.data.diagnosticCode
import dev.rits.bettermoodle.data.quizAttemptStateLabel
import dev.rits.bettermoodle.data.quizAttemptsLimitLabel
import dev.rits.bettermoodle.data.quizGradeLabel
import dev.rits.bettermoodle.data.quizGradeMethodLabel
import dev.rits.bettermoodle.data.quizHttpStatus
import dev.rits.bettermoodle.data.quizLoadErrorMessage
import dev.rits.bettermoodle.data.quizMoodleErrorCode
import dev.rits.bettermoodle.data.quizTimeLimitLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Grade
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing
import dev.rits.bettermoodle.ui.theme.TABULAR_NUMS

private data class QuizUiData(
    val quiz: Quiz,
    val attempts: List<QuizAttempt>,
    val bestGrade: QuizBestGradeResponse,
    val access: QuizAccessInformationResponse,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    container: AppContainer,
    target: QuizTarget,
    title: String,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    onFallbackWeb: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UiState<QuizUiData>>(UiState.Loading) }
    var diagnostic by remember { mutableStateOf<QuizDiagnostic?>(null) }

    fun refresh() {
        refreshTick++
    }

    LaunchedEffect(target, refreshTick) {
        if (!target.isValid) {
            diagnostic = QuizDiagnostic(
                stage = "TARGET",
                wsFunction = null,
                kind = QuizLoadErrorKind.InvalidQuizTarget,
                moodleErrorCode = null,
                httpStatus = null,
                target = target,
            )
            state = UiState.Error(quizLoadErrorMessage(QuizLoadErrorKind.InvalidQuizTarget))
            return@LaunchedEffect
        }

        state = UiState.Loading
        diagnostic = null
        state = try {
            val quiz = container.moodleRepository.quiz(target.courseId, target.courseModuleId)
                ?: throw MoodleWsException("invalidrecord", "Quiz not found")
            val quizData = coroutineScope {
                val attempts = async { container.moodleRepository.quizAttempts(quiz.id) }
                val bestGrade = async { container.moodleRepository.quizBestGrade(quiz.id) }
                val access = async { container.moodleRepository.quizAccessInformation(quiz.id) }

                QuizUiData(
                    quiz = quiz,
                    attempts = attempts.await(),
                    bestGrade = bestGrade.await(),
                    access = access.await(),
                )
            }
            UiState.Success(
                quizData,
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val kind = classifyQuizLoadError(error)
            diagnostic = QuizDiagnostic(
                stage = "QUIZ_SUMMARY",
                wsFunction = "mod_quiz_get_quizzes_by_courses",
                kind = kind,
                moodleErrorCode = quizMoodleErrorCode(error),
                httpStatus = quizHttpStatus(error),
                target = target,
            )
            UiState.Error(quizLoadErrorMessage(kind))
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
                colors = appTopBarColors(),
            )
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> LoadingBox()
            is UiState.Error -> QuizErrorContent(
                padding = padding,
                message = s.message,
                diagnostic = diagnostic,
                onRetry = ::refresh,
                onFallbackWeb = onFallbackWeb,
            )
            is UiState.Success -> QuizContent(
                padding = padding,
                data = s.data,
                canOpenWeb = !target.url.isNullOrBlank(),
                onOpenUrl = onOpenUrl,
                onFallbackWeb = onFallbackWeb,
            )
        }
    }
}

@Composable
private fun QuizContent(
    padding: PaddingValues,
    data: QuizUiData,
    canOpenWeb: Boolean,
    onOpenUrl: (String, String) -> Unit,
    onFallbackWeb: () -> Unit,
) {
    val quiz = data.quiz
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        DetailTitle(quiz.name, typeLabel = "小テスト", typeIcon = Icons.Outlined.Quiz)
        QuizStatusHero(quiz = quiz, access = data.access, bestGrade = data.bestGrade)
        QuizAccessBlock(data.access)
        if (htmlHasRenderableContent(quiz.intro)) {
            DetailCard(title = "説明", icon = Icons.Outlined.Description) {
                HtmlText(
                    html = quiz.intro,
                    onOpenUrl = { onOpenUrl(it, quiz.name) },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        QuizSummaryCard(quiz = quiz, bestGrade = data.bestGrade)
        QuizAttemptsCard(data.attempts)

        PrimaryButton(
            "受験する (Moodleを開く)",
            onClick = onFallbackWeb,
            enabled = canOpenWeb,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm),
        )
    }
}

/** 受験可否と受付終了をまず見せる。受験できない場合は理由を [QuizAccessBlock] で説明する。 */
@Composable
private fun QuizStatusHero(quiz: Quiz, access: QuizAccessInformationResponse, bestGrade: QuizBestGradeResponse) {
    val t = AppTheme.colors
    val canAttempt = access.canattempt
    val nowSec = System.currentTimeMillis() / 1000
    val remaining = quiz.timeclose.takeIf { it > 0L && it >= nowSec }?.let { remainingLabel(nowSec, it) }
    val soon = quiz.timeclose > 0L && quiz.timeclose - nowSec in 0 until 24 * 3600
    val tone = when {
        !canAttempt -> Tone.Neutral
        soon -> Tone.Warning
        else -> Tone.Accent
    }
    StatusHeroCard(
        tone = tone,
        icon = Icons.Outlined.Schedule,
        label = "受付終了",
        value = formatQuizTime(quiz.timeclose),
        remaining = remaining,
    ) {
        HeroPill(
            if (canAttempt) "受験できます" else "現在は受験できません",
            color = if (canAttempt) t.success else t.inkSecondary,
        )
        if (bestGrade.hasgrade) {
            HeroPill("最高 ${quizGradeLabel(bestGrade.grade)} / ${quizGradeLabel(quiz.grade)}")
        }
    }
}

@Composable
private fun QuizSummaryCard(quiz: Quiz, bestGrade: QuizBestGradeResponse) {
    DetailCard(title = "詳細", icon = Icons.Outlined.Info) {
        InfoRow(Icons.Outlined.EventAvailable, "受験開始", formatQuizTime(quiz.timeopen))
        CardDivider(inset = 32.dp)
        InfoRow(Icons.Outlined.Timer, "制限時間", quizTimeLimitLabel(quiz.timelimit))
        CardDivider(inset = 32.dp)
        InfoRow(Icons.Outlined.Repeat, "受験可能回数", quizAttemptsLimitLabel(quiz.attempts))
        CardDivider(inset = 32.dp)
        InfoRow(Icons.Outlined.Calculate, "評定方法", quizGradeMethodLabel(quiz.grademethod))
        if (bestGrade.hasgrade) {
            CardDivider(inset = 32.dp)
            InfoRow(
                Icons.Outlined.Grade,
                "最高評点",
                "${quizGradeLabel(bestGrade.grade)} / ${quizGradeLabel(quiz.grade)}",
                valueColor = AppTheme.colors.success,
            )
        }
    }
}

@Composable
private fun QuizAttemptsCard(attempts: List<QuizAttempt>) {
    DetailCard(
        title = "これまでの受験",
        icon = Icons.Outlined.History,
        trailing = if (attempts.isNotEmpty()) ({ MetaText("${attempts.size}回") }) else null,
    ) {
        if (attempts.isEmpty()) {
            Text(
                "まだ受験していません",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.inkSecondary,
            )
            return@DetailCard
        }
        attempts.forEachIndexed { index, attempt ->
            if (index > 0) CardDivider(inset = 40.dp + Spacing.md)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.sm)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(AppTheme.colors.surfaceSunken, RoundedCornerShape(Radius.sm)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${attempt.attempt}",
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR_NUMS),
                        color = AppTheme.colors.ink,
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text("${attempt.attempt}回目", style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.ink)
                    MetaText("終了 ${formatQuizTime(attempt.timefinish)}")
                }
                val state = quizAttemptStateLabel(attempt.state)
                StatusPill(
                    state,
                    when (attempt.state.lowercase()) {
                        "finished" -> Tone.Success
                        "inprogress" -> Tone.Accent
                        else -> Tone.Neutral
                    },
                )
            }
        }
    }
}

@Composable
private fun QuizAccessBlock(access: QuizAccessInformationResponse) {
    if (access.canattempt && access.preventaccessreasons.isEmpty()) return
    val message = buildString {
        append(if (access.canattempt) "受験時の注意" else "受験できない理由")
        access.preventaccessreasons.forEach { append("\n・").append(it) }
        if (!access.canattempt) append("\nサーバ側が最終判断するため、Moodle画面を開く操作は残しています。")
    }
    MessageBanner(
        message,
        isError = false,
        onDismiss = null,
        tone = if (access.canattempt) Tone.Accent else Tone.Warning,
    )
}

@Composable
private fun QuizErrorContent(
    padding: PaddingValues,
    message: String,
    diagnostic: QuizDiagnostic?,
    onRetry: () -> Unit,
    onFallbackWeb: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        SecondaryButton("再読み込み", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        if (diagnostic?.kind?.canFallbackToWebView() == true) {
            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton("Moodle画面で開く", onClick = onFallbackWeb, modifier = Modifier.fillMaxWidth())
        }
        if (BuildConfig.DEBUG && diagnostic != null) {
            Text(
                "errorcode: ${diagnostic.safeMoodleErrorCode() ?: diagnostic.kind.diagnosticCode()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppCard(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    diagnostic.toSafeText(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private data class QuizDiagnostic(
    val stage: String,
    val wsFunction: String?,
    val kind: QuizLoadErrorKind,
    val moodleErrorCode: String?,
    val httpStatus: Int?,
    val target: QuizTarget,
)

private fun QuizDiagnostic.safeMoodleErrorCode(): String? =
    moodleErrorCode?.takeIf { SAFE_MOODLE_ERROR_CODE.matches(it) }

private val SAFE_MOODLE_ERROR_CODE = Regex("[A-Za-z0-9_-]{1,64}")

private fun QuizDiagnostic.toSafeText(): String = buildString {
    appendLine("診断情報")
    appendLine("stage=$stage")
    appendLine("wsfunction=${wsFunction ?: "-"}")
    appendLine("kind=${kind.diagnosticCode()}")
    appendLine("moodleErrorCode=${moodleErrorCode ?: "-"}")
    appendLine("httpStatus=${httpStatus ?: "-"}")
    appendLine("courseId=${target.courseId}")
    appendLine("courseModuleId=${target.courseModuleId}")
    appendLine("quizInstanceId=${target.quizInstanceId}")
    appendLine("contextId=${target.contextId ?: "-"}")
    appendLine("modName=${target.modName}")
}

private fun formatQuizTime(epochSeconds: Long): String =
    formatMoodleDateTime(epochSeconds)
