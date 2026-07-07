package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.rits.bettermoodle.data.UrlPolicy

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MoodleWebScreen(
    url: String,
    onBack: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }

    if (!UrlPolicy.isAllowedMoodleWebViewUrl(url)) {
        ErrorBox("This Moodle URL cannot be opened safely.", onRetry = null)
        return
    }

    Box(Modifier.fillMaxSize()) {
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
                            val target = request?.url?.toString() ?: return true
                            if (UrlPolicy.isAllowedMoodleWebViewUrl(target)) return false
                            error = "Blocked non-Moodle or unsafe navigation."
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            canGoBack = view?.canGoBack() == true
                            CookieManager.getInstance().flush()
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.align(Alignment.TopCenter))
        error?.let {
            Text(
                it,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
