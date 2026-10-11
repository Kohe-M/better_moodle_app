package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.rits.bettermoodle.data.UrlPolicy

/**
 * 立命館スチューデントポータル (Salesforce Experience Cloud) を
 * アプリ内WebViewで表示。Microsoft SSOのセッションCookieは永続化される。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalScreen(clearSessionTick: Int = 0) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var mainFrameError by remember { mutableStateOf<MainFrameError?>(null) }
    var observedClearSessionTick by remember { mutableIntStateOf(clearSessionTick) }
    // 画面の作り直し (回転・プロセス再生成) のあとに履歴と表示中のページを復元する (G09)。
    // ポータルはコースタブから push する画面なので、戻る操作で閉じたときは破棄される
    val webState = rememberSaveable { Bundle() }
    val context = LocalContext.current

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    LaunchedEffect(clearSessionTick) {
        if (clearSessionTick != observedClearSessionTick) {
            observedClearSessionTick = clearSessionTick
            webView?.clearCache(true)
            webState.clear()
            webView?.loadUrl(PORTAL_URL)
        }
    }

    Box(Modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        onRelease = { view ->
            if (webView === view) webView = null
            webState.clear()
            view.saveState(webState)
            releaseWebView(view)
        },
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
                        val url = request.url?.toString() ?: return true
                        if (UrlPolicy.isAllowedPortalWebViewUrl(url)) return false
                        if (UrlPolicy.canOpenExternally(url)) openInCustomTab(context, url)
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        loading = true
                        mainFrameError = null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        loading = false
                        canGoBack = view?.canGoBack() == true
                        CookieManager.getInstance().flush()
                    }

                    // ポータル (Salesforce Lightning) は history API で画面を切り替え、onPageFinished が来ない。
                    // 履歴の更新ごとに戻る操作の可否を反映する (G09)
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        canGoBack = view?.canGoBack() == true
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        mainFrameErrorMessage(request, error)?.let {
                            loading = false
                            mainFrameError = it
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        mainFrameHttpErrorMessage(request, errorResponse)?.let {
                            loading = false
                            mainFrameError = it
                        }
                    }
                }
                val restored = !webState.isEmpty && restoreState(webState) != null
                if (!restored) loadUrl(PORTAL_URL)
                canGoBack = canGoBack()
                webView = this
            }
        },
    )
    mainFrameError?.let { error ->
        WebViewErrorPanel(
            error = error,
            onRetry = {
                mainFrameError = null
                loading = true
                webView?.reload()
            },
            onOpenExternal = { openInCustomTab(context, error.url?.takeIf(UrlPolicy::canOpenExternally) ?: PORTAL_URL) },
        )
    }
    if (loading) LinearProgressIndicator(Modifier.align(Alignment.TopCenter))
    }
}

private const val PORTAL_URL = "https://sp.ritsumei.ac.jp/studentportal/s/"
