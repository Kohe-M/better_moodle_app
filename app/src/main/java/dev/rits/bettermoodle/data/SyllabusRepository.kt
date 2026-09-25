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
import kotlinx.coroutines.CancellationException
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

/**
 * 授業コード→シラバスURLの解決結果。通信失敗・仕様変更を「見つからない」と
 * 混同しないよう、失敗理由を保持する。
 */
sealed interface SyllabusResolveResult {
    data class Found(val url: String, val fromCache: Boolean) : SyllabusResolveResult
    data object NotFound : SyllabusResolveResult
    data class NetworkError(val statusCode: Int? = null) : SyllabusResolveResult
    data object InvalidResponse : SyllabusResolveResult

    val urlOrNull: String? get() = (this as? Found)?.url

    /** 利用者向けの失敗理由。Found なら null */
    val failureMessage: String?
        get() = when (this) {
            is Found -> null
            NotFound -> "該当するシラバスが見つかりませんでした"
            is NetworkError -> "シラバス検索の通信に失敗しました" + (statusCode?.let { " (HTTP $it)" } ?: "")
            InvalidResponse -> "シラバス検索の応答形式が変わった可能性があります"
        }
}

/** 授業コード→URL のキャッシュ。SessionStore が実装し、テストではメモリ実装に差し替える。 */
interface SyllabusUrlCache {
    suspend fun syllabusCache(): Map<String, String>
    suspend fun saveSyllabusUrl(code: String, year: Int, url: String)
    fun syllabusCacheKey(code: String, year: Int): String = "$year:$code"

    /** 最後に確認できた Aura の fwuid (Salesforce の更新で変わる。G05) */
    suspend fun auraFwuid(): String? = null
    suspend fun saveAuraFwuid(fwuid: String) {}
}

class SyllabusRepository(
    private val store: SyllabusUrlCache,
    private val baseUrl: String = SYLLABUS_BASE,
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
        val fwuid = currentFwuid()
        val (result, raw) = executeSearch(keyword, year, limits, lang, fwuid)
        // Salesforce のリリースで fwuid が変わると clientOutOfSync になる。新しい値を取得して1回だけ再試行する (G05)
        if (result !is SyllabusSearchResult.InvalidResponse || raw == null || !isClientOutOfSync(raw)) {
            return@withContext result
        }
        val fresh = extractFwuid(raw)?.takeIf { it != fwuid } ?: fetchFwuidFromPage()?.takeIf { it != fwuid }
            ?: return@withContext result
        knownFwuid = fresh
        try {
            store.saveAuraFwuid(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 保存できなくてもこのプロセスではメモリの値を使う
        }
        executeSearch(keyword, year, limits, lang, fresh).first
    }

    @Volatile private var knownFwuid: String? = null

    private suspend fun currentFwuid(): String =
        knownFwuid ?: try {
            store.auraFwuid()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() }?.also { knownFwuid = it } ?: FWUID

    /** 検索ページの HTML から現在の fwuid を読む。取れなければ null */
    private suspend fun fetchFwuidFromPage(): String? = try {
        http.newCall(Request.Builder().url("$baseUrl/syllabus/s/").build()).useResponse { resp ->
            if (!resp.isSuccessful) return@useResponse null
            resp.body?.string()?.let(::extractFwuid)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** 検索を1回実行する。応答本文 (解釈できた場合) も返す */
    private suspend fun executeSearch(
        keyword: String,
        year: Int,
        limits: Int,
        lang: String,
        fwuid: String,
    ): Pair<SyllabusSearchResult, String?> {
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
            put("fwuid", fwuid)
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
            .url("$baseUrl/syllabus/s/sfsites/aura?r=1&aura.ApexAction.execute=1")
            .post(body)
            .header("Referer", "$baseUrl/syllabus/s/")
            .build()

        return try {
            // 画面離脱でキャンセルできるよう、ヘッダ待ちと本文読み取りの両方を中断可能にする
            http.newCall(request).useResponse { resp ->
                if (!resp.isSuccessful) return@useResponse SyllabusSearchResult.NetworkError(resp.code) to null
                val raw = resp.body?.string() ?: return@useResponse SyllabusSearchResult.InvalidResponse to null
                parseRecords(raw) to raw
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            SyllabusSearchResult.NetworkError() to null
        } catch (_: Exception) {
            // 通信は成功したが解釈中に例外 → 通信失敗ではなく応答形式の問題
            SyllabusSearchResult.InvalidResponse to null
        }
    }

    /** 後方互換: 失敗理由を区別しない呼び出し。新規コードは [resolve] を使う。 */
    suspend fun resolveUrl(courseCode: String, year: Int = academicYear()): String? =
        resolve(courseCode, year).urlOrNull

    /** 授業コードからシラバスURLを解決する。キャッシュ→検索の順。 */
    suspend fun resolve(courseCode: String, year: Int = academicYear()): SyllabusResolveResult {
        val cacheKey = store.syllabusCacheKey(courseCode, year)
        store.syllabusCache()[cacheKey]?.let { return SyllabusResolveResult.Found(it, fromCache = true) }

        val record = when (val result = searchResult(keyword = courseCode, year = year, limits = 10)) {
            is SyllabusSearchResult.Success -> result.records.firstOrNull { it.courseCode == courseCode }
                ?: return SyllabusResolveResult.NotFound
            SyllabusSearchResult.NoResults -> return SyllabusResolveResult.NotFound
            is SyllabusSearchResult.NetworkError -> return SyllabusResolveResult.NetworkError(result.statusCode)
            SyllabusSearchResult.InvalidResponse -> return SyllabusResolveResult.InvalidResponse
        }
        val url = detailUrl(record.id, year, courseCode)
        store.saveSyllabusUrl(courseCode, year, url)
        return SyllabusResolveResult.Found(url, fromCache = false)
    }

    fun detailUrl(record: SyllabusRecord, year: Int = academicYear(), lang: String = "ja"): String? {
        val code = record.courseCode ?: return null
        return detailUrl(record.id, year, code, lang)
    }

    private fun detailUrl(recordId: String, year: Int, code: String, lang: String = "ja"): String =
        "$baseUrl/syllabus/s/r-syllabus/$recordId/$year$code?language=$lang"

    /**
     * Aura 応答の解釈。契約外の構造 (actions が無い/配列でない・state が SUCCESS でない・
     * result が配列でない・レコードが1件も解釈できない) は InvalidResponse。
     * 本当の 0 件 (result が空配列) だけを NoResults にする。jsonObject/jsonArray の
     * キャスト失敗も通信失敗ではなく InvalidResponse として扱う。
     */
    internal fun parseRecords(raw: String): SyllabusSearchResult {
        val start = raw.indexOf('{')
        if (start < 0) return SyllabusSearchResult.InvalidResponse
        return try {
            val root = json.parseToJsonElement(cleanAuraJson(raw.substring(start))).jsonObject
            val actions = root["actions"]?.jsonArray ?: return SyllabusSearchResult.InvalidResponse
            val actionResult = actions.firstOrNull()?.jsonObject ?: return SyllabusSearchResult.InvalidResponse
            if (actionResult["state"]?.jsonPrimitive?.content != "SUCCESS") {
                return SyllabusSearchResult.InvalidResponse
            }
            val result = actionResult["returnValue"]?.jsonObject
                ?.get("returnValue")?.jsonObject
                ?.get("result")?.jsonArray
                ?: return SyllabusSearchResult.InvalidResponse
            if (result.isEmpty()) return SyllabusSearchResult.NoResults
            val records = result.mapNotNull { el ->
                runCatching { json.decodeFromJsonElement(SyllabusRecord.serializer(), el) }
                    .getOrNull()
                    ?.takeIf { it.id.isNotBlank() }
            }
            // 要素はあるのに1件も解釈できないのは 0 件ではなく仕様変更の疑い
            if (records.isEmpty()) SyllabusSearchResult.InvalidResponse else SyllabusSearchResult.Success(records)
        } catch (_: IllegalArgumentException) {
            SyllabusSearchResult.InvalidResponse
        } catch (_: kotlinx.serialization.SerializationException) {
            SyllabusSearchResult.InvalidResponse
        }
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

        private val FWUID_JSON_REGEX = Regex(""""fwuid"\s*:\s*"([A-Za-z0-9_\-.]{10,})"""")
        private val FWUID_SCRIPT_REGEX = Regex("""/auraFW/javascript/([A-Za-z0-9_\-.]{10,})/""")

        /** Aura の「クライアントの版が古い」応答か */
        internal fun isClientOutOfSync(raw: String): Boolean = "clientOutOfSync" in raw

        /** Aura 応答の context や検索ページの HTML から fwuid を取り出す */
        internal fun extractFwuid(text: String): String? =
            FWUID_JSON_REGEX.find(text)?.groupValues?.get(1)
                ?: FWUID_SCRIPT_REGEX.find(text)?.groupValues?.get(1)
    }
}
