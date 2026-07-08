package dev.rits.bettermoodle

import dev.rits.bettermoodle.auth.SsoLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class SsoLoginTest {

    private val passport = "0123456789abcdef0123456789abcdef"
    private val signature = md5("https://lms.ritsumei.ac.jp$passport")

    @Test
    fun `launch URL contains passport and fixed scheme`() {
        val url = SsoLogin.launchUrl(passport)
        assertTrue(url.startsWith("https://lms.ritsumei.ac.jp/admin/tool/mobile/launch.php?"))
        assertTrue("passport=$passport" in url)
        assertTrue("urlscheme=bettermoodle" in url)
        assertTrue("service=moodle_mobile_app" in url)
    }

    @Test
    fun `accepts signed bettermoodle callback`() {
        val payload = encode("$signature:::aabbccddeeff00112233:::ignoredPrivateToken")
        val tokens = SsoLogin.parseTokenUrl("bettermoodle://token=$payload", passport)!!
        assertEquals("aabbccddeeff00112233", tokens.wsToken)
        assertEquals("ignoredPrivateToken", tokens.privateToken)
    }

    @Test
    fun `accepts moodlemobile callback`() {
        val payload = encode("$signature:::aabbccddeeff00112233")
        val tokens = SsoLogin.parseTokenUrl("moodlemobile://token=$payload", passport)!!
        assertEquals("aabbccddeeff00112233", tokens.wsToken)
    }

    @Test
    fun `rejects unknown scheme callback`() {
        val payload = encode("$signature:::aabbccddeeff00112233")
        assertNull(SsoLogin.parseTokenUrl("evilapp://token=$payload", passport))
    }

    @Test
    fun `rejects signature mismatch`() {
        val payload = encode("${md5("other")}:::aabbccddeeff00112233")
        assertNull(SsoLogin.parseTokenUrl("bettermoodle://token=$payload", passport))
    }

    @Test
    fun `rejects malformed callbacks`() {
        assertNull(SsoLogin.parseTokenUrl("https://lms.ritsumei.ac.jp/my/", passport))
        assertNull(SsoLogin.parseTokenUrl("bettermoodle://token=abc", passport))
        assertNull(SsoLogin.parseTokenUrl("bettermoodle://token=" + encode("no-separator"), passport))
        assertNull(SsoLogin.parseTokenUrl("bettermoodle://token=" + encode("bad:::short"), passport))
    }

    @Test
    fun `accepts URL encoded payload`() {
        val payload = URLEncoder.encode(encode("$signature:::aabbccddeeff00112233"), "UTF-8")
        val tokens = SsoLogin.parseTokenUrl("bettermoodle://token=$payload", passport)!!
        assertEquals("aabbccddeeff00112233", tokens.wsToken)
    }

    @Test
    fun `token scheme URL detection`() {
        assertTrue(SsoLogin.isTokenSchemeUrl("bettermoodle://token=abc"))
        assertTrue(SsoLogin.isTokenSchemeUrl(" BetterMoodle://token=abc "))
        assertTrue(SsoLogin.isTokenSchemeUrl("moodlemobile://token=abc"))
        assertFalse(SsoLogin.isTokenSchemeUrl("https://lms.ritsumei.ac.jp/my/"))
    }

    @Test
    fun `location header probe extracts tokens only from scheme redirects`() {
        val payload = encode("$signature:::aabbccddeeff00112233:::priv")
        val tokens = SsoLogin.tokensFromLocationHeader("bettermoodle://token=$payload", passport)!!
        assertEquals("aabbccddeeff00112233", tokens.wsToken)
        val moodleMobileTokens = SsoLogin.tokensFromLocationHeader("moodlemobile://token=$payload", passport)!!
        assertEquals("aabbccddeeff00112233", moodleMobileTokens.wsToken)
        assertNull(SsoLogin.tokensFromLocationHeader(null, passport))
        assertNull(SsoLogin.tokensFromLocationHeader("https://lms.ritsumei.ac.jp/login/index.php", passport))
    }

    @Test
    fun `passport has at least 128 bits of entropy material`() {
        val first = SsoLogin.newPassport()
        val second = SsoLogin.newPassport()
        assertTrue(first != second)
        assertTrue(first.length >= 32)
    }

    private fun encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
