package dev.rits.bettermoodle.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private const val LOGIN_POLLING_TIMEOUT_MILLIS = 10 * 60 * 1000L

/** SSO中の launch.php 定期確認の間隔。主経路は「lmsへ戻ったときの確認」で、これは補助 (サーバー負荷を抑える) */
private const val LOGIN_POLLING_INTERVAL_MILLIS = 5_000L

/** Logcat用 (DEBUGのみ): スキーム+ホスト+パスのみ。クエリ・フラグメントは出さない。 */
private fun loginDebugUrlLabel(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "(unparseable)"
    val scheme = uri.scheme?.lowercase() ?: return "(no-scheme)"
    return if (scheme == "http" || scheme == "https") {
        "$scheme://${uri.host.orEmpty()}${uri.path.orEmpty()}"
    } else {
        "$scheme://"
    }
}

/**
 * ログイン画面。
 * 「ログイン」を押すとアプリ内WebViewで学内SSO (Azure AD) を開き、
 * Moodleがカスタムスキームへリダイレクトしたところでトークンを回収する。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onToken: suspend (SsoLogin.Tokens) -> Boolean) {
    // 画面の作り直し (折りたたみ・分割画面など) で SSO の途中状態と passport を失わないよう保存する (G14)
    var showWebView by rememberSaveable { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    var loginError by rememberSaveable { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var tokenDelivered by remember { mutableStateOf(false) }
    val passport = rememberSaveable { SsoLogin.newPassport() }
    val scope = rememberCoroutineScope()

    /** トークンを保存する。保存に失敗したら受け取り済みを戻してエラーを出す (やり直せるように。G04) */
    fun deliverTokens(tokens: SsoLogin.Tokens) {
        if (tokenDelivered) return
        tokenDelivered = true
        scope.launch {
            if (!onToken(tokens)) {
                tokenDelivered = false
                loginError = "ログイン情報を端末に保存できませんでした。もう一度お試しください。"
            }
        }
    }

    // WebViewを経由せずlaunch.phpのLocationヘッダからトークンを直接取得する。
    // セッションCookieが無ければnull (通常のWebView SSOへ)。
    // WebViewと同じ passport を使う: 未ログインの launch.php は Moodle セッションの戻り先 (wantsurl) を
    // 自分のURLで上書きするため、別の passport だと SSO 完了後に WebView へ届くトークンの署名が
    // 合わなくなる (G01)
    suspend fun probeLaunchToken(): SsoLogin.Tokens? = withContext(Dispatchers.IO) {
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(MoodleClient.SITE_URL)
        }.getOrNull()
        SsoLogin.fetchTokenByProbe(passport, cookies)
    }

    val error = loginError
    if (error != null) {
        BackHandler {
            loginError = null
            showWebView = false
        }
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

    if (showWebView) {
        // SSO途中の戻る操作はログインの入口へ戻す (WebViewの履歴を辿らせない)
        BackHandler {
            showWebView = false
        }
        // WebViewはカスタムスキームへの302を「どのコールバックも呼ばずに」破棄することが
        // あり (KMSI後のPOSTリダイレクト連鎖など)、その場合ページイベントが一切来ない。
        // SSO中は定期的に launch.php を直叩きし、セッション成立を検出してトークンを回収する。
        LaunchedEffect(Unit) {
            val startedAt = SystemClock.elapsedRealtime()
            while (
                !tokenDelivered &&
                loginError == null &&
                SystemClock.elapsedRealtime() - startedAt < LOGIN_POLLING_TIMEOUT_MILLIS
            ) {
                val tokens = probeLaunchToken()
                if (tokens != null) {
                    deliverTokens(tokens)
                    break
                }
                delay(LOGIN_POLLING_INTERVAL_MILLIS)
            }
            // 黙って終わらせず、タイムアウトしたことを知らせてやり直せるようにする
            if (!tokenDelivered && loginError == null &&
                SystemClock.elapsedRealtime() - startedAt >= LOGIN_POLLING_TIMEOUT_MILLIS
            ) {
                loginError = "ログインが時間内に完了しませんでした。もう一度お試しください。"
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            onRelease = { releaseWebView(it) },
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
                            // SSOの連鎖は大学/Microsoft側の構成変更で経由ホストが増え得るため、
                            // ログインWebViewに限りhttpsは全て許可する (トークンの真正性は
                            // parseTokenUrl のmd5署名検証が担保する)。
                            // http(s)以外のスキームはWebViewに渡さない (ERR_UNKNOWN_URL_SCHEME 防止)
                            val allowed = UrlPolicy.isHttpsUrl(url)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "LoginWeb",
                                    "navigate ${if (allowed) "allow" else "block"}: ${loginDebugUrlLabel(url)}",
                                )
                            }
                            return !allowed
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            if (BuildConfig.DEBUG && url != null) {
                                android.util.Log.d("LoginWeb", "pageStarted: ${loginDebugUrlLabel(url)}")
                            }
                            if (handleTokenUrl(url)) view?.stopLoading()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            val current = url ?: return
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("LoginWeb", "pageFinished: ${loginDebugUrlLabel(current)}")
                            }
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
        EmptyIllustration()
        Spacer(Modifier.height(32.dp))
        Text("Moodle+R Companion", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "時間割・締切・シラバス・ポータルをひとつに",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        // 非公式クライアントであることを誤認させない (DESIGN.md §13)
        Text(
            "非公式アプリです (大学・Moodle公式ではありません)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(if (probing) "確認中..." else "立命館アカウントでログイン", style = MaterialTheme.typography.labelLarge)
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
                            deliverTokens(SsoLogin.Tokens(manualToken.trim(), null))
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
