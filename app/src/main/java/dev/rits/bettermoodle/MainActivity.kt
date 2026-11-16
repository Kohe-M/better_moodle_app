package dev.rits.bettermoodle

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.rits.bettermoodle.auth.SsoLogin
import dev.rits.bettermoodle.data.AuthState
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.ui.AssignmentScreen
import dev.rits.bettermoodle.ui.CourseScreen
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
import dev.rits.bettermoodle.ui.openInCustomTab
import dev.rits.bettermoodle.ui.theme.BetterMoodleTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import dev.rits.bettermoodle.ui.LocalCourseColors
import dev.rits.bettermoodle.ui.SettingsScreen
import dev.rits.bettermoodle.ui.SubScreenScaffold
import dev.rits.bettermoodle.ui.theme.AppTheme
import dev.rits.bettermoodle.work.DeadlineWorker
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
internal fun NavHostController.navigateFromUser(route: String) {
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
        val nativeRoute = Routes.pluginFile(normalized, title) ?: Routes.resolveNative(
            url = normalized,
            title = title,
            courseModule = { cmid -> container.moodleRepository.courseModule(cmid) },
            enrolledCourses = { container.moodleRepository.enrolledCourses() },
        )
        val go: (String) -> Unit = { route -> if (fromUser) rootNav.navigateFromUser(route) else rootNav.navigate(route) }
        when {
            nativeRoute != null -> go(nativeRoute)
            UrlPolicy.isAllowedMoodleWebViewUrl(normalized) ->
                go(Routes.moodleWeb(normalized, title))
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
    NavHost(navController = rootNav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) { MainTabs(container, rootNav, onOpenUrl = ::openUrl) }

        composable(Routes.NOTIFICATIONS) {
            SubScreenScaffold(title = "通知", onBack = { rootNav.popBackStack() }) {
                NotificationsScreen(container, onOpenUrl = ::openUrl)
            }
        }

        composable(Routes.SYLLABUS) {
            SubScreenScaffold(title = "シラバス", onBack = { rootNav.popBackStack() }) {
                SyllabusScreen(container)
            }
        }

        composable(Routes.PORTAL) {
            SubScreenScaffold(title = "スチューデントポータル", onBack = { rootNav.popBackStack() }) {
                PortalScreen()
            }
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(container, onBack = { rootNav.popBackStack() })
        }

        composable(Routes.COURSE) { entry ->
            val id = Routes.longArg(entry::arg, "courseId")
            val name = entry.savedName()
            val code = entry.savedCode()
            CourseScreen(
                container = container,
                courseId = id,
                courseName = name,
                courseCode = code,
                onBack = { rootNav.popBackStack() },
                onOpenPdf = { url, title ->
                    rootNav.navigateFromUser(Routes.pdf(url, title))
                },
                onOpenAssignment = { moduleId, assignId, title ->
                    rootNav.navigateFromUser(Routes.assignment(id, moduleId, assignId, title))
                },
                onOpenForum = { target, title ->
                    rootNav.navigateFromUser(Routes.forum(target, title))
                },
                onOpenQuiz = { target, title ->
                    rootNav.navigateFromUser(Routes.quiz(target, title))
                },
                onOpenPage = { target, title ->
                    rootNav.navigateFromUser(Routes.page(target, title))
                },
                onOpenFilePreview = { url, title, kind ->
                    rootNav.navigateFromUser(Routes.filePreview(url, title, kind))
                },
                onOpenMoodleWeb = { url, title ->
                    rootNav.navigateFromUser(Routes.moodleWeb(url, title))
                },
                onOpenUrl = { url, title -> openUrl(url, title) },
            )
        }

        composable(Routes.PDF) { entry ->
            val url = entry.arg("url").orEmpty()
            val title = entry.arg("title") ?: "PDF"
            PdfPreviewScreen(
                container = container,
                fileUrl = url,
                title = title,
                onBack = { rootNav.popBackStack() },
            )
        }

        composable(Routes.ASSIGNMENT) { entry ->
            AssignmentScreen(
                container = container,
                courseId = Routes.longArg(entry::arg, "courseId"),
                moduleId = Routes.longArg(entry::arg, "moduleId"),
                assignId = Routes.longArg(entry::arg, "assignId"),
                title = entry.arg("title") ?: "課題",
                onBack = { rootNav.popBackStack() },
                onOpenUrl = { url, title -> openUrl(url, title) },
                onOpenFilePreview = { file ->
                    val url = file.sourceUrl ?: return@AssignmentScreen
                    when (file.previewKind) {
                        PreviewKind.Pdf -> rootNav.navigateFromUser(Routes.pdf(url, file.filename))
                        PreviewKind.Image,
                        PreviewKind.Text,
                        -> rootNav.navigateFromUser(Routes.filePreview(url, file.filename, file.previewKind))
                        PreviewKind.Html,
                        PreviewKind.Unsupported -> Unit
                    }
                },
                onOpenMoodleWeb = { url, webTitle ->
                    rootNav.navigateFromUser(Routes.moodleWeb(url, webTitle))
                },
            )
        }

        composable(Routes.FILE_PREVIEW) { entry ->
            val kind = runCatching {
                PreviewKind.valueOf(entry.arg("kind").orEmpty())
            }.getOrDefault(PreviewKind.Unsupported)
            FilePreviewScreen(
                container = container,
                fileUrl = entry.arg("url").orEmpty(),
                title = entry.arg("title") ?: "プレビュー",
                kind = kind,
                onBack = { rootNav.popBackStack() },
            )
        }

        composable(Routes.FORUM) { entry ->
            val target = Routes.forumTarget(entry::arg)
            ForumScreen(
                container = container,
                target = target,
                title = entry.arg("title") ?: "フォーラム",
                onBack = { rootNav.popBackStack() },
                onOpenUrl = { url, title -> openUrl(url, title) },
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigateFromUser(Routes.moodleWeb(url, "フォーラム"))
                    }
                },
            )
        }

        composable(Routes.FORUM_DISCUSSION) { entry ->
            ForumDiscussionScreen(
                container = container,
                discussionId = Routes.longArg(entry::arg, "discussionId"),
                title = entry.arg("title") ?: "フォーラム",
                fallbackUrl = entry.arg("url").orEmpty(),
                onBack = { rootNav.popBackStack() },
                onOpenUrl = { url, title -> openUrl(url, title) },
                onFallbackWeb = { url, fallbackTitle ->
                    if (url.isNotBlank()) {
                        rootNav.navigateFromUser(Routes.moodleWeb(url, fallbackTitle))
                    }
                },
            )
        }

        composable(Routes.QUIZ) { entry ->
            val target = Routes.quizTarget(entry::arg)
            QuizScreen(
                container = container,
                target = target,
                title = entry.arg("title") ?: "小テスト",
                onBack = { rootNav.popBackStack() },
                onOpenUrl = { url, title -> openUrl(url, title) },
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigateFromUser(Routes.moodleWeb(url, "小テスト"))
                    }
                },
            )
        }

        composable(Routes.PAGE) { entry ->
            val target = Routes.pageTarget(entry::arg)
            PageScreen(
                container = container,
                target = target,
                title = entry.arg("title") ?: "ページ",
                onBack = { rootNav.popBackStack() },
                onFallbackWeb = {
                    val url = target.url
                    if (!url.isNullOrBlank()) {
                        rootNav.navigateFromUser(Routes.moodleWeb(url, "ページ"))
                    }
                },
                onOpenPdf = { url, title ->
                    rootNav.navigateFromUser(Routes.pdf(url, title))
                },
                onOpenFilePreview = { url, title, kind ->
                    rootNav.navigateFromUser(Routes.filePreview(url, title, kind))
                },
                onOpenUrl = { url, title -> openUrl(url, title) },
            )
        }

        composable(Routes.MOODLE_WEB) { entry ->
            MoodleWebScreen(
                container = container,
                url = entry.arg("url").orEmpty(),
                title = entry.arg("title") ?: "Moodle",
                onBack = { rootNav.popBackStack() },
            )
        }
    }
    }
}

/** ルート引数 (すべて文字列で受け渡す) を名前で読む */
private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)

// コース名・コードはナビゲーション引数で受け渡す (クエリ文字列)
private fun NavBackStackEntry.savedName(): String =
    arg("name").orEmpty()

private fun NavBackStackEntry.savedCode(): String? =
    arg("code")?.takeIf { it.isNotBlank() }

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
