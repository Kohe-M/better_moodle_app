package dev.rits.bettermoodle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.Timetable
import dev.rits.bettermoodle.data.TimetableEntry
import java.time.LocalDate

/**
 * 時間割画面。スクロールなしで全コマを1画面に表示する (一覧性重視)。
 * コマをタップするとアプリ内のコース画面へ直接遷移する。
 */
@Composable
fun TimetableScreen(
    container: AppContainer,
    onOpenCourse: (courseId: Long, courseName: String, courseCode: String?) -> Unit,
) {
    val (state, refresh) = rememberLoadable { container.moodleRepository.timetable() }

    when (val s = state) {
        is UiState.Loading -> LoadingBox()
        is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
        is UiState.Success -> {
            if (s.data.entries.isEmpty()) {
                ErrorBox(
                    "時間割ブロックを取得できませんでした。\nMoodleのダッシュボードに時間割ブロックが表示されているか確認してください。",
                    onRetry = refresh,
                )
            } else {
                TimetableGrid(s.data, onOpenCourse)
            }
        }
    }
}

@Composable
private fun TimetableGrid(
    timetable: Timetable,
    onOpenCourse: (Long, String, String?) -> Unit,
) {
    val maxPeriod = maxOf(timetable.entries.maxOfOrNull { it.period } ?: 5, 5)
    val todayIndex = LocalDate.now().dayOfWeek.value - 1 // 0=月

    val byCell = timetable.entries.groupBy { it.dayIndex to it.period }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // 曜日ヘッダ
        Row(modifier = Modifier.fillMaxWidth().height(30.dp)) {
            Box(Modifier.width(30.dp))
            timetable.dayLabels.forEachIndexed { i, label ->
                HeaderCell(label, Modifier.weight(1f), highlight = i == todayIndex)
            }
        }
        // 本体: 残り高さを時限数で均等割 → スクロール不要
        for (period in 1..maxPeriod) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PeriodCell(period, Modifier.width(30.dp))
                timetable.dayLabels.forEachIndexed { dayIdx, _ ->
                    val entries = byCell[dayIdx to period].orEmpty()
                    SubjectCell(
                        entries = entries,
                        highlight = dayIdx == todayIndex,
                        modifier = Modifier.weight(1f),
                        onClick = { entry ->
                            val id = entry.courseId
                            if (id != null) onOpenCourse(id, entry.title, entry.courseCode)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, highlight: Boolean) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeriodCell(period: Int, modifier: Modifier) {
    val times = Timetable.PERIOD_TIMES[period]
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$period",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (times != null) {
            Text(times.first, fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(times.second, fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubjectCell(
    entries: List<TimetableEntry>,
    highlight: Boolean,
    modifier: Modifier,
    onClick: (TimetableEntry) -> Unit,
) {
    val entry = entries.firstOrNull()
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    entry == null -> MaterialTheme.colorScheme.surface
                    highlight -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
            )
            .clickable(enabled = entry != null) { entry?.let(onClick) },
        contentAlignment = Alignment.Center,
    ) {
        if (entry != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp),
            ) {
                Text(
                    entries.joinToString("\n") { it.title },
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                val room = entries.firstNotNullOfOrNull { it.room }
                if (!room.isNullOrBlank()) {
                    Text(
                        room,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}
