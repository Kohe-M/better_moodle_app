package dev.rits.bettermoodle.data

import java.net.URI
import java.net.URLEncoder

object UrlPolicy {
    const val MOODLE_HOST = "lms.ritsumei.ac.jp"
    private const val PORTAL_HOST = "sp.ritsumei.ac.jp"
    private val sensitiveQueryNames = setOf("token", "wstoken", "private_token", "privatetoken")

    private val microsoftAuthHosts = setOf(
        "login.microsoftonline.com",
        "login.live.com",
        "aadcdn.msauth.net",
        "aadcdn.msftauth.net",
        "secure.aadcdn.microsoftonline-p.com",
        // MFA・シームレスSSO・デバイス登録・認証方法の登録で経由する (G08)
        "login.microsoft.com",
        "device.login.microsoftonline.com",
        "autologon.microsoftazuread-sso.com",
        "mysignins.microsoft.com",
        "account.activedirectory.windowsazure.com",
    )

    fun isHttpsUrl(url: String): Boolean =
        parse(url)?.scheme.equals("https", ignoreCase = true)

    /** Moodleホストのhttp URLをhttpsに揃える (古いプラグインの通知URL対策)。他はそのまま返す。 */
    fun normalizeMoodleUrl(url: String): String {
        val source = url.trim()
        val uri = parse(source) ?: return source
        if (!uri.scheme.equals("http", ignoreCase = true)) return source
        if (!uri.host.equals(MOODLE_HOST, ignoreCase = true)) return source
        return source.replaceRange(0, "http".length, "https")
    }

    /**
     * Moodleサイトのhttps URLか。[host] は既定で本番ホスト。
     * ループバック (127.0.0.1 / localhost) だけは http も許可する (MockWebServer によるテスト用。
     * 本番ホストは https 以外を通さない)。
     */
    fun isAllowedMoodleUrl(url: String, host: String = MOODLE_HOST): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.host.equals(host, ignoreCase = true)) return false
        if (uri.scheme.equals("https", ignoreCase = true)) return true
        return uri.scheme.equals("http", ignoreCase = true) && isLoopbackHost(host)
    }

    fun isAllowedMoodlePluginFileUrl(url: String, host: String = MOODLE_HOST): Boolean {
        val uri = parse(url) ?: return false
        if (!isAllowedMoodleUrl(url, host)) return false
        val path = uri.path.orEmpty()
        return path == "/pluginfile.php" ||
            path.startsWith("/pluginfile.php/") ||
            path == "/webservice/pluginfile.php" ||
            path.startsWith("/webservice/pluginfile.php/")
    }

    fun isSafeMoodlePluginFileSourceUrl(url: String, host: String = MOODLE_HOST): Boolean {
        val uri = parse(url) ?: return false
        return isAllowedMoodlePluginFileUrl(url, host) && !containsSensitiveParameter(uri)
    }

    private fun isLoopbackHost(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"

    fun isMoodleCourseUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        return isAllowedMoodleUrl(url) && uri.path == "/course/view.php"
    }

    fun canOpenExternally(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (containsSensitiveParameter(uri)) return false
        val host = uri.host ?: return false
        return host.isNotBlank() && !host.equals(MOODLE_HOST, ignoreCase = true)
    }

    fun canUseMoodleAutologin(url: String): Boolean {
        val uri = parse(url) ?: return false
        return isAllowedMoodleUrl(url) && !containsSensitiveParameter(uri)
    }

    fun isAllowedMoodleWebViewUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        return isAllowedMoodleUrl(url) && !containsSensitiveParameter(uri)
    }

    fun isAllowedPortalWebViewUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == PORTAL_HOST || host in microsoftAuthHosts
    }

    fun isAllowedSsoWebViewUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == MOODLE_HOST || host in microsoftAuthHosts
    }

    fun appendMoodleToken(url: String, token: String, host: String = MOODLE_HOST): String? {
        val source = url.trim()
        if (!isSafeMoodlePluginFileSourceUrl(source, host)) return null
        val uri = parse(source) ?: return null
        if (uri.rawFragment != null) return null
        val query = uri.rawQuery
        val tokenParam = "token=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
        val separator = if (query.isNullOrBlank()) "?" else "&"
        return rewriteBarePluginFilePath(source, uri) + separator + tokenParam
    }

    private fun parse(url: String): URI? =
        runCatching { URI(url.trim()) }.getOrNull()

    private fun hasQueryParameter(rawQuery: String?, name: String): Boolean =
        rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .any { it.substringBefore('=').equals(name, ignoreCase = true) }

    private fun containsSensitiveParameter(uri: URI): Boolean {
        if (sensitiveQueryNames.any { hasQueryParameter(uri.rawQuery, it) }) return true
        val fragment = uri.rawFragment.orEmpty().lowercase()
        return sensitiveQueryNames.any { "$it=" in fragment }
    }

    private fun rewriteBarePluginFilePath(source: String, uri: URI): String {
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath != "/pluginfile.php" && !rawPath.startsWith("/pluginfile.php/")) {
            return source
        }
        val authority = uri.rawAuthority ?: return source
        val authorityStart = source.indexOf("//").takeIf { it >= 0 }?.plus(2) ?: return source
        val pathStart = authorityStart + authority.length
        if (!source.startsWith("/pluginfile.php", pathStart)) return source
        return source.replaceRange(
            pathStart,
            pathStart + "/pluginfile.php".length,
            "/webservice/pluginfile.php",
        )
    }

}
