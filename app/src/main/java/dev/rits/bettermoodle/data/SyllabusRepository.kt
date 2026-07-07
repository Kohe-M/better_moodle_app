package dev.rits.bettermoodle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@Serializable
data class SyllabusRecord(
    @SerialName("Id") val id: String = "",
    @SerialName("R_SlCourseName__c") val courseName: String = "",
    @SerialName("R_SlCourseNameEn__c") val courseNameEn: String? = null,
    @SerialName("R_SlPersonalName__c") val teacher: String? = null,
    @SerialName("R_SlWeekDayPeriod__c") val weekDayPeriod: String? = null,
    @SerialName("R_SlCampusInfo__c") val campus: String? = null,
    @SerialName("R_SlCredits__c") val credits: String? = null,
    @SerialName("R_SlCourseOpenPeriodName__c") val term: String? = null,
    @SerialName("R_SlDepartmentId__c") val departments: String? = null,
) {
    val courseCode: String?
        get() = Regex("^(\\d{5})[:：]").find(courseName)?.groupValues?.get(1)
}

sealed interface SyllabusSearchResult {
    data class Success(val records: List<SyllabusRecord>) : SyllabusSearchResult
    data object NoResults : SyllabusSearchResult
    data class NetworkError(val statusCode: Int? = null) : SyllabusSearchResult
    data object InvalidResponse : SyllabusSearchResult
}

class SyllabusRepository(
    private val store: SessionStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun search(
        keyword: String,
        year: Int = academicYear(),
        limits: Int = 50,
        lang: String = "ja",
    ): List<SyllabusRecord> =
        when (val result = searchResult(keyword, year, limits, lang)) {
            is SyllabusSearchResult.Success -> result.records
            else -> emptyList()
        }

    suspend fun searchResult(
        keyword: String,
        year: Int = academicYear(),
        limits: Int = 50,
        lang: String = "ja",
    ): SyllabusSearchResult = withContext(Dispatchers.IO) {
        val action = buildJsonObject {
            put("lang", lang)
            put("keyword", keyword)
            put("faculty", JsonNull)
            put("year", year.toString())
            put("term", JsonNull)
            put("week", JsonNull)
            put("period", JsonNull)
            put("professionalCareer", JsonNull)
            put("limits", limits)
        }
        val message = buildJsonObject {
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("id", "1;a")
                    put("descriptor", "aura://ApexActionController/ACTION\$execute")
                    put("callingDescriptor", "UNKNOWN")
                    put("params", buildJsonObject {
                        put("namespace", "")
                        put("classname", "R_SyllabusPublicPageController")
                        put("method", "getSyllabusRecords")
                        put("params", buildJsonObject { put("action", action) })
                        put("cacheable", false)
                        put("isContinuation", false)
                    })
                })
            })
        }
        val auraContext = buildJsonObject {
            put("mode", "PROD")
            put("fwuid", FWUID)
            put("app", "siteforce:communityApp")
            put("loaded", buildJsonObject {})
            put("dn", buildJsonArray {})
            put("globals", buildJsonObject {})
            put("uad", true)
        }
        val body = FormBody.Builder()
            .add("message", message.toString())
            .add("aura.context", auraContext.toString())
            .add("aura.pageURI", "/syllabus/s/")
            .add("aura.token", "null")
            .build()
        val request = Request.Builder()
            .url("$SYLLABUS_BASE/syllabus/s/sfsites/aura?r=1&aura.ApexAction.execute=1")
            .post(body)
            .header("Referer", "$SYLLABUS_BASE/syllabus/s/")
            .build()

        runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext SyllabusSearchResult.NetworkError(resp.code)
                val raw = resp.body?.string() ?: return@withContext SyllabusSearchResult.InvalidResponse
                parseRecords(raw)
            }
        }.getOrElse { SyllabusSearchResult.NetworkError() }
    }

    suspend fun resolveUrl(courseCode: String, year: Int = academicYear()): String? {
        val cacheKey = store.syllabusCacheKey(courseCode, year)
        store.syllabusCache()[cacheKey]?.let { return it }

        val result = searchResult(keyword = courseCode, year = year, limits = 10)
        val record = (result as? SyllabusSearchResult.Success)
            ?.records
            ?.firstOrNull { it.courseCode == courseCode }
            ?: return null
        val url = detailUrl(record.id, year, courseCode)
        store.saveSyllabusUrl(courseCode, year, url)
        return url
    }

    fun detailUrl(record: SyllabusRecord, year: Int = academicYear(), lang: String = "ja"): String? {
        val code = record.courseCode ?: return null
        return detailUrl(record.id, year, code, lang)
    }

    private fun detailUrl(recordId: String, year: Int, code: String, lang: String = "ja"): String =
        "$SYLLABUS_BASE/syllabus/s/r-syllabus/$recordId/$year$code?language=$lang"

    private fun parseRecords(raw: String): SyllabusSearchResult {
        val start = raw.indexOf('{')
        if (start < 0) return SyllabusSearchResult.InvalidResponse
        val root = runCatching { json.parseToJsonElement(cleanAuraJson(raw.substring(start))) }
            .getOrNull()
            ?.jsonObject
            ?: return SyllabusSearchResult.InvalidResponse

        val actionResult = root["actions"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return SyllabusSearchResult.InvalidResponse
        if (actionResult["state"]?.jsonPrimitive?.content != "SUCCESS") {
            return SyllabusSearchResult.InvalidResponse
        }
        val result = actionResult["returnValue"]?.jsonObject
            ?.get("returnValue")?.jsonObject
            ?.get("result")?.jsonArray
            ?: return SyllabusSearchResult.InvalidResponse
        val records = result.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(SyllabusRecord.serializer(), el) }.getOrNull()
        }
        return if (records.isEmpty()) SyllabusSearchResult.NoResults else SyllabusSearchResult.Success(records)
    }

    companion object {
        const val SYLLABUS_BASE = "https://syllabus.ritsumei.ac.jp"
        const val SYLLABUS_SEARCH_URL = "$SYLLABUS_BASE/syllabus/s/?language=ja"

        object AuraConfig {
            const val FWUID =
                "cmpKNldRZXRSMkdjemxQdjBkbl9uQWtVMjdnTGFERUU2S3FfSVdrcU92bkExNC4xOTIuODM4ODYwOA"
        }

        val FWUID: String
            get() = AuraConfig.FWUID

        fun academicYear(today: LocalDate = LocalDate.now()): Int =
            if (today.monthValue >= 4) today.year else today.year - 1

        internal fun cleanAuraJson(s: String): String =
            s.removeSuffix("/*ERROR*/").trim()
    }
}
