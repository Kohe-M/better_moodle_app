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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.Course
import dev.rits.bettermoodle.data.CourseColors
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Radius
import dev.rits.bettermoodle.ui.theme.Spacing

/**
 * コースタブ (DESIGN.md §8.4・§9): 検索 → 今学期のコース → 過去のコース・今後のコース (折りたたみ)。
 * カード型ではなくリスト。シラバスとポータルへの入口もここに置く (タブを5つより増やさない)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesScreen(
    container: AppContainer,
    onOpenCourse: (courseId: Long, courseName: String, courseCode: String?) -> Unit,
    onOpenSyllabus: () -> Unit,
    onOpenPortal: () -> Unit,
) {
    val (state, refresh) = rememberLoadable("courses") { container.moodleRepository.enrolledCourses("inprogress") }
    var showPast by rememberSaveable { mutableStateOf(false) }
    var showFuture by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        StaleDataBanner(state, onRetry = refresh)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = RowListPadding) {
                item(key = "search") { CourseSearchField(query, onChange = { query = it }) }
                item(key = "current-h") { SectionLabel("今学期のコース") }
                when (val s = state) {
                    is UiState.Loading -> item(key = "loading") {
                        Column(Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm)) {
                            repeat(4) {
                                SkeletonBlock(width = null, height = 16.dp)
                                Spacer(Modifier.padding(vertical = Spacing.sm))
                            }
                        }
                    }
                    is UiState.Error -> item(key = "error") {
                        Column(Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm)) {
                            MessageBanner("コースを読み込めませんでした (${s.message})", isError = true, onDismiss = null)
                            TertiaryButton("再試行", onClick = refresh)
                        }
                    }
                    is UiState.Success -> {
                        val courses = s.data.filter { it.matches(query) }
                        if (courses.isEmpty()) {
                            item(key = "empty") {
                                MetaText(
                                    if (query.isBlank()) "今学期のコースはありません" else "「$query」に一致するコースはありません",
                                    modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm),
                                )
                            }
                        }
                        courseItems("cur", courses, onOpenCourse)
                    }
                }

                item(key = "past-h") {
                    CollapsibleHeader("過去のコース", expanded = showPast, onToggle = { showPast = !showPast })
                }
                if (showPast) {
                    item(key = "past") { OtherCourses(container, "past", "過去のコース", query, onOpenCourse) }
                }

                // 学期が始まる前に公開された次学期のコース
                item(key = "future-h") {
                    CollapsibleHeader("今後のコース", expanded = showFuture, onToggle = { showFuture = !showFuture })
                }
                if (showFuture) {
                    item(key = "future") { OtherCourses(container, "future", "今後のコース", query, onOpenCourse) }
                }

                item(key = "links-h") { SectionLabel("大学のサービス") }
                item(key = "syllabus") {
                    LinkRow(
                        Icons.AutoMirrored.Outlined.MenuBook, "シラバス", "授業コード・キーワードで検索", onOpenSyllabus,
                        modifier = Modifier.groupedItem(GroupPosition.First),
                    )
                }
                item(key = "portal") {
                    LinkRow(
                        Icons.Outlined.Public, "スチューデントポータル", "大学からのお知らせ・各種手続き", onOpenPortal,
                        modifier = Modifier.groupedItem(GroupPosition.Last, dividerInset = LinkTextInset),
                    )
                }
                item(key = "sync") { SyncFooter(state) }
            }
        }
    }
}

private fun Course.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return displayName.contains(q, ignoreCase = true) || fullname.contains(q, ignoreCase = true) || courseCode == q
}

private fun LazyListScope.courseItems(
    prefix: String,
    courses: List<Course>,
    onOpenCourse: (Long, String, String?) -> Unit,
) {
    itemsIndexed(courses, key = { _, it -> "$prefix-${it.id}" }) { index, course ->
        CourseRow(course, Modifier.groupedItem(GroupPosition.of(index, courses.size), dividerInset = CourseTextInset)) {
            onOpenCourse(course.id, course.displayName.ifBlank { course.fullname }, course.courseCode)
        }
    }
}

/** 過去・今後のコース ([classification] = "past" / "future") は展開したときだけ読み込む */
@Composable
private fun OtherCourses(
    container: AppContainer,
    classification: String,
    label: String,
    query: String,
    onOpenCourse: (Long, String, String?) -> Unit,
) {
    val (state, refresh) = rememberLoadable("courses:$classification") {
        container.moodleRepository.enrolledCourses(classification)
    }
    Column {
        when (val s = state) {
            is UiState.Loading -> Column(Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm)) {
                SkeletonBlock(width = null, height = 16.dp)
            }
            is UiState.Error -> Column(Modifier.padding(horizontal = ScreenGutter)) {
                MessageBanner("${label}を読み込めませんでした (${s.message})", isError = true, onDismiss = null)
                TertiaryButton("再試行", onClick = refresh)
            }
            is UiState.Success -> {
                val courses = s.data.filter { it.matches(query) }
                if (courses.isEmpty()) {
                    MetaText("${label}はありません", modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Spacing.sm))
                }
                courses.forEachIndexed { index, course ->
                    CourseRow(course, Modifier.groupedItem(GroupPosition.of(index, courses.size), dividerInset = CourseTextInset)) {
                        onOpenCourse(course.id, course.displayName.ifBlank { course.fullname }, course.courseCode)
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseSearchField(query: String, onChange: (String) -> Unit) {
    val c = AppTheme.colors
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("コース名・授業コードで検索", color = c.inkTertiary) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = c.inkTertiary) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "検索語を消す", tint = c.inkSecondary) } }
        } else {
            null
        },
        shape = RoundedCornerShape(Radius.sm),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceSunken,
            unfocusedContainerColor = c.surfaceSunken,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            cursorColor = c.accent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = Spacing.sm),
    )
}

@Composable
internal fun CollapsibleHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = ScreenGutter + Spacing.xs, end = ScreenGutter, top = Spacing.xl, bottom = Spacing.sm)
            .semantics { stateDescription = if (expanded) "展開" else "折りたたみ" },
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.inkSecondary, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = AppTheme.colors.inkSecondary,
        )
    }
}

/** コース行の本文の開始位置 (授業カラーのスクエアの分)。区切り線もここから引く */
private val CourseTextInset = ScreenGutter + 36.dp + Spacing.md

/** サービスへのリンク行の本文の開始位置 */
private val LinkTextInset = ScreenGutter + 36.dp + Spacing.md

/** コースの1行: 授業カラーの角丸スクエア + 名前 (2行まで) + 授業コード + 進む印 */
@Composable
private fun CourseRow(course: Course, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val name = course.displayName.ifBlank { course.fullname }
    val code = course.courseCode ?: course.shortname.ifBlank { null }
    val cc = courseColor(CourseColors.key(course))
    ListRow(
        onClick = onClick,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, code).joinToString("、") },
    ) {
        CourseSquare(cc, name, size = 36.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (code != null) MetaText(code)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = AppTheme.colors.inkTertiary)
    }
}

@Composable
internal fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListRow(onClick = onClick, verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconTile(icon, tone = Tone.Accent, size = 36.dp, iconSize = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.ink)
            subtitle?.let { MetaText(it) }
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = AppTheme.colors.inkTertiary)
    }
}
