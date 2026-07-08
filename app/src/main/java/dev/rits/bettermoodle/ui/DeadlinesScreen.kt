package dev.rits.bettermoodle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.parseCourseModuleIdFromUrl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 課題締切だけを集めた画面。「通知がごちゃまぜ」問題への回答。
 * core_calendar_get_action_events_by_timesort を assign/quiz 等に絞って表示。
 */
@Composable
fun DeadlinesScreen(
    container: AppContainer,
    onOpenUrl: (url: String, title: String) -> Unit = { _, _ -> },
    onOpenAssignment: (courseId: Long, moduleId: Long, assignId: Long, title: String) -> Unit = { _, _, _, _ -> },
    onOpenQuiz: (target: QuizTarget, title: String) -> Unit = { _, _ -> },
) {
    val (state, refresh) = rememberLoadable {
        container.moodleRepository.upcomingDeadlines(Instant.now().epochSecond - 3600)
    }

    when (val s = state) {
        is UiState.Loading -> LoadingBox()
        is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
        is UiState.Success -> {
            if (s.data.isEmpty()) {
                ErrorBox("今後の課題・小テストの締切はありません 🎉", onRetry = refresh)
                return
            }
            val zone = ZoneId.systemDefault()
            val grouped = s.data.sortedBy { it.timesort }
                .groupBy { Instant.ofEpochSecond(it.timesort).atZone(zone).toLocalDate() }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                grouped.forEach { (date, events) ->
                    item(key = "header-$date") {
                        Text(
                            text = dateLabel(date),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(events, key = { it.id }) { event ->
                        DeadlineCard(event) {
                            openDeadlineEvent(
                                event = event,
                                onOpenUrl = onOpenUrl,
                                onOpenAssignment = onOpenAssignment,
                                onOpenQuiz = onOpenQuiz,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openDeadlineEvent(
    event: ActionEvent,
    onOpenUrl: (url: String, title: String) -> Unit,
    onOpenAssignment: (courseId: Long, moduleId: Long, assignId: Long, title: String) -> Unit,
    onOpenQuiz: (target: QuizTarget, title: String) -> Unit,
) {
    val title = event.activityname ?: event.name
    val actionUrl = event.action?.url?.takeIf { it.isNotBlank() }
    val fallbackUrl = actionUrl ?: event.url
    val courseId = event.course?.id?.takeIf { it > 0L }
    val moduleId = parseCourseModuleIdFromUrl(actionUrl)
    val instanceId = event.instance?.takeIf { it > 0L }

    when (event.modulename) {
        "assign" -> if (courseId != null && moduleId != null && instanceId != null) {
            onOpenAssignment(courseId, moduleId, instanceId, title)
            return
        }
        "quiz" -> if (courseId != null && moduleId != null && instanceId != null) {
            onOpenQuiz(
                QuizTarget(
                    courseId = courseId,
                    courseModuleId = moduleId,
                    quizInstanceId = instanceId,
                    contextId = null,
                    modName = "quiz",
                    url = actionUrl,
                ),
                title,
            )
            return
        }
    }

    if (!fallbackUrl.isNullOrBlank()) {
        onOpenUrl(fallbackUrl, title)
    }
}

private fun dateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今日 ${date.format(DateTimeFormatter.ofPattern("M/d"))}"
        today.plusDays(1) -> "明日 ${date.format(DateTimeFormatter.ofPattern("M/d"))}"
        else -> date.format(DateTimeFormatter.ofPattern("M/d (E)"))
    }
}

@Composable
private fun DeadlineCard(event: ActionEvent, onClick: () -> Unit) {
    val time = Instant.ofEpochSecond(event.timesort)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    val isUrgent = event.timesort - Instant.now().epochSecond < 24 * 3600

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isUrgent) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (event.modulename) {
                    "assign" -> "📝"
                    "quiz" -> "🧪"
                    else -> "📌"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.activityname ?: event.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                event.course?.let {
                    Text(
                        it.fullname,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                time,
                style = MaterialTheme.typography.titleMedium,
                color = if (isUrgent) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
