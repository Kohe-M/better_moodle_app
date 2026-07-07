package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.rits.bettermoodle.auth.SsoLogin

/**
 * ログイン画面。
 * 「ログイン」を押すとアプリ内WebViewで学内SSO (Azure AD) を開き、
 * Moodleがカスタムスキームへリダイレクトしたところでトークンを回収する。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onToken: (SsoLogin.Tokens) -> Unit) {
    var showWebView by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    val passport = remember { SsoLogin.newPassport() }

    if (showWebView) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            val tokens = SsoLogin.parseTokenUrl(url, passport)
                            if (tokens != null) {
                                onToken(tokens)
                                return true
                            }
                            // moodlemobile:// などhttp以外のスキームはWebViewに渡さない
                            // (ERR_UNKNOWN_URL_SCHEME 防止)
                            return !url.startsWith("http://") && !url.startsWith("https://")
                        }
                    }
                    loadUrl(SsoLogin.launchUrl(passport))
                }
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Moodle+R Companion", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "時間割・課題締切・シラバス・ポータルをひとつのアプリで",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { showWebView = true }, modifier = Modifier.fillMaxWidth()) {
            Text("立命館アカウントでログイン")
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { showManual = !showManual }, modifier = Modifier.fillMaxWidth()) {
            Text("トークンを手動入力 (上級者向け)")
        }
        if (showManual) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = manualToken,
                onValueChange = { manualToken = it },
                label = { Text("Moodleモバイルサービストークン") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (manualToken.isNotBlank()) {
                        onToken(SsoLogin.Tokens(manualToken.trim(), null))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("このトークンでログイン")
            }
        }
    }
}
