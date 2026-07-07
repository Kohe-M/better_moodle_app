package dev.rits.bettermoodle

import dev.rits.bettermoodle.auth.SsoLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class SsoLoginTest {

    private val passport = "123.456"
    private val signature = md5("https://lms.ritsumei.ac.jp$passport")

    @Test
    fun `launch URLにpassportとschemeが含まれる`() {
        val url = SsoLogin.launchUrl(passport)
        assertTrue(url.startsWith("https://lms.ritsumei.ac.jp/admin/tool/mobile/launch.php?"))
        assertTrue("passport=$passport" in url)
        assertTrue("urlscheme=bettermoodle" in url)
        assertTrue("service=moodle_mobile_app" in url)
    }

    @Test
    fun `サーバ強制のmoodlemobileスキームでもトークンを取り出せる`() {
        // lms.ritsumei.ac.jp は urlscheme パラメータを無視して moodlemobile を強制する
        val payload = encode("$signature:::aabbccddeeff00112233:::privateTokenValue")
        val tokens = SsoLogin.parseTokenUrl("moodlemobile://token=$payload", passport)!!
        assertEquals("aabbccddeeff00112233", tokens.wsToken)
        assertEquals("privateTokenValue", tokens.privateToken)
    }

    @Test
    fun `自前スキームでもトークンを取り出せる`() {
        val payload = encode("$signature:::aabbccddeeff00112233")
        val tokens = SsoLogin.parseTokenUrl("bettermoodle://token=$payload", passport)!!
        assertEquals("aabbccddeeff00112233", tokens.wsToken)
        assertNull(tokens.privateToken)
    }

    @Test
    fun `署名不一致でもmd5形式ならトークンを受け入れる`() {
        val otherSig = md5("something else")
        val payload = encode("$otherSig:::aabbccddeeff00112233")
        val tokens = SsoLogin.parseTokenUrl("moodlemobile://token=$payload", passport)
        assertEquals("aabbccddeeff00112233", tokens?.wsToken)
    }

    @Test
    fun `不正なURLやペイロードはnull`() {
        assertNull(SsoLogin.parseTokenUrl("https://lms.ritsumei.ac.jp/my/", passport))
        assertNull(SsoLogin.parseTokenUrl("moodlemobile://token=abc", passport))
        assertNull(SsoLogin.parseTokenUrl("moodlemobile://token=" + encode("no-separator"), passport))
        // 署名部がmd5形式ですらない場合は拒否
        assertNull(SsoLogin.parseTokenUrl("moodlemobile://token=" + encode("bad:::aabbccddeeff00112233"), passport))
    }

    @Test
    fun `passportは毎回異なる`() {
        assertTrue(SsoLogin.newPassport() != SsoLogin.newPassport())
    }

    private fun encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
