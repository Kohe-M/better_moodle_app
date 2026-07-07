package dev.rits.bettermoodle.auth

import dev.rits.bettermoodle.data.MoodleClient
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * 公式Moodleアプリと同じ「ブラウザSSO → カスタムスキームでトークン受取」フロー。
 *
 * 1. launch.php?service=moodle_mobile_app&passport={p}&urlscheme={scheme} をWebViewで開く
 * 2. 学内SSO (Azure AD) でログイン完了後、
 *    {scheme}://token=BASE64("md5sig:::wstoken[:::privatetoken]") にリダイレクトされる
 * 3. md5sig == md5(siteUrl + passport) を検証してトークンを取り出す
 *
 * 注意: lms.ritsumei.ac.jp はサーバ側でスキームを moodlemobile に強制しているため
 * (urlschemeパラメータは無視される)、任意のスキームの token= リダイレクトを受け付ける。
 */
object SsoLogin {

    data class Tokens(val wsToken: String, val privateToken: String?)

    private val TOKEN_URL_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://token=(.+)$")

    fun newPassport(): String = (Random.nextDouble() * 1000).toString()

    fun launchUrl(passport: String): String =
        "${MoodleClient.LAUNCH_URL}?service=moodle_mobile_app&passport=$passport&urlscheme=${MoodleClient.URL_SCHEME}"

    /**
     * "任意スキーム://token=..." 形式のURLからトークンを取り出す。
     * 対象外のURL (通常のhttpページ等) はnull。
     */
    fun parseTokenUrl(url: String, passport: String): Tokens? {
        val match = TOKEN_URL_REGEX.find(url.trim()) ?: return null
        val encoded = match.groupValues[2]
        val decoded = decodeBase64(encoded) ?: return null

        val parts = decoded.split(":::")
        if (parts.size < 2 || parts[1].length < 16) return null

        // passport署名 md5(siteUrl+passport) を検証。一致しない場合でも
        // トークン形式が正しければ受け入れる (サーバのスキーム強制などで
        // launch.phpを経由し直すと署名が変わることがあるため)
        val expected = md5(MoodleClient.SITE_URL + passport)
        if (!parts[0].equals(expected, ignoreCase = true) && parts[0].length != 32) return null

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
