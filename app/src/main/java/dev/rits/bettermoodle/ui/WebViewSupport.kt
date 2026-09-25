package dev.rits.bettermoodle.ui

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * WebView画面で共通の所有・破棄・失敗表示。
 *
 * - View破棄: AndroidView の onRelease から [releaseWebView] を呼び、画面を離れたら
 *   読み込みを止めて destroy する (Cookie は CookieManager が持つので消えない)
 * - main frame の通信/HTTPエラーは [MainFrameError] として画面に出し、再試行できるようにする
 */
data class MainFrameError(val message: String, val url: String?)

fun releaseWebView(view: WebView) {
    runCatching {
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.webChromeClient = null
        view.loadUrl("about:blank")
        view.destroy()
    }
}

/** main frame のエラーを文言に変換する。サブリソースのエラーは null (無視)。 */
fun mainFrameErrorMessage(request: WebResourceRequest?, error: WebResourceError?): MainFrameError? {
    if (request?.isForMainFrame != true) return null
    val description = error?.description?.toString().orEmpty()
    return MainFrameError(
        message = "ページを読み込めませんでした。通信環境を確認して再試行してください。" +
            if (description.isNotBlank()) "\n($description)" else "",
        url = request.url?.toString(),
    )
}

fun mainFrameHttpErrorMessage(request: WebResourceRequest?, response: WebResourceResponse?): MainFrameError? {
    if (request?.isForMainFrame != true) return null
    val status = response?.statusCode ?: return null
    if (status < 400) return null
    return MainFrameError(
        message = "サーバーがエラーを返しました (HTTP $status)。",
        url = request.url?.toString(),
    )
}

@Composable
fun WebViewErrorPanel(
    error: MainFrameError,
    onRetry: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(error.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = onRetry) { Text("再試行") }
                if (onOpenExternal != null) {
                    OutlinedButton(onClick = onOpenExternal) { Text("外部ブラウザで開く") }
                }
            }
        }
    }
}
