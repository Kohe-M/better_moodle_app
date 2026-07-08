package dev.rits.bettermoodle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val (state, refresh) = rememberLoadable { container.moodleRepository.enrolledCourses() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("すべてのコース") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
            is UiState.Success -> {
                if (s.data.isEmpty()) {
                    ErrorBox("履修中のコースが見つかりません", onRetry = refresh)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
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

@Composable
private fun CourseCard(course: Course, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = course.displayName.ifBlank { course.fullname },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                Text(
                    text = "コースコード: ${course.courseCode ?: course.shortname.ifBlank { "-" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
