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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** シラバス検索結果 (Salesforce R_Syllabus__c) */
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
    /** コース名先頭の5桁授業コード */
    val courseCode: String?
        get() = Regex("^(\\d{5})[:：]").find(courseName)?.groupValues?.get(1)
}

/**
 * 立命館シラバス (syllabus.ritsumei.ac.jp, Salesforce Experience Cloud/Aura) の直接API。
 *
 * ゲスト (Cookieなし) であれば aura.token="null"・任意のfwuidで
 * POST /syllabus/s/sfsites/aura の ApexActionController 経由で
 * R_SyllabusPublicPageController.getSyllabusRecords を呼び出せることを確認済み。
 * (Cookieを持つブラウザセッションではCSRFトークンが要求されるため、
 *  このクライアントは意図的にCookieを保持しない)
 */
class SyllabusRepository(
    private val store: SessionStore,
    private val fallbackResolverUrl: String = FALLBACK_RESOLVER,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * キーワード検索 (科目名・授業コード等)。
     * 年度は4月始まりの学年暦で解釈する。
     */
    suspend fun search(
        keyword: String,
        year: Int = academicYear(),
        limits: Int = 50,
        lang: String = "ja",
    ): List<SyllabusRecord> = withContext(Dispatchers.IO) {
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

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val raw = resp.body?.string() ?: return@use emptyList()
            // Aura応答は "*/{...}/*ERROR*/" のようなガード付きの場合がある
            val start = raw.indexOf('{')
            if (start < 0) return@use emptyList()
            val root = runCatching { json.parseToJsonElement(cleanAuraJson(raw.substring(start))) }
                .getOrNull()?.jsonObject ?: return@use emptyList()

            val actionResult = root["actions"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@use emptyList() // exceptionEvent等
            if (actionResult["state"]?.jsonPrimitive?.content != "SUCCESS") return@use emptyList()
            val result = actionResult["returnValue"]?.jsonObject
                ?.get("returnValue")?.jsonObject
                ?.get("result")?.jsonArray ?: return@use emptyList()
            result.mapNotNull { el ->
                runCatching { json.decodeFromJsonElement(SyllabusRecord.serializer(), el) }.getOrNull()
            }
        }
    }

    /** 授業コード(5桁) → シラバス詳細URL。直接API → 外部resolverの順に解決し、キャッシュする。 */
    suspend fun resolveUrl(courseCode: String, year: Int = academicYear()): String? {
        store.syllabusCache()[courseCode]?.let { return it }

        // 1. 直接API: コードで検索し、コース名が "コード:" で始まるレコードを採用
        val record = runCatching { search(keyword = courseCode, year = year, limits = 10) }
            .getOrDefault(emptyList())
            .firstOrNull { it.courseCode == courseCode }
        if (record != null) {
            val url = detailUrl(record.id, year, courseCode)
            store.saveSyllabusUrl(courseCode, url)
            return url
        }

        // 2. フォールバック: Moodle-Schedule-Extension作者のWorker
        return resolveViaWorker(courseCode)
    }

    /** 検索結果レコードからシラバス詳細URLを組み立てる */
    fun detailUrl(record: SyllabusRecord, year: Int = academicYear(), lang: String = "ja"): String? {
        val code = record.courseCode ?: return null
        return detailUrl(record.id, year, code, lang)
    }

    private fun detailUrl(recordId: String, year: Int, code: String, lang: String = "ja"): String =
        "$SYLLABUS_BASE/syllabus/s/r-syllabus/$recordId/$year$code?language=$lang"

    private suspend fun resolveViaWorker(courseCode: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$fallbackResolverUrl/syllabus?code=$courseCode")
            .get()
            .build()
        runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val url = resp.body?.string()?.trim()
                if (url != null && url.startsWith("https://")) {
                    store.saveSyllabusUrl(courseCode, url)
                    url
                } else null
            }
        }.getOrNull()
    }

    companion object {
        const val SYLLABUS_BASE = "https://syllabus.ritsumei.ac.jp"
        const val SYLLABUS_SEARCH_URL = "$SYLLABUS_BASE/syllabus/s/?language=ja"
        const val FALLBACK_RESOLVER = "https://withered-salad-b4aa.yudai-syllabus.workers.dev"

        // Cookieなしゲスト呼び出しではfwuidは検証されない (2026-07確認)。
        // 将来チェックが入った場合はトップページの
        // /sfsites/auraFW/javascript/{fwuid}/aura_prod.js から動的取得すること。
        const val FWUID = "cmpKNldRZXRSMkdjemxQdjBkbl9uQWtVMjdnTGFERUU2S3FfSVdrcU92bkExNC4xOTIuODM4ODYwOA"

        /** 4月始まりの学年暦 (1〜3月は前年扱い) */
        fun academicYear(today: LocalDate = LocalDate.now()): Int =
            if (today.monthValue >= 4) today.year else today.year - 1

        // Aura応答末尾のコメント形式ガード ( ERROR マーカー) を除去
        internal fun cleanAuraJson(s: String): String =
            s.removeSuffix("/*ERROR*/").trim()
    }
}
