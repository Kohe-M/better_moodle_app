package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    var observedClearSessionTick by remember { mutableIntStateOf(clearSessionTick) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    LaunchedEffect(clearSessionTick) {
        if (clearSessionTick != observedClearSessionTick) {
            observedClearSessionTick = clearSessionTick
            webView?.clearCache(true)
            webView?.loadUrl(PORTAL_URL)
        }
    }

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
                        val url = request.url?.toString() ?: return true
                        if (UrlPolicy.isAllowedPortalWebViewUrl(url)) return false
                        if (UrlPolicy.canOpenExternally(url)) openInCustomTab(context, url)
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        canGoBack = view?.canGoBack() == true
                        CookieManager.getInstance().flush()
                    }
                }
                loadUrl(PORTAL_URL)
                webView = this
            }
        },
    )
}

private const val PORTAL_URL = "https://sp.ritsumei.ac.jp/studentportal/s/"
