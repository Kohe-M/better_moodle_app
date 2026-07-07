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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Logout
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.rits.bettermoodle.auth.SsoLogin
import dev.rits.bettermoodle.ui.CourseScreen
import dev.rits.bettermoodle.ui.DeadlinesScreen
import dev.rits.bettermoodle.ui.LoginScreen
import dev.rits.bettermoodle.ui.NotificationsScreen
import dev.rits.bettermoodle.ui.PdfPreviewScreen
import dev.rits.bettermoodle.ui.PortalScreen
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
    NavHost(navController = rootNav, startDestination = "main") {
        composable("main") { MainTabs(container, rootNav) }

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
                onOpenExternally = {
                    val authed = container.moodleRepository.authedFileUrl(url) ?: url
                    openInCustomTab(context, authed)
                },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(container: AppContainer, rootNav: NavHostController) {
    val scope = rememberCoroutineScope()
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
        rootNav.navigate("course/$id?name=${Uri.encode(name)}&code=${Uri.encode(code ?: "")}")
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(items.firstOrNull { it.route == currentRoute }?.label ?: "Moodle+R") },
                actions = {
                    IconButton(onClick = { showLogout = true }) {
                        Icon(Icons.Filled.Logout, contentDescription = "ログアウト")
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
            composable("deadlines") { DeadlinesScreen(container) }
            composable("notifications") { NotificationsScreen(container) }
            composable("syllabus") { SyllabusScreen(container) }
            composable("portal") { PortalScreen() }
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
                    scope.launch { container.sessionStore.logout() }
                }) { Text("ログアウト") }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("キャンセル") }
            },
        )
    }
}
