package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 立命館スチューデントポータル (Salesforce Experience Cloud) を
 * アプリ内WebViewで表示。Microsoft SSOのセッションCookieは永続化される。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalScreen() {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
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
