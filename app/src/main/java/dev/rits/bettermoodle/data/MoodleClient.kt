package dev.rits.bettermoodle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Moodle Web Service (REST) クライアント。
 * 公式モバイルアプリと同じ moodle_mobile_app サービスのトークンで
 * /webservice/rest/server.php を呼び出す。
 */
class MoodleClient(
    private val siteUrl: String = SITE_URL,
    private val tokenProvider: () -> String?,
    /** invalidtoken等の認証エラー検出時に呼ばれる (ログアウト→ログイン画面遷移に使う) */
    private val onAuthError: () -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** ファイル取得用にトークン付きURLを組み立てる (pluginfile.php等) */
    fun authedUrl(fileUrl: String): String? {
        val token = tokenProvider() ?: return null
        return UrlPolicy.appendMoodleToken(fileUrl, token)
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun call(wsFunction: String, params: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val token = tokenProvider() ?: throw MoodleWsException("notoken", "未ログインです")
            val body = FormBody.Builder().apply {
                add("wstoken", token)
                add("wsfunction", wsFunction)
                add("moodlewsrestformat", "json")
                params.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("$siteUrl/webservice/rest/server.php")
                .post(body)
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw MoodleHttpException(resp.code)
                val text = resp.body?.string() ?: throw IOException("空のレスポンス")
                val element = runCatching { json.parseToJsonElement(text) }
                    .getOrElse { throw MoodleResponseParseException() }
                // Moodleはエラーも HTTP 200 の {"exception": ...} で返す
                if (element is kotlinx.serialization.json.JsonObject && "exception" in element.jsonObject) {
                    val err = json.decodeFromJsonElement(WsError.serializer(), element)
                    if (err.errorcode in AUTH_ERROR_CODES) onAuthError()
                    throw MoodleWsException(err.errorcode, err.message ?: "Moodle WSエラー")
                }
                element
            }
        }

    suspend inline fun <reified T> callAs(wsFunction: String, params: Map<String, String> = emptyMap()): T =
        json.decodeFromJsonElement(call(wsFunction, params))

    suspend fun uploadFile(file: File, filename: String): List<UploadedFile> =
        withContext(Dispatchers.IO) {
            val token = tokenProvider() ?: throw MoodleWsException("notoken", "Not logged in")
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("token", token)
                .addFormDataPart("filearea", "draft")
                .addFormDataPart("itemid", "0")
                .addFormDataPart("filepath", "/")
                .addFormDataPart(
                    "file",
                    filename,
                    file.asRequestBody("application/octet-stream".toMediaTypeOrNull()),
                )
                .build()
            val request = Request.Builder()
                .url("$siteUrl/webservice/upload.php")
                .post(body)
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw MoodleHttpException(resp.code)
                val text = resp.body?.string() ?: throw IOException("Empty response")
                val element = runCatching { json.parseToJsonElement(text) }
                    .getOrElse { throw MoodleResponseParseException() }
                if (element is kotlinx.serialization.json.JsonObject && "exception" in element.jsonObject) {
                    val err = json.decodeFromJsonElement(WsError.serializer(), element)
                    if (err.errorcode in AUTH_ERROR_CODES) onAuthError()
                    throw MoodleWsException(err.errorcode, err.message ?: "Moodle WS error")
                }
                json.decodeFromJsonElement(element)
            }
        }

    companion object {
        const val SITE_URL = "https://lms.ritsumei.ac.jp"
        const val LAUNCH_URL = "$SITE_URL/admin/tool/mobile/launch.php"
        const val URL_SCHEME = "bettermoodle"

        // Keep this narrow: network failures, 5xx, parse errors, and access-policy
        // errors must not clear the saved Moodle token.
        val AUTH_ERROR_CODES = setOf(
            "invalidtoken",
        )
    }
}
