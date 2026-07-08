package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.BuildConfig
import dev.rits.bettermoodle.data.UrlPolicy
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

private const val AUTOLOGIN_ATTEMPT_TIMEOUT_MILLIS = 5_000L

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MoodleWebScreen(
    container: AppContainer,
    url: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var blockedNotice by remember(url) { mutableStateOf<BlockedNotice?>(null) }
    var initialUrl by remember(url) { mutableStateOf<String?>(null) }
    val canLoadOriginalUrl = UrlPolicy.isAllowedMoodleWebViewUrl(url)

    LaunchedEffect(url, canLoadOriginalUrl) {
        webView = null
        initialUrl = null
        loading = true
        if (canLoadOriginalUrl) {
            // オフライン等でHTTPタイムアウトまで待たされないよう、autologin試行は短時間で打ち切る
            initialUrl = withTimeoutOrNull(AUTOLOGIN_ATTEMPT_TIMEOUT_MILLIS) {
                container.moodleRepository.autologinUrl(url)
            } ?: url
        }
    }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "閉じる")
                    }
                },
            )
        },
    ) { padding ->
        if (!canLoadOriginalUrl) {
            Box(Modifier.padding(padding)) {
                ErrorBox("このMoodle URLは安全に開けません", onRetry = null)
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            blockedNotice?.let { notice ->
                BlockedNoticeBanner(
                    notice = notice,
                    onOpenExternal = { openInCustomTab(context, it) },
                    onDismiss = { blockedNotice = null },
                )
            }
            Box(Modifier.fillMaxSize()) {
                initialUrl?.let { urlToLoad ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.safeBrowsingEnabled = true
                                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        if (request == null || !request.isForMainFrame) return false
                                        val target = request.url?.toString() ?: return true
                                        if (BuildConfig.DEBUG) {
                                            Log.d("MoodleWeb", "navigate: ${debugUrlLabel(target)}")
                                        }
                                        // ログインリダイレクト (Moodle→SSO) を通すため、SSO許可リストで判定する。
                                        if (UrlPolicy.isAllowedSsoWebViewUrl(target)) return false
                                        blockedNotice = BlockedNotice.BlockedUrl(target)
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        loading = false
                                        CookieManager.getInstance().flush()
                                    }
                                }
                                setDownloadListener { _, _, _, _, _ ->
                                    blockedNotice = BlockedNotice.Message("このファイルはダウンロードできません")
                                }
                                loadUrl(urlToLoad)
                                webView = this
                            }
                        },
                    )
                }
                if (loading) LinearProgressIndicator(Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun BlockedNoticeBanner(
    notice: BlockedNotice,
    onOpenExternal: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val blockedUrl = (notice as? BlockedNotice.BlockedUrl)?.url
    val externalUrl = blockedUrl?.takeIf(UrlPolicy::canOpenExternally)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                notice.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
            if (externalUrl != null) {
                TextButton(onClick = { onOpenExternal(externalUrl) }) {
                    Text("外部ブラウザで開く")
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "閉じる")
            }
        }
    }
}

private sealed interface BlockedNotice {
    val message: String

    data class BlockedUrl(val url: String) : BlockedNotice {
        override val message: String = "Moodle外への遷移をブロックしました: ${safeUrlLabel(url)}"
    }

    data class Message(override val message: String) : BlockedNotice
}

/** ユーザー表示用: スキームとホストのみ。カスタムスキームはauthorityに秘密情報が入り得るためスキームのみ。 */
private fun safeUrlLabel(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "不明なURL"
    val scheme = uri.scheme?.lowercase() ?: return "不明なURL"
    return if (scheme == "http" || scheme == "https") {
        "$scheme://${uri.host.orEmpty()}"
    } else {
        "$scheme://"
    }
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
