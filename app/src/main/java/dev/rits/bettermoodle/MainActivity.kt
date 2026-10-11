package dev.rits.bettermoodle

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.rits.bettermoodle.auth.SsoLogin
import dev.rits.bettermoodle.data.AuthState
import dev.rits.bettermoodle.data.ForumTarget
import dev.rits.bettermoodle.data.MoodleUrlTarget
import dev.rits.bettermoodle.data.PageTarget
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.data.parseMoodleUrlTarget
import dev.rits.bettermoodle.data.previewKindFor
import dev.rits.bettermoodle.ui.AssignmentScreen
import dev.rits.bettermoodle.ui.CourseScreen
import dev.rits.bettermoodle.ui.DeadlinesScreen
import dev.rits.bettermoodle.ui.FilePreviewScreen
import dev.rits.bettermoodle.ui.ForumDiscussionScreen
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.rits.bettermoodle.data.ActionEvent
import dev.rits.bettermoodle.ui.AccentDot
import dev.rits.bettermoodle.ui.CoursesScreen
import dev.rits.bettermoodle.ui.LocalCourseColors
import dev.rits.bettermoodle.ui.SettingsScreen
import dev.rits.bettermoodle.ui.SubScreenScaffold
import dev.rits.bettermoodle.ui.TodayScreen
import dev.rits.bettermoodle.ui.UiState
import dev.rits.bettermoodle.ui.formatTitleDate
import dev.rits.bettermoodle.ui.openDeadlineEvent
import dev.rits.bettermoodle.ui.rememberLoadable
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.ui.theme.Spacing
import java.time.LocalDate
import dev.rits.bettermoodle.work.DeadlineWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // システムバーのアイコン色を端末のダークモードに合わせる (テーマの windowLightStatusBar 固定だと
        // ダークモードで canvas の上に暗いアイコンが並んで見えない)。API 35 未満も API 35+ と同じ edge-to-edge にそろえる
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        // cold start・プロセス再生成・既存Activityへの onNewIntent のどれでも同じ経路で宛先を保持し、
        // ログイン後に Root が消費する。savedInstanceState がある = プロセス再生成による再配達なので、
        // 消費済みキー (DataStore) と照合して二重に開かない。新規起動は利用者の明示的な操作として扱う
        consumeDeepLink(intent, explicit = savedInstanceState == null)
        setContent {
            BetterMoodleTheme {
                // 画面ごとの Scaffold の外側 (ログイン画面など) も canvas にする
                Box(Modifier.fillMaxSize().background(AppTheme.colors.canvas)) {
                    Root(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 既存Activityへの配達は利用者がいまタップした通知
        consumeDeepLink(intent, explicit = true)
    }

    private fun consumeDeepLink(intent: Intent?, explicit: Boolean) {
        val link = pendingDeepLinkFrom(intent, explicit) ?: return
        (application as App).container.pendingDeepLink.value = link
    }

    companion object {
        /** 通知Intentの宛先を検証して取り出す。外部から任意URLを流し込めないようMoodleホストに限定する。 */
        fun pendingDeepLinkFrom(intent: Intent?, explicit: Boolean): PendingDeepLink? {
            val raw = intent?.getStringExtra(DeadlineWorker.EXTRA_OPEN_URL)?.takeIf { it.isNotBlank() } ?: return null
            val url = UrlPolicy.normalizeMoodleUrl(raw)
            if (!UrlPolicy.isAllowedMoodleWebViewUrl(url)) return null
            val title = intent.getStringExtra(DeadlineWorker.EXTRA_OPEN_TITLE).orEmpty().ifBlank { "課題" }
            val key = intent.getStringExtra(DeadlineWorker.EXTRA_OPEN_KEY).orEmpty().ifBlank { url }
            return PendingDeepLink(url, title, key, explicit)
        }
    }
}

/**
 * ユーザー操作起点のナビゲーション。遷移アニメーション中 (現在のエントリが
 * RESUMED でない間) はタップが出ていく画面に落ちるため、その間の navigate を無視する。
 * 二重タップによる同一画面の多重プッシュもこれで防ぐ。
 */
private fun NavHostController.navigateFromUser(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
        navigate(route)
    }
}

@Composable
private fun Root(container: AppContainer) {
    // tokenFlow ではなく authState を見る: MoodleClient のトークンキャッシュ更新後に発行されるため、
    // 認証済み画面の最初のリクエストが notoken で失敗しない
    val auth by container.authState.collectAsState()
    val scope = rememberCoroutineScope()

    when (val a = auth) {
        AuthState.Unknown -> return // DataStore読み込み待ち
        AuthState.LoggedOut -> {
            // 永続化と状態更新はセッション管理側 (アプリ scope) が排他して行う
            // ログイン画面 (SSO の WebView を含む) は Scaffold を持たないので、システムバーとキーボードを避ける
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                LoginScreen(onToken = { tokens: SsoLogin.Tokens ->
                    container.authSession.login(tokens.wsToken, tokens.privateToken).await()
                })
            }
            return
        }
        // 世代ごとにNavHostを作り直す (別アカウントで再ログインしたときに前の画面スタックを残さない)
        is AuthState.LoggedIn -> key(a.generation) { LoggedInRoot(container) }
    }
}

@Composable
private fun LoggedInRoot(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val rootNav = rememberNavController()

    // 下書きの所有者判定に使う Moodle ユーザーIDを、通信できるうちに取得・保存しておく (G02)
    LaunchedEffect(Unit) { container.currentAccount() }

    // 締切通知の権限はログイン後に1回だけ求める (起動のたび・ログイン前には出さない。G15)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || container.sessionStore.notificationPermissionAsked()) return@LaunchedEffect
        container.sessionStore.markNotificationPermissionAsked()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    /**
     * URLをネイティブ画面/WebView/外部ブラウザへ振り分ける。
     * [fromUser] が false (通知タップ等) のときは遷移アニメーション中のガードを通さない
     * (cold start 直後はまだ RESUMED でないため、ガードすると宛先が捨てられる)。
     */
    suspend fun openUrlNow(url: String, title: String, fromUser: Boolean) {
        val normalized = UrlPolicy.normalizeMoodleUrl(url)
        val nativeRoute = pluginFileRoute(normalized, title) ?: resolveNativeRoute(container, normalized, title)
        val go: (String) -> Unit = { route -> if (fromUser) rootNav.navigateFromUser(route) else rootNav.navigate(route) }
        when {
            nativeRoute != null -> go(nativeRoute)
            UrlPolicy.isAllowedMoodleWebViewUrl(normalized) ->
                go("moodleWeb?url=${Uri.encode(normalized)}&title=${Uri.encode(title)}")
            UrlPolicy.canOpenExternally(normalized) -> openInCustomTab(context, normalized)
            else -> showUnhandledUrl(context, normalized)
        }
    }

    /** 画面からのURLを開く (全画面共通の入口。G10) */
    fun openUrl(url: String, title: String) {
        scope.launch { openUrlNow(url, title, fromUser = true) }
    }

    // 通知タップなどの宛先はログイン後に消費する。
    // - Effect のキー (pendingLink) は処理が終わるまで変えない (途中で null にすると自分がキャンセルされ、
    //   宛先も失われる)。DataStore 処理中に中断されても宛先は残り、再コンポーズ時にやり直せる
    // - 遷移要求 → 消費済み記録 → 宛先クリア の順。記録前に中断されても「開いていないのに消費済み」にならない
    // - OS の再配達 (explicit=false) だけを消費済みキーで抑止し、明示的な再タップは常に開く
    val pendingLink by container.pendingDeepLink.collectAsState()
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        val redelivered = !link.explicit && container.sessionStore.consumedDeepLinkKey() == link.key
        if (!redelivered) {
            // 遷移し終えてから消費済みにする (通信中に中断されたら、再配達で開き直せるように。G10)
            openUrlNow(link.url, link.title, fromUser = false)
            container.sessionStore.markDeepLinkConsumed(link.key)
        }
        container.pendingDeepLink.compareAndSet(link, null)
    }

    val courseColors by container.courseColorStore.state.collectAsState()
    CompositionLocalProvider(LocalCourseColors provides courseColors) {
    NavHost(navController = rootNav, startDestination = "main") {
        composable("main") { MainTabs(container, rootNav, onOpenUrl = ::openUrl) }

        composable("notifications") {
            SubScreenScaffold(title = "通知", onBack = { rootNav.popBackStack() }) {
                NotificationsScreen(container, onOpenUrl = ::openUrl)
            }
        }

        composable("syllabus") {
            SubScreenScaffold(title = "シラバス", onBack = { rootNav.popBackStack() }) {
                SyllabusScreen(container)
            }
        }

        composable("portal") {
            SubScreenScaffold(title = "スチューデントポータル", onBack = { rootNav.popBackStack() }) {
                PortalScreen()
            }
        }

        composable("settings") {
            SettingsScreen(container, onBack = { rootNav.popBackStack() })
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
                    rootNav.navigateFromUser("pdf?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                },
                onOpenAssignment = { moduleId, assignId, title ->
                    rootNav.navigateFromUser(
                        "assignment/$id/$moduleId/$assignId?title=${Uri.encode(title)}",
                    )
                },
                onOpenForum = { target, title ->
                    rootNav.navigateFromUser(
                        forumRoute(target, title),
                    )
                },
                onOpenQuiz = { target, title ->
                    rootNav.navigateFromUser(
                        quizRoute(target, title),
                    )
                },
                onOpenPage = { target, title ->
                    rootNav.navigateFromUser(
                        pageRoute(target, title),
                    )
                },
                onOpenFilePreview = { url, title, kind ->
                    rootNav.navigateFromUser(
                        "filePreview?url=${Uri.encode(url)}" +
                            "&title=${Uri.encode(title)}" +
                            "&kind=${Uri.encode(kind.name)}",
                    )
                },
                onOpenMoodleWeb = { url, title ->
                    rootNav.navigateFromUser("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                },
                onOpenUrl = { url, title -> openUrl(url, title) },
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
                onOpenUrl = { url, title -> openUrl(url, title) },
                onOpenFilePreview = { file ->
                    val url = file.sourceUrl ?: return@AssignmentScreen
                    when (file.previewKind) {
                        PreviewKind.Pdf -> rootNav.navigateFromUser(
                            "pdf?url=${Uri.encode(url)}&title=${Uri.encode(file.filename)}",
                        )
                        PreviewKind.Image,
                        PreviewKind.Text,
                        -> rootNav.navigateFromUser(
                            "filePreview?url=${Uri.encode(url)}" +
                                "&title=${Uri.encode(file.filename)}" +
                                "&kind=${Uri.encode(file.previewKind.name)}",
                        )
                        PreviewKind.Html,
                        PreviewKind.Unsupported -> Unit
                    }
                },
                onOpenMoodleWeb = { url, webTitle ->
                    rootNav.navigateFromUser("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode(webTitle)}")
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
                onOpenUrl = { url, title -> openUrl(url, title) },
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigateFromUser("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode("フォーラム")}")
                    }
                },
            )
        }

        composable("forumDiscussion?discussionId={discussionId}&title={title}&url={url}") { entry ->
            ForumDiscussionScreen(
                container = container,
                discussionId = entry.arguments?.getString("discussionId")?.toLongOrNull() ?: 0L,
                title = entry.arguments?.getString("title") ?: "フォーラム",
                fallbackUrl = entry.arguments?.getString("url").orEmpty(),
                onBack = { rootNav.popBackStack() },
                onOpenUrl = { url, title -> openUrl(url, title) },
                onFallbackWeb = { url, fallbackTitle ->
                    if (url.isNotBlank()) {
                        rootNav.navigateFromUser(
                            "moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode(fallbackTitle)}",
                        )
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
                onOpenUrl = { url, title -> openUrl(url, title) },
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigateFromUser("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode("小テスト")}")
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
                        rootNav.navigateFromUser("moodleWeb?url=${Uri.encode(url)}&title=${Uri.encode("ページ")}")
                    }
                },
                onOpenPdf = { url, title ->
                    rootNav.navigateFromUser("pdf?url=${Uri.encode(url)}&title=${Uri.encode(title)}")
                },
                onOpenFilePreview = { url, title, kind ->
                    rootNav.navigateFromUser(
                        "filePreview?url=${Uri.encode(url)}" +
                            "&title=${Uri.encode(title)}" +
                            "&kind=${Uri.encode(kind.name)}",
                    )
                },
                onOpenUrl = { url, title -> openUrl(url, title) },
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
    val selectedIcon: ImageVector,
)

private fun courseRoute(id: Long, name: String, code: String?): String =
    "course/$id?name=${Uri.encode(name)}&code=${Uri.encode(code ?: "")}"

/**
 * Moodle URLをネイティブ画面のrouteに解決する。解決できなければnull
 * (呼び出し側で従来のWebView/Custom Tabにフォールバックする)。
 */
private suspend fun resolveNativeRoute(
    container: AppContainer,
    url: String,
    title: String,
): String? {
    return when (val target = parseMoodleUrlTarget(url)) {
        is MoodleUrlTarget.Course -> courseRoute(target.courseId, title.ifBlank { "コース" }, null)
        is MoodleUrlTarget.ForumDiscussion -> forumDiscussionRoute(target.discussionId, title, url)
        is MoodleUrlTarget.Module -> {
            val cm = runCatching {
                container.moodleRepository.courseModule(target.cmid)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            } ?: return null

            val routeTitle = cm.name.ifBlank { title }
            when (target.modName) {
                "assign" -> cm.instance.takeIf { it > 0L }?.let { assignId ->
                    "assignment/${cm.course}/${cm.id}/$assignId?title=${Uri.encode(routeTitle)}"
                }
                "quiz" -> cm.instance.takeIf { it > 0L }?.let { quizId ->
                    quizRoute(
                        QuizTarget(
                            courseId = cm.course,
                            courseModuleId = cm.id,
                            quizInstanceId = quizId,
                            contextId = null,
                            modName = "quiz",
                            url = url,
                        ),
                        routeTitle,
                    )
                }
                "forum" -> cm.instance.takeIf { it > 0L }?.let { forumId ->
                    forumRoute(
                        ForumTarget(
                            courseId = cm.course,
                            courseModuleId = cm.id,
                            forumInstanceId = forumId,
                            contextId = null,
                            modName = "forum",
                            url = url,
                        ),
                        routeTitle,
                    )
                }
                "page" -> pageRoute(
                    PageTarget(
                        courseId = cm.course,
                        courseModuleId = cm.id,
                        contextId = null,
                        modName = "page",
                        url = url,
                    ),
                    routeTitle,
                )
                else -> {
                    val course = runCatching {
                        container.moodleRepository.enrolledCourses()
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        emptyList()
                    }.firstOrNull { it.id == cm.course }
                    courseRoute(
                        cm.course,
                        course?.displayName?.ifBlank { "コース" } ?: "コース",
                        course?.courseCode,
                    )
                }
            }
        }
        null -> null
    }
}

/**
 * pluginfile の画像・PDF・テキストはWebViewではなくアプリ内プレビューで開く
 * (課題説明やフォーラム本文の画像リンクの逃げ道)。対象外なら null。
 */
private fun pluginFileRoute(url: String, title: String): String? {
    if (!UrlPolicy.isSafeMoodlePluginFileSourceUrl(url)) return null
    val filename = runCatching { URI(url).path }.getOrNull()?.substringAfterLast('/')?.let { Uri.decode(it) }.orEmpty()
    if (filename.isBlank()) return null
    val label = title.ifBlank { filename }
    return when (previewKindFor(filename, null)) {
        PreviewKind.Pdf -> "pdf?url=${Uri.encode(url)}&title=${Uri.encode(label)}"
        PreviewKind.Image,
        PreviewKind.Text,
        -> "filePreview?url=${Uri.encode(url)}&title=${Uri.encode(label)}&kind=${Uri.encode(previewKindFor(filename, null).name)}"
        else -> null
    }
}

private fun forumDiscussionRoute(discussionId: Long, title: String, url: String): String =
    "forumDiscussion?discussionId=$discussionId" +
        "&title=${Uri.encode(title)}" +
        "&url=${Uri.encode(url)}"

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

private fun showUnhandledUrl(context: Context, url: String) {
    if (BuildConfig.DEBUG) {
        Log.d("OpenUrl", "unhandled: ${debugUrlLabel(url)}")
    }
    Toast.makeText(context, "このリンクは開けません", Toast.LENGTH_SHORT).show()
}

/** Logcat用 (DEBUGのみ): クエリ・フラグメントは含めない。 */
private fun debugUrlLabel(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "(unparseable)"
    val scheme = uri.scheme?.lowercase() ?: return "(no-scheme)"
    return if (scheme == "http" || scheme == "https") {
        "$scheme://${uri.host.orEmpty()}${uri.path.orEmpty()}"
    } else {
        "$scheme://"
    }
}

/**
 * トップレベルのタブ (DESIGN.md §2)。今日 / 時間割 / 課題 / コース の4つ。
 * タブはトップレベル遷移専用で、アクションは入れない。通知は今日タブの右上、設定はコースタブの右上から開く。
 */
@Composable
private fun MainTabs(
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
        rootNav.navigateFromUser(courseRoute(id, name, code))
    }

    fun openDeadline(event: ActionEvent) = openDeadlineEvent(
        event,
        onOpenUrl = onOpenUrl,
        onOpenAssignment = { courseId, moduleId, assignId, title ->
            rootNav.navigateFromUser("assignment/$courseId/$moduleId/$assignId?title=${Uri.encode(title)}")
        },
        onOpenQuiz = { target, title -> rootNav.navigateFromUser(quizRoute(target, title)) },
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
                    IconButton(onClick = { rootNav.navigateFromUser("notifications") }) {
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
                    IconButton(onClick = { rootNav.navigateFromUser("settings") }) {
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
                        rootNav.navigateFromUser(
                            "assignment/$courseId/$moduleId/$assignId?title=${Uri.encode(title)}",
                        )
                    },
                    onOpenQuiz = { target, title ->
                        rootNav.navigateFromUser(quizRoute(target, title))
                    },
                )
            }
            composable("courses") {
                CoursesScreen(
                    container = container,
                    onOpenCourse = ::openCourse,
                    onOpenSyllabus = { rootNav.navigateFromUser("syllabus") },
                    onOpenPortal = { rootNav.navigateFromUser("portal") },
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
