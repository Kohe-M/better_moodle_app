package dev.rits.bettermoodle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.BuildConfig
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
            UiState.Success(
                QuizUiData(
                    quiz = quiz,
                    attempts = container.moodleRepository.quizAttempts(quiz.id),
                    bestGrade = container.moodleRepository.quizBestGrade(quiz.id),
                    access = container.moodleRepository.quizAccessInformation(quiz.id),
                ),
            )
        } catch (error: Exception) {
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(quiz.name, style = MaterialTheme.typography.headlineSmall)
        HtmlText(
            html = quiz.intro,
            onOpenUrl = { onOpenUrl(it, quiz.name) },
            style = MaterialTheme.typography.bodyMedium,
        )

        QuizSummaryCard(quiz = quiz, bestGrade = data.bestGrade)
        QuizAttemptsCard(data.attempts)
        QuizAccessBlock(data.access)

        Button(
            onClick = onFallbackWeb,
            enabled = canOpenWeb,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("受験する(Moodleを開く)")
        }
    }
}

@Composable
private fun QuizSummaryCard(quiz: Quiz, bestGrade: QuizBestGradeResponse) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("小テストサマリー", style = MaterialTheme.typography.titleMedium)
            SummaryLine("受験可能期間", "${formatQuizTime(quiz.timeopen)}〜${formatQuizTime(quiz.timeclose)}")
            SummaryLine("制限時間", quizTimeLimitLabel(quiz.timelimit))
            SummaryLine("受験可能回数", quizAttemptsLimitLabel(quiz.attempts))
            SummaryLine("評定方法", quizGradeMethodLabel(quiz.grademethod))
            if (bestGrade.hasgrade) {
                SummaryLine("最高評点", "${quizGradeLabel(bestGrade.grade)} / ${quizGradeLabel(quiz.grade)}")
            }
        }
    }
}

@Composable
private fun QuizAttemptsCard(attempts: List<QuizAttempt>) {
    Text("これまでの受験", style = MaterialTheme.typography.titleMedium)
    if (attempts.isEmpty()) {
        Text("受験履歴はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attempts.forEach { attempt ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${attempt.attempt}回目", fontWeight = FontWeight.Bold)
                    Text("状態: ${quizAttemptStateLabel(attempt.state)}")
                    Text("終了日時: ${formatQuizTime(attempt.timefinish)}")
                }
            }
        }
    }
}

@Composable
private fun QuizAccessBlock(access: QuizAccessInformationResponse) {
    if (access.canattempt && access.preventaccessreasons.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (access.canattempt) "受験時の注意" else "現在は受験できません",
            style = MaterialTheme.typography.titleMedium,
        )
        access.preventaccessreasons.forEach { reason ->
            Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!access.canattempt) {
            Text(
                "サーバ側が最終判断するため、Moodle画面を開く操作は残しています。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("再読み込み")
        }
        if (diagnostic?.kind?.canFallbackToWebView() == true) {
            Button(onClick = onFallbackWeb, modifier = Modifier.fillMaxWidth()) {
                Text("Moodle画面で開く")
            }
        }
        if (BuildConfig.DEBUG && diagnostic != null) {
            Text(
                "errorcode: ${diagnostic.safeMoodleErrorCode() ?: diagnostic.kind.diagnosticCode()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
