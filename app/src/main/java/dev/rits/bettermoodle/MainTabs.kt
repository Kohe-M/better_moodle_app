package dev.rits.bettermoodle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.ui.AccentDot
import dev.rits.bettermoodle.ui.CoursesScreen
import dev.rits.bettermoodle.ui.DeadlinesScreen
import dev.rits.bettermoodle.ui.TimetableScreen
import dev.rits.bettermoodle.ui.TodayScreen
import dev.rits.bettermoodle.ui.UiState
import dev.rits.bettermoodle.ui.formatTitleDate
import dev.rits.bettermoodle.ui.openDeadlineEvent
import dev.rits.bettermoodle.ui.rememberLoadable
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * トップレベルのタブ (DESIGN.md §2)。今日 / 時間割 / 課題 / コース の4つ。
 * タブはトップレベル遷移専用で、アクションは入れない。通知は今日タブの右上、設定はコースタブの右上から開く。
 */
@Composable
internal fun MainTabs(
    container: AppContainer,
    rootNav: NavHostController,
    onOpenUrl: (url: String, title: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val items = listOf(
        NavItem("today", "今日", Icons.Outlined.WbSunny, Icons.Filled.WbSunny),
        NavItem("timetable", "時間割", Icons.Outlined.CalendarViewWeek, Icons.Filled.CalendarViewWeek),
        NavItem("deadlines", "課題", Icons.AutoMirrored.Outlined.Assignment, Icons.AutoMirrored.Filled.Assignment),
        NavItem("courses", "コース", Icons.Outlined.School, Icons.Filled.School),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "today"
    val todayList = rememberLazyListState()
    val badge by container.deadlineBadge.collectAsState()
    // 通知の未読 (今日タブ右上のドット)。通知画面とキャッシュを共有する
    val (notifications, _) = rememberLoadable("notifications") { container.moodleRepository.notifications() }
    // 取得後にアプリ内で既読にした分は差し引く (次の再取得まで点が残らないように)
    val readSync by container.notificationReadSync.state.collectAsState()
    val unread = (notifications as? UiState.Success)?.data?.let { page ->
        val readLocally = page.notifications.count { !it.read && it.id in readSync.readIds }
        (page.unreadCount - readLocally).coerceAtLeast(0)
    } ?: 0

    fun openCourse(id: Long, name: String, code: String?) {
        rootNav.navigateFromUser(Routes.course(id, name, code))
    }

    fun openDeadline(event: ActionEvent) = openDeadlineEvent(
        event,
        onOpenUrl = onOpenUrl,
        onOpenAssignment = { courseId, moduleId, assignId, title ->
            rootNav.navigateFromUser(Routes.assignment(courseId, moduleId, assignId, title))
        },
        onOpenQuiz = { target, title -> rootNav.navigateFromUser(Routes.quiz(target, title)) },
    )

    fun selectTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            when (currentRoute) {
                "today" -> TabHeader(
                    title = "今日",
                    subtitle = formatTitleDate(LocalDate.now()),
                    // タイトルをタップで今日の先頭へ戻る (Fantastical)
                    onTitleClick = { scope.launch { todayList.animateScrollToItem(0) } },
                ) {
                    IconButton(onClick = { rootNav.navigateFromUser(Routes.NOTIFICATIONS) }) {
                        BadgedBox(badge = { if (unread > 0) AccentDot() }) {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = if (unread > 0) "通知 (未読${unread}件)" else "通知",
                            )
                        }
                    }
                }
                "timetable" -> TabHeader(title = "時間割")
                "deadlines" -> TabHeader(title = "課題")
                else -> TabHeader(title = "コース") {
                    IconButton(onClick = { rootNav.navigateFromUser(Routes.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "設定")
                    }
                }
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 0.5.dp, color = AppTheme.colors.hairline)
                NavigationBar(containerColor = AppTheme.colors.surface, tonalElevation = 0.dp) {
                    items.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected && item.route == "today") {
                                    scope.launch { todayList.animateScrollToItem(0) }
                                } else {
                                    selectTab(item.route)
                                }
                            },
                            icon = {
                                val icon = if (selected) item.selectedIcon else item.icon
                                if (item.route == "deadlines" && badge > 0) {
                                    BadgedBox(badge = {
                                        Badge(containerColor = AppTheme.colors.warning, contentColor = AppTheme.colors.surface) {
                                            Text("$badge")
                                        }
                                    }) { Icon(icon, contentDescription = "24時間以内の締切 ${badge}件") }
                                } else {
                                    Icon(icon, contentDescription = null)
                                }
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelLarge) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppTheme.colors.accent,
                                selectedTextColor = AppTheme.colors.accent,
                                indicatorColor = AppTheme.colors.accentSoft,
                                unselectedIconColor = AppTheme.colors.inkSecondary,
                                unselectedTextColor = AppTheme.colors.inkSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "today",
            modifier = Modifier.padding(padding),
        ) {
            composable("today") {
                TodayScreen(
                    container = container,
                    listState = todayList,
                    onOpenCourse = ::openCourse,
                    onOpenDeadline = ::openDeadline,
                    onSeeAllDeadlines = { selectTab("deadlines") },
                )
            }
            composable("timetable") {
                TimetableScreen(container, onOpenCourse = ::openCourse, onOpenDeadline = ::openDeadline)
            }
            composable("deadlines") {
                DeadlinesScreen(
                    container = container,
                    onOpenUrl = onOpenUrl,
                    onOpenAssignment = { courseId, moduleId, assignId, title ->
                        rootNav.navigateFromUser(Routes.assignment(courseId, moduleId, assignId, title))
                    },
                    onOpenQuiz = { target, title ->
                        rootNav.navigateFromUser(Routes.quiz(target, title))
                    },
                )
            }
            composable("courses") {
                CoursesScreen(
                    container = container,
                    onOpenCourse = ::openCourse,
                    onOpenSyllabus = { rootNav.navigateFromUser(Routes.SYLLABUS) },
                    onOpenPortal = { rootNav.navigateFromUser(Routes.PORTAL) },
                )
            }
        }
    }
}

/** タブの大タイトル (display 28/700)。今日タブは日付を添える */
@Composable
private fun TabHeader(
    title: String,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.canvas)
            .statusBarsPadding()
            .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .weight(1f)
                .then(if (onTitleClick != null) Modifier.clickable(onClickLabel = "先頭へ戻る", onClick = onTitleClick) else Modifier)
                .semantics(mergeDescendants = true) { heading() },
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = AppTheme.colors.ink)
            if (subtitle != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTheme.colors.inkSecondary,
                    modifier = Modifier.padding(bottom = Spacing.xxs),
                )
            }
        }
        actions()
    }
}
