package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.SyllabusRepository
import dev.rits.bettermoodle.data.SyllabusSearchResult
import dev.rits.bettermoodle.data.SyllabusUrlCache
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** G05: Salesforce の更新で fwuid が変わってもシラバス検索が自動で復旧する */
class SyllabusFwuidTest {

    private val server = MockWebServer().also { it.start() }

    @After
    fun tearDown() = server.shutdown()

    private class Cache : SyllabusUrlCache {
        var fwuid: String? = null
        override suspend fun syllabusCache(): Map<String, String> = emptyMap()
        override suspend fun saveSyllabusUrl(code: String, year: Int, url: String) = Unit
        override suspend fun auraFwuid(): String? = fwuid
        override suspend fun saveAuraFwuid(fwuid: String) { this.fwuid = fwuid }
    }

    private val outOfSync =
        """*/{"event":{"descriptor":"markup://aura:clientOutOfSync","attributes":{"values":{}}},"exceptionEvent":true}/*ERROR*/"""

    private val success = """
        {"actions":[{"id":"1;a","state":"SUCCESS","returnValue":{"returnValue":{"result":[
          {"Id":"a0i1","R_SlCourseName__c":"12345:情報科学"}
        ]}}}],"context":{"fwuid":"NEWFWUID_abcdefghijk"}}
    """.trimIndent()

    private fun sentFwuid(body: String): String {
        val context = body.split('&').first { it.startsWith("aura.context=") }.removePrefix("aura.context=")
        return Regex(""""fwuid":"([^"]+)"""").find(URLDecoder.decode(context, "UTF-8"))!!.groupValues[1]
    }

    @Test
    fun `out of sync response refreshes fwuid from the page and retries once`() = runBlocking {
        val cache = Cache()
        server.enqueue(MockResponse().setBody(outOfSync))
        server.enqueue(MockResponse().setBody("""<script src="/syllabus/s/sfsites/auraFW/javascript/NEWFWUID_abcdefghijk/aura_prod.js"></script>"""))
        server.enqueue(MockResponse().setBody(success))
        val repo = SyllabusRepository(cache, baseUrl = server.url("/").toString().removeSuffix("/"))

        val result = repo.searchResult("12345", year = 2026)

        assertTrue(result.toString(), result is SyllabusSearchResult.Success)
        assertEquals(SyllabusRepository.FWUID, sentFwuid(server.takeRequest().body.readUtf8()))
        assertEquals("/syllabus/s/", server.takeRequest().path)
        assertEquals("NEWFWUID_abcdefghijk", sentFwuid(server.takeRequest().body.readUtf8()))
        assertEquals("NEWFWUID_abcdefghijk", cache.fwuid)
    }

    @Test
    fun `saved fwuid is used for the next search`() = runBlocking {
        val cache = Cache().apply { fwuid = "SAVEDFWUID_0123456789" }
        server.enqueue(MockResponse().setBody(success))
        val repo = SyllabusRepository(cache, baseUrl = server.url("/").toString().removeSuffix("/"))

        repo.searchResult("12345", year = 2026)

        assertEquals("SAVEDFWUID_0123456789", sentFwuid(server.takeRequest().body.readUtf8()))
    }

    @Test
    fun `retry happens at most once`() = runBlocking {
        val cache = Cache()
        server.enqueue(MockResponse().setBody(outOfSync))
        server.enqueue(MockResponse().setBody("""{"fwuid":"OTHERFWUID_0123456789"}"""))
        server.enqueue(MockResponse().setBody(outOfSync))
        val repo = SyllabusRepository(cache, baseUrl = server.url("/").toString().removeSuffix("/"))

        val result = repo.searchResult("12345", year = 2026)

        assertEquals(SyllabusSearchResult.InvalidResponse, result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `fwuid is extracted from response context and script urls`() {
        assertEquals("abcDEF123_-.xyz", SyllabusRepository.extractFwuid("""{"context":{"fwuid":"abcDEF123_-.xyz"}}"""))
        assertEquals("abcDEF1234567", SyllabusRepository.extractFwuid("/s/sfsites/auraFW/javascript/abcDEF1234567/aura_prod.js"))
    }
}
