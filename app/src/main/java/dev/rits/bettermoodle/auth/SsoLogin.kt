package dev.rits.bettermoodle.auth

import dev.rits.bettermoodle.data.MoodleClient
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 公式Moodleアプリと同じ「ブラウザSSO → カスタムスキームでトークン受取」フロー。
 *
 * 1. launch.php?service=moodle_mobile_app&passport={p}&urlscheme={scheme} をWebViewで開く
 * 2. 学内SSO (Azure AD) でログイン完了後、
 *    {scheme}://token=BASE64("md5sig:::wstoken[:::privatetoken]") にリダイレクトされる
 * 3. md5sig == md5(siteUrl + passport) を検証してトークンを取り出す
 *
 * Callback scheme is fixed to the app-owned bettermoodle scheme declared in AndroidManifest.
 */
object SsoLogin {

    data class Tokens(val wsToken: String, val privateToken: String?)

    private val TOKEN_URL_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://token=(.+)$")
    private val secureRandom = SecureRandom()

    fun newPassport(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun launchUrl(passport: String): String =
        "${MoodleClient.LAUNCH_URL}?service=moodle_mobile_app&passport=$passport&urlscheme=${MoodleClient.URL_SCHEME}"

    /** アプリのコールバックスキーム (bettermoodle://) へのリダイレクトかどうか */
    fun isTokenSchemeUrl(url: String): Boolean =
        url.trim().startsWith("${MoodleClient.URL_SCHEME}://", ignoreCase = true)

    private val probeHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * launch.php をHTTPで直接叩き、302の Location ヘッダからトークンを取り出す。
     *
     * WebViewはカスタムスキームへのサーバーリダイレクトを (POST後や初回ロードの302で)
     * どのコールバックにも通知せず黙って破棄することがあるため、WebViewに依存しない
     * 受け取り経路として使う。セッションCookieが無い/期限切れの場合は Location が
     * ログインページになるので null を返す (呼び出し側でWebViewのSSOにフォールバック)。
     */
    fun fetchTokenByProbe(passport: String, cookieHeader: String?): Tokens? = runCatching {
        val request = okhttp3.Request.Builder()
            .url(launchUrl(passport))
            .apply { if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader) }
            .build()
        probeHttp.newCall(request).execute().use { resp ->
            tokensFromLocationHeader(resp.header("Location"), passport)
        }
    }.getOrNull()

    /** リダイレクト先ヘッダがトークンスキームURLならトークンを取り出す */
    fun tokensFromLocationHeader(location: String?, passport: String): Tokens? {
        val loc = location?.trim() ?: return null
        if (!isTokenSchemeUrl(loc)) return null
        return parseTokenUrl(loc, passport)
    }

    /**
     * "bettermoodle://token=..." 形式のURLからトークンを取り出す。
     * 対象外のURL (通常のhttpページ等) はnull。
     */
    fun parseTokenUrl(url: String, passport: String): Tokens? {
        val match = TOKEN_URL_REGEX.find(url.trim()) ?: return null
        if (match.groupValues[1] != MoodleClient.URL_SCHEME) return null
        val encoded = runCatching {
            URLDecoder.decode(match.groupValues[2], Charsets.UTF_8.name())
        }.getOrNull() ?: return null
        val decoded = decodeBase64(encoded) ?: return null

        val parts = decoded.split(":::")
        if (parts.size < 2 || parts[1].length < 16) return null

        val expected = md5(MoodleClient.SITE_URL + passport)
        if (!parts[0].equals(expected, ignoreCase = true)) return null

        return Tokens(wsToken = parts[1], privateToken = parts.getOrNull(2))
    }

    private fun decodeBase64(s: String): String? {
        val candidates = listOf(Base64.getDecoder(), Base64.getUrlDecoder(), Base64.getMimeDecoder())
        for (decoder in candidates) {
            runCatching { return String(decoder.decode(s)) }
        }
        return null
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
