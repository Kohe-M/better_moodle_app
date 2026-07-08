package dev.rits.bettermoodle

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.rits.bettermoodle.auth.SsoLogin
import dev.rits.bettermoodle.data.ForumTarget
import dev.rits.bettermoodle.data.PageTarget
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.ui.AssignmentScreen
import dev.rits.bettermoodle.ui.CourseScreen
import dev.rits.bettermoodle.ui.CourseListScreen
import dev.rits.bettermoodle.ui.DeadlinesScreen
import dev.rits.bettermoodle.ui.FilePreviewScreen
import dev.rits.bettermoodle.ui.ForumScreen
import dev.rits.bettermoodle.ui.LoginScreen
import dev.rits.bettermoodle.ui.MoodleWebScreen
import dev.rits.bettermoodle.ui.NotificationsScreen
import dev.rits.bettermoodle.ui.PageScreen
import dev.rits.bettermoodle.ui.PdfPreviewScreen
import dev.rits.bettermoodle.ui.PortalScreen
import dev.rits.bettermoodle.ui.QuizScreen
import dev.rits.bettermoodle.ui.SyllabusScreen
import dev.rits.bettermoodle.ui.TimetableScreen
import dev.rits.bettermoodle.ui.openInCustomTab
import dev.rits.bettermoodle.ui.theme.BetterMoodleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as App).container
        setContent {
            BetterMoodleTheme {
                Root(container)
            }
        }
    }
}

@Composable
private fun Root(container: AppContainer) {
    val token by container.sessionStore.tokenFlow.collectAsState(initial = "INIT")
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    when (token) {
        "INIT" -> return // DataStore読み込み待ち
        null -> {
            LoginScreen(onToken = { tokens: SsoLogin.Tokens ->
                scope.launch {
                    container.sessionStore.saveLogin(tokens.wsToken, tokens.privateToken)
                }
            })
            return
        }
    }

    val rootNav = rememberNavController()
    fun openUrl(url: String, title: String) {
        when {
            UrlPolicy.isAllowedMoodleWebViewUrl(url) ->
                rootNav.navigate("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
            UrlPolicy.canOpenExternally(url) -> openInCustomTab(context, url)
        }
    }

    NavHost(navController = rootNav, startDestination = "main") {
        composable("main") { MainTabs(container, rootNav) }

        composable("courses") {
            CourseListScreen(
                container = container,
                onBack = { rootNav.popBackStack() },
                onOpenCourse = { id, name, code ->
                    rootNav.navigate(courseRoute(id, name, code))
                },
            )
        }

        composable("course/{courseId}?name={name}&code={code}") { entry ->
            val id = entry.arguments?.getString("courseId")?.toLongOrNull() ?: 0L
            val name = entry.savedName()
            val code = entry.savedCode()
            CourseScreen(
                container = container,
                courseId = id,
                courseName = name,
                courseCode = code,
                onBack = { rootNav.popBackStack() },
                onOpenPdf = { url, title ->
                    rootNav.navigate("pdf?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                },
                onOpenAssignment = { moduleId, assignId, title ->
                    rootNav.navigate(
                        "assignment/$id/$moduleId/$assignId?title=${Uri.encode(title)}",
                    )
                },
                onOpenForum = { target, title ->
                    rootNav.navigate(
                        forumRoute(target, title),
                    )
                },
                onOpenQuiz = { target, title ->
                    rootNav.navigate(
                        quizRoute(target, title),
                    )
                },
                onOpenPage = { target, title ->
                    rootNav.navigate(
                        pageRoute(target, title),
                    )
                },
                onOpenFilePreview = { url, title, kind ->
                    rootNav.navigate(
                        "filePreview?url=${Uri.encode(url)}" +
                            "&title=${Uri.encode(title)}" +
                            "&kind=${Uri.encode(kind.name)}",
                    )
                },
                onOpenMoodleWeb = { url, title ->
                    rootNav.navigate("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                },
                onOpenUrl = ::openUrl,
            )
        }

        composable("pdf?url={url}&title={title}") { entry ->
            val url = entry.arguments?.getString("url").orEmpty()
            val title = entry.arguments?.getString("title") ?: "PDF"
            PdfPreviewScreen(
                container = container,
                fileUrl = url,
                title = title,
                onBack = { rootNav.popBackStack() },
            )
        }

        composable("assignment/{courseId}/{moduleId}/{assignId}?title={title}") { entry ->
            AssignmentScreen(
                container = container,
                courseId = entry.arguments?.getString("courseId")?.toLongOrNull() ?: 0L,
                moduleId = entry.arguments?.getString("moduleId")?.toLongOrNull() ?: 0L,
                assignId = entry.arguments?.getString("assignId")?.toLongOrNull() ?: 0L,
                title = entry.arguments?.getString("title") ?: "課題",
                onBack = { rootNav.popBackStack() },
                onOpenUrl = ::openUrl,
                onOpenFilePreview = { file ->
                    val url = file.sourceUrl ?: return@AssignmentScreen
                    when (file.previewKind) {
                        PreviewKind.Pdf -> rootNav.navigate(
                            "pdf?url=${Uri.encode(url)}&title=${Uri.encode(file.filename)}",
                        )
                        PreviewKind.Image,
                        PreviewKind.Text,
                        -> rootNav.navigate(
                            "filePreview?url=${Uri.encode(url)}" +
                                "&title=${Uri.encode(file.filename)}" +
                                "&kind=${Uri.encode(file.previewKind.name)}",
                        )
                        PreviewKind.Html,
                        PreviewKind.Unsupported -> Unit
                    }
                },
            )
        }

        composable("filePreview?url={url}&title={title}&kind={kind}") { entry ->
            val kind = runCatching {
                PreviewKind.valueOf(entry.arguments?.getString("kind").orEmpty())
            }.getOrDefault(PreviewKind.Unsupported)
            FilePreviewScreen(
                container = container,
                fileUrl = entry.arguments?.getString("url").orEmpty(),
                title = entry.arguments?.getString("title") ?: "プレビュー",
                kind = kind,
                onBack = { rootNav.popBackStack() },
            )
        }

        composable("forum?courseId={courseId}&courseModuleId={courseModuleId}&forumInstanceId={forumInstanceId}&contextId={contextId}&modName={modName}&title={title}&url={url}") { entry ->
            val target = ForumTarget(
                courseId = entry.arguments?.getString("courseId")?.toLongOrNull() ?: 0L,
                courseModuleId = entry.arguments?.getString("courseModuleId")?.toLongOrNull() ?: 0L,
                forumInstanceId = entry.arguments?.getString("forumInstanceId")?.toLongOrNull() ?: 0L,
                contextId = entry.arguments?.getString("contextId")?.toLongOrNull()?.takeIf { it > 0L },
                modName = entry.arguments?.getString("modName").orEmpty(),
                url = entry.arguments?.getString("url")?.takeIf { it.isNotBlank() },
            )
            ForumScreen(
                container = container,
                target = target,
                title = entry.arguments?.getString("title") ?: "フォーラム",
                onBack = { rootNav.popBackStack() },
                onOpenUrl = ::openUrl,
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigate("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode("フォーラム")}")
                    }
                },
            )
        }

        composable("quiz?courseId={courseId}&courseModuleId={courseModuleId}&quizInstanceId={quizInstanceId}&contextId={contextId}&modName={modName}&title={title}&url={url}") { entry ->
            val target = QuizTarget(
                courseId = entry.arguments?.getString("courseId")?.toLongOrNull() ?: 0L,
                courseModuleId = entry.arguments?.getString("courseModuleId")?.toLongOrNull() ?: 0L,
                quizInstanceId = entry.arguments?.getString("quizInstanceId")?.toLongOrNull() ?: 0L,
                contextId = entry.arguments?.getString("contextId")?.toLongOrNull()?.takeIf { it > 0L },
                modName = entry.arguments?.getString("modName").orEmpty(),
                url = entry.arguments?.getString("url")?.takeIf { it.isNotBlank() },
            )
            QuizScreen(
                container = container,
                target = target,
                title = entry.arguments?.getString("title") ?: "小テスト",
                onBack = { rootNav.popBackStack() },
                onOpenUrl = ::openUrl,
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigate("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode("小テスト")}")
                    }
                },
            )
        }

        composable("page?courseId={courseId}&courseModuleId={courseModuleId}&contextId={contextId}&modName={modName}&title={title}&url={url}") { entry ->
            val target = PageTarget(
                courseId = entry.arguments?.getString("courseId")?.toLongOrNull() ?: 0L,
                courseModuleId = entry.arguments?.getString("courseModuleId")?.toLongOrNull() ?: 0L,
                contextId = entry.arguments?.getString("contextId")?.toLongOrNull()?.takeIf { it > 0L },
                modName = entry.arguments?.getString("modName").orEmpty(),
                url = entry.arguments?.getString("url")?.takeIf { it.isNotBlank() },
            )
            PageScreen(
                container = container,
                target = target,
                title = entry.arguments?.getString("title") ?: "ページ",
                onBack = { rootNav.popBackStack() },
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigate("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode("ページ")}")
                    }
                },
                onOpenPdf = { url, title ->
                    rootNav.navigate("pdf?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                },
                onOpenFilePreview = { url, title, kind ->
                    rootNav.navigate(
                        "filePreview?url=${Uri.encode(url)}" +
                            "&title=${Uri.encode(title)}" +
                            "&kind=${Uri.encode(kind.name)}",
                    )
                },
                onOpenUrl = ::openUrl,
            )
        }

        composable("moodleWeb?url={url}&title={title}") { entry ->
            MoodleWebScreen(
                container = container,
                url = entry.arguments?.getString("url").orEmpty(),
                title = entry.arguments?.getString("title") ?: "Moodle",
                onBack = { rootNav.popBackStack() },
            )
        }
    }
}

// コース名・コードはナビゲーション引数で受け渡す (クエリ文字列)
private fun androidx.navigation.NavBackStackEntry.savedName(): String =
    arguments?.getString("name").orEmpty()

private fun androidx.navigation.NavBackStackEntry.savedCode(): String? =
    arguments?.getString("code")?.takeIf { it.isNotBlank() }

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private fun courseRoute(id: Long, name: String, code: String?): String =
    "course/$id?name=${Uri.encode(name)}&code=${Uri.encode(code ?: "")}"

private fun forumRoute(target: ForumTarget, title: String): String =
    "forum?courseId=${target.courseId}" +
        "&courseModuleId=${target.courseModuleId}" +
        "&forumInstanceId=${target.forumInstanceId}" +
        "&contextId=${target.contextId ?: 0L}" +
        "&modName=${Uri.encode(target.modName)}" +
        "&title=${Uri.encode(title)}" +
        "&url=${Uri.encode(target.url.orEmpty())}"

private fun quizRoute(target: QuizTarget, title: String): String =
    "quiz?courseId=${target.courseId}" +
        "&courseModuleId=${target.courseModuleId}" +
        "&quizInstanceId=${target.quizInstanceId}" +
        "&contextId=${target.contextId ?: 0L}" +
        "&modName=${Uri.encode(target.modName)}" +
        "&title=${Uri.encode(title)}" +
        "&url=${Uri.encode(target.url.orEmpty())}"

private fun pageRoute(target: PageTarget, title: String): String =
    "page?courseId=${target.courseId}" +
        "&courseModuleId=${target.courseModuleId}" +
        "&contextId=${target.contextId ?: 0L}" +
        "&modName=${Uri.encode(target.modName)}" +
        "&title=${Uri.encode(title)}" +
        "&url=${Uri.encode(target.url.orEmpty())}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(container: AppContainer, rootNav: NavHostController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showLogout by remember { mutableStateOf(false) }

    val navController = rememberNavController()
    val items = listOf(
        NavItem("timetable", "時間割", Icons.Filled.CalendarViewMonth),
        NavItem("deadlines", "締切", Icons.Filled.Alarm),
        NavItem("notifications", "通知", Icons.Filled.Notifications),
        NavItem("syllabus", "シラバス", Icons.AutoMirrored.Filled.MenuBook),
        NavItem("portal", "ポータル", Icons.Filled.Public),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    fun openCourse(id: Long, name: String, code: String?) {
        rootNav.navigate(courseRoute(id, name, code))
    }

    fun openUrl(url: String, title: String) {
        when {
            UrlPolicy.isAllowedMoodleWebViewUrl(url) ->
                rootNav.navigate("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
            UrlPolicy.canOpenExternally(url) -> openInCustomTab(context, url)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(items.firstOrNull { it.route == currentRoute }?.label ?: "Moodle+R") },
                actions = {
                    if (currentRoute == "timetable") {
                        IconButton(onClick = { rootNav.navigate("courses") }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "すべてのコース")
                        }
                    }
                    IconButton(onClick = { showLogout = true }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "ログアウト")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "timetable",
            modifier = Modifier.padding(padding),
        ) {
            composable("timetable") { TimetableScreen(container, onOpenCourse = ::openCourse) }
            composable("deadlines") {
                DeadlinesScreen(
                    container = container,
                    onOpenUrl = ::openUrl,
                    onOpenAssignment = { courseId, moduleId, assignId, title ->
                        rootNav.navigate(
                            "assignment/$courseId/$moduleId/$assignId?title=${Uri.encode(title)}",
                        )
                    },
                    onOpenQuiz = { target, title ->
                        rootNav.navigate(quizRoute(target, title))
                    },
                )
            }
            composable("notifications") { NotificationsScreen(container, onOpenUrl = ::openUrl) }
            composable("syllabus") { SyllabusScreen(container) }
            composable("portal") {
                PortalScreen(onClearAllWebSessions = {
                    container.clearAllWebSessions()
                })
            }
        }
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("ログアウト") },
            text = { Text("保存されたトークンとキャッシュを削除します。よろしいですか?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    scope.launch { container.logout() }
                }) { Text("ログアウト") }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("キャンセル") }
            },
        )
    }
}
