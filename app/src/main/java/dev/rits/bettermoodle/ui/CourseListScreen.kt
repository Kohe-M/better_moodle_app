package dev.rits.bettermoodle.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.Course

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenCourse: (courseId: Long, courseName: String, courseCode: String?) -> Unit,
) {
    // 「すべてのコース」は既定で進行中のみ。過去・未来のコースは期間フィルタで切り替える
    var classification by rememberSaveable { mutableStateOf("inprogress") }
    val cacheKey = if (classification == "inprogress") "courses" else "courses:$classification"
    val (state, refresh) = rememberLoadable(cacheKey) { container.moodleRepository.enrolledCourses(classification) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("すべてのコース") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = appTopBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                COURSE_PERIOD_FILTERS.forEach { (value, label) ->
                    FilterChip(
                        selected = classification == value,
                        onClick = { classification = value },
                        label = { Text(label) },
                    )
                }
            }
        StaleDataBanner(state, onRetry = refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val s = state) {
                is UiState.Loading -> LoadingBox()
                is UiState.Error -> ErrorBox(s.message, onRetry = refresh)
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        EmptyBox("該当する期間のコースが見つかりません", onRetry = refresh, icon = Icons.Outlined.School)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = ListContentPadding,
                            verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
                        ) {
                            items(s.data, key = { it.id }) { course ->
                                CourseCard(course) {
                                    onOpenCourse(course.id, course.displayName, course.courseCode)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

private val COURSE_PERIOD_FILTERS = listOf(
    "inprogress" to "進行中",
    "future" to "今後",
    "past" to "過去",
    "all" to "すべて",
)

@Composable
private fun CourseCard(course: Course, onClick: () -> Unit) {
    val code = course.courseCode ?: course.shortname.ifBlank { null }
    AppCard(onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeadingIconBox(Icons.Outlined.School, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = course.displayName.ifBlank { course.fullname },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (code != null) {
                    MetaText("授業コード $code", modifier = Modifier.padding(top = 2.dp))
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
