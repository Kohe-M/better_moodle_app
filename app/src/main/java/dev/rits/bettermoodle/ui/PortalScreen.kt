package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.rits.bettermoodle.data.UrlPolicy
import kotlinx.coroutines.launch

/**
 * 立命館スチューデントポータル (Salesforce Experience Cloud) を
 * アプリ内WebViewで表示。Microsoft SSOのセッションCookieは永続化される。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalScreen(onClearAllWebSessions: suspend () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var showClearSessionConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    Column(Modifier.fillMaxSize()) {
        TextButton(
            onClick = { showClearSessionConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Webセッションを全て削除(ポータル/Moodle共通)")
        }
        AndroidView(
            modifier = Modifier.weight(1f),
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

    if (showClearSessionConfirm) {
        AlertDialog(
            onDismissRequest = { showClearSessionConfirm = false },
            title = { Text("Webセッションを削除") },
            text = { Text("Moodle側のWebログイン状態も削除されます。よろしいですか?") },
            confirmButton = {
                TextButton(onClick = {
                    showClearSessionConfirm = false
                    scope.launch {
                        onClearAllWebSessions()
                        webView?.clearCache(true)
                        webView?.loadUrl(PORTAL_URL)
                    }
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}

private const val PORTAL_URL = "https://sp.ritsumei.ac.jp/studentportal/s/"
