package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.rits.bettermoodle.BuildConfig
import dev.rits.bettermoodle.auth.SsoLogin
import dev.rits.bettermoodle.data.MoodleClient
import dev.rits.bettermoodle.data.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var loginError by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var tokenDelivered by remember { mutableStateOf(false) }
    val passport = remember { SsoLogin.newPassport() }
    val scope = rememberCoroutineScope()

    fun deliverTokens(tokens: SsoLogin.Tokens) {
        if (tokenDelivered) return
        tokenDelivered = true
        onToken(tokens)
    }

    // WebViewを経由せずlaunch.phpのLocationヘッダからトークンを直接取得する。
    // セッションCookieが無ければnull (通常のWebView SSOへ)。
    suspend fun probeLaunchToken(): SsoLogin.Tokens? = withContext(Dispatchers.IO) {
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(MoodleClient.SITE_URL)
        }.getOrNull()
        SsoLogin.fetchTokenByProbe(SsoLogin.newPassport(), cookies)
    }

    if (showWebView) {
        val error = loginError
        if (error != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        loginError = null
                        showWebView = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("やり直す")
                }
            }
            return
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
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        /**
                         * トークンURL (bettermoodle://token=...) ならここで回収してtrueを返す。
                         * KMSI (「ログイン状態を維持しますか?」) はフォームPOSTで送信され、
                         * POST起点のリダイレクトでは shouldOverrideUrlLoading が呼ばれないため、
                         * onPageStarted / onReceivedError (ERR_UNKNOWN_URL_SCHEME) からも拾う。
                         */
                        private fun handleTokenUrl(url: String?): Boolean {
                            val target = url ?: return false
                            if (!SsoLogin.isTokenSchemeUrl(target)) return false
                            if (tokenDelivered) return true
                            val tokens = SsoLogin.parseTokenUrl(target, passport)
                            if (tokens != null) {
                                deliverTokens(tokens)
                            } else {
                                loginError = "ログイン情報を受け取れませんでした。もう一度お試しください。"
                            }
                            return true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (handleTokenUrl(url)) return true
                            // http(s)以外のスキームはWebViewに渡さない (ERR_UNKNOWN_URL_SCHEME 防止)
                            return !UrlPolicy.isAllowedSsoWebViewUrl(url)
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            if (handleTokenUrl(url)) view?.stopLoading()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            val current = url ?: return
                            if (tokenDelivered || probing) return
                            // カスタムスキームへの302はWebViewがコールバックなしに破棄する
                            // ことがあるため、lmsホストのページに戻ってきたタイミングで
                            // launch.php を直接叩いてトークンを回収する。
                            if (!UrlPolicy.isAllowedMoodleUrl(current)) return
                            probing = true
                            scope.launch {
                                val tokens = probeLaunchToken()
                                probing = false
                                if (tokens != null) deliverTokens(tokens)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            val url = request.url?.toString()
                            if (handleTokenUrl(url)) return
                            if (!tokenDelivered) {
                                loginError = "ログインページを読み込めませんでした。通信環境を確認して、もう一度お試しください。"
                            }
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
        Button(
            onClick = {
                if (probing) return@Button
                probing = true
                scope.launch {
                    // 前回のSSOセッションが残っていればWebViewを開かずにログイン完了できる
                    val tokens = probeLaunchToken()
                    probing = false
                    if (tokens != null) deliverTokens(tokens) else showWebView = true
                }
            },
            enabled = !probing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (probing) "確認中..." else "立命館アカウントでログイン")
        }
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { showManual = !showManual }, modifier = Modifier.fillMaxWidth()) {
                Text("トークンを手動入力 (debug)")
            }
            if (showManual) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    label = { Text("Moodleモバイルサービストークン") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
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
}
