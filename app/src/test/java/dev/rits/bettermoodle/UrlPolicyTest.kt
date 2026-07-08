package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.UrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun `allows only Moodle pluginfile URL for token append`() {
        val url = "https://lms.ritsumei.ac.jp/pluginfile.php/123/mod_resource/content/1/a.pdf"
        val authed = UrlPolicy.appendMoodleToken(url, "secret")!!
        assertEquals(
            "https://lms.ritsumei.ac.jp/webservice/pluginfile.php/123/mod_resource/content/1/a.pdf?token=secret",
            authed,
        )
    }

    @Test
    fun `token append keeps webservice pluginfile path`() {
        val url = "https://lms.ritsumei.ac.jp/webservice/pluginfile.php/123/mod_resource/content/1/a.pdf"
        val authed = UrlPolicy.appendMoodleToken(url, "secret")!!
        assertEquals("$url?token=secret", authed)
    }

    @Test
    fun `token append rewrites bare page pluginfile path to webservice path`() {
        val url = "https://lms.ritsumei.ac.jp/pluginfile.php/123/mod_page/content/1/a.pdf"
        val authed = UrlPolicy.appendMoodleToken(url, "secret")!!
        assertEquals(
            "https://lms.ritsumei.ac.jp/webservice/pluginfile.php/123/mod_page/content/1/a.pdf?token=secret",
            authed,
        )
    }

    @Test
    fun `preserves existing non-sensitive query`() {
        val url = "https://lms.ritsumei.ac.jp/pluginfile.php/123/a.pdf?forcedownload=1"
        val authed = UrlPolicy.appendMoodleToken(url, "new")!!
        assertTrue("forcedownload=1" in authed)
        assertTrue("token=new" in authed)
        assertTrue(authed.startsWith("https://lms.ritsumei.ac.jp/webservice/pluginfile.php/123/a.pdf?"))
    }

    @Test
    fun `token append preserves already encoded pluginfile path`() {
        val url = "https://lms.ritsumei.ac.jp/pluginfile.php/123/mod_resource/content/1/%E8%AC%9B%E7%BE%A9.pdf?forcedownload=1"
        val authed = UrlPolicy.appendMoodleToken(url, "secret token")!!
        assertEquals(
            "https://lms.ritsumei.ac.jp/webservice/pluginfile.php/123/mod_resource/content/1/%E8%AC%9B%E7%BE%A9.pdf?forcedownload=1&token=secret+token",
            authed,
        )
        assertFalse("%25" in authed)
        assertTrue("/content/1/%E8%AC%9B%E7%BE%A9.pdf?forcedownload=1&token=" in authed)
    }

    @Test
    fun `rejects pluginfile URLs that already carry sensitive parameters`() {
        val url = "https://lms.ritsumei.ac.jp/pluginfile.php/123/a.pdf?forcedownload=1&token=old"
        assertNull(UrlPolicy.appendMoodleToken(url, "new"))
        assertNull(UrlPolicy.appendMoodleToken("https://lms.ritsumei.ac.jp/pluginfile.php/123/a.pdf?wstoken=old", "new"))
        assertNull(UrlPolicy.appendMoodleToken("https://lms.ritsumei.ac.jp/pluginfile.php/123/a.pdf?private_token=old", "new"))
        assertFalse(UrlPolicy.isSafeMoodlePluginFileSourceUrl(url))
    }

    @Test
    fun `rejects lookalike Moodle host`() {
        assertNull(
            UrlPolicy.appendMoodleToken(
                "https://lms.ritsumei.ac.jp.evil.example/pluginfile.php/1/a.pdf",
                "secret",
            ),
        )
    }

    @Test
    fun `rejects external host and http and javascript`() {
        assertNull(UrlPolicy.appendMoodleToken("https://evil.example/pluginfile.php/1/a.pdf", "secret"))
        assertNull(UrlPolicy.appendMoodleToken("http://lms.ritsumei.ac.jp/pluginfile.php/1/a.pdf", "secret"))
        assertNull(UrlPolicy.appendMoodleToken("javascript:alert(1)", "secret"))
    }

    @Test
    fun `distinguishes Moodle course and external教材 URLs`() {
        assertTrue(UrlPolicy.isMoodleCourseUrl("https://lms.ritsumei.ac.jp/course/view.php?id=42"))
        assertFalse(UrlPolicy.isMoodleCourseUrl("https://lms.ritsumei.ac.jp.evil.example/course/view.php?id=42"))
        assertFalse(UrlPolicy.isMoodleCourseUrl("https://example.com/course/view.php?id=42"))
    }

    @Test
    fun `external open rejects token URLs and dangerous schemes`() {
        assertTrue(UrlPolicy.canOpenExternally("https://example.com/material"))
        assertFalse(UrlPolicy.canOpenExternally("https://lms.ritsumei.ac.jp/mod/assign/view.php?id=42"))
        assertFalse(UrlPolicy.canOpenExternally("https://example.com/material?token=secret"))
        assertFalse(UrlPolicy.canOpenExternally("https://example.com/material?wstoken=secret"))
        assertFalse(UrlPolicy.canOpenExternally("file:///tmp/a.pdf"))
        assertFalse(UrlPolicy.canOpenExternally("content://example/a.pdf"))
        assertFalse(UrlPolicy.canOpenExternally("data:text/html,hello"))
        assertFalse(UrlPolicy.canOpenExternally("intent://example"))
        assertFalse(UrlPolicy.canOpenExternally("javascript:alert(1)"))
    }

    @Test
    fun `moodle webview allows only safe internal URLs without tokens`() {
        assertTrue(UrlPolicy.isAllowedMoodleWebViewUrl("https://lms.ritsumei.ac.jp/mod/forum/view.php?id=42"))
        assertFalse(UrlPolicy.isAllowedMoodleWebViewUrl("https://lms.ritsumei.ac.jp/mod/forum/view.php?id=42&wstoken=secret"))
        assertFalse(UrlPolicy.isAllowedMoodleWebViewUrl("https://lms.ritsumei.ac.jp.evil.example/mod/forum/view.php?id=42"))
        assertFalse(UrlPolicy.isAllowedMoodleWebViewUrl("http://lms.ritsumei.ac.jp/mod/forum/view.php?id=42"))
    }

    @Test
    fun `portal webview allowlist is strict`() {
        assertTrue(UrlPolicy.isAllowedPortalWebViewUrl("https://sp.ritsumei.ac.jp/studentportal/s/"))
        assertTrue(UrlPolicy.isAllowedPortalWebViewUrl("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"))
        assertFalse(UrlPolicy.isAllowedPortalWebViewUrl("https://sp.ritsumei.ac.jp.evil.example/"))
        assertFalse(UrlPolicy.isAllowedPortalWebViewUrl("http://sp.ritsumei.ac.jp/studentportal/s/"))
    }

    @Test
    fun `sso webview allowlist is strict`() {
        assertTrue(UrlPolicy.isAllowedSsoWebViewUrl("https://lms.ritsumei.ac.jp/admin/tool/mobile/launch.php"))
        assertTrue(UrlPolicy.isAllowedSsoWebViewUrl("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"))
        assertFalse(UrlPolicy.isAllowedSsoWebViewUrl("https://example.com/phishing"))
        assertFalse(UrlPolicy.isAllowedSsoWebViewUrl("http://lms.ritsumei.ac.jp/admin/tool/mobile/launch.php"))
        assertFalse(UrlPolicy.isAllowedSsoWebViewUrl("moodlemobile://token=abcdef"))
    }
}
