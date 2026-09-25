package dev.rits.bettermoodle.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Moodle Web Service (REST) クライアント。
 * 公式モバイルアプリと同じ moodle_mobile_app サービスのトークンで
 * /webservice/rest/server.php を呼び出す。
 *
 * すべての通信は [Call.useResponse] 経由で、ヘッダ待ち・本文読み取りのどちらでも
 * コルーチンのキャンセルで中断できる。
 */
class MoodleClient(
    private val siteUrl: String = SITE_URL,
    private val tokenProvider: () -> String?,
    private val privateTokenProvider: suspend () -> String? = { null },
    /**
     * invalidtoken等の認証エラー検出時に、失敗したリクエストで使ったトークンと共に呼ばれる。
     * 呼び出し側は「現在のトークンと一致する場合だけ」認証を消去する (旧セッションの遅延エラーで
     * 新しいログインを消さないため)。
     */
    private val onAuthError: (failedToken: String) -> Unit = {},
    httpClient: OkHttpClient? = null,
) {
    private val http = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** ファイルURLの許可ホスト。siteUrl から導出する (本番は lms.ritsumei.ac.jp) */
    val siteHost: String = runCatching { URI(siteUrl).host }.getOrNull() ?: UrlPolicy.MOODLE_HOST

    /** ファイル取得用にトークン付きURLを組み立てる (pluginfile.php等) */
    fun authedUrl(fileUrl: String): String? {
        val token = tokenProvider() ?: return null
        return UrlPolicy.appendMoodleToken(fileUrl, token, siteHost)
    }

    suspend fun privateToken(): String? = privateTokenProvider()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 読み取り系WS関数の呼び出し。応答は JSON (配列/オブジェクト)。
     * 本文が `null` の場合も [JsonNull] として返す (呼び出し側の型デコードで弾かれる)。
     */
    suspend fun call(
        wsFunction: String,
        params: Map<String, String> = emptyMap(),
        /** セッションに束縛した要求のために、呼び出し時点ではなく予約時点のトークンを使う */
        tokenOverride: String? = null,
    ): JsonElement {
        val token = tokenOverride ?: tokenProvider() ?: throw MoodleWsException("notoken", "未ログインです")
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

        return http.newCall(request).useResponse { resp -> parseWsResponse(resp, token) }
    }

    suspend inline fun <reified T> callAs(wsFunction: String, params: Map<String, String> = emptyMap()): T =
        decodeStrict(call(wsFunction, params))

    /** 型に合わない応答 (null・想定外の構造) は応答形式エラーとして扱う */
    inline fun <reified T> decodeStrict(element: JsonElement): T =
        try {
            json.decodeFromJsonElement(element)
        } catch (e: SerializationException) {
            throw MoodleResponseParseException(e.message)
        } catch (e: IllegalArgumentException) {
            throw MoodleResponseParseException(e.message)
        }

    /**
     * 書き込み系WS関数のうち「warning 配列」を返す関数 (mod_assign_save_submission 等) の呼び出し。
     * Moodle は成功時にも warnings で「保存されなかった理由」を返すため、応答を捨てずに返す。
     * 配列以外・要素の形式不正は成功扱いにせず [MoodleResponseParseException] にする。
     */
    suspend fun callForWarningList(wsFunction: String, params: Map<String, String> = emptyMap()): List<WsWarning> {
        val element = call(wsFunction, params)
        if (element !is JsonArray) throw MoodleResponseParseException("expected warning array from $wsFunction")
        return decodeWarnings(element)
    }

    /** JSON 配列を warning のリストにデコードする。要素の形式不正は応答形式エラー。 */
    fun decodeWarnings(element: JsonElement): List<WsWarning> {
        if (element !is JsonArray) throw MoodleResponseParseException("warnings is not an array")
        return element.map { item ->
            if (item !is JsonObject) throw MoodleResponseParseException("warning entry is not an object")
            decodeStrict<WsWarning>(item)
        }
    }

    /**
     * webservice/upload.php にファイルを1件アップロードする。
     * [draftItemId] が null なら新しいdraft領域を作り、指定があればその領域に追加する。
     * [filepath] はdraft内のディレクトリ (既存提出物のサブディレクトリを維持するために使う)。
     */
    suspend fun uploadFile(
        file: File,
        filename: String,
        draftItemId: Long? = null,
        filepath: String = "/",
    ): List<UploadedFile> {
        val token = tokenProvider() ?: throw MoodleWsException("notoken", "未ログインです")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("token", token)
            .addFormDataPart("filearea", "draft")
            .addFormDataPart("itemid", (draftItemId ?: 0L).toString())
            .addFormDataPart("filepath", normalizeDraftPath(filepath))
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
        return http.newCall(request).useResponse { resp ->
            val element = parseWsResponse(resp, token)
            if (element !is JsonArray) throw MoodleResponseParseException("upload.php did not return a file list")
            decodeStrict(element)
        }
    }

    /** 取得したファイルの情報 (draft引き継ぎ時の検証用) */
    data class Downloaded(val bytes: Long, val contentType: String?)

    /**
     * トークン付きURLからファイルを [dest] に保存する (提出済みファイルのdraft引き継ぎ用)。
     * 途中でキャンセル・失敗した場合は [dest] を削除する。
     *
     * - pluginfile 以外へのリダイレクト (ログインページ等) は拒否する
     * - text/html 応答は、提出物自体が HTML ([allowHtml]) のときだけ受け入れる
     */
    suspend fun downloadToFile(
        fileUrl: String,
        dest: File,
        maxBytes: Long = Long.MAX_VALUE,
        allowHtml: Boolean = false,
    ): Downloaded {
        if (tokenProvider() == null) throw MoodleWsException("notoken", "未ログインです")
        // authedUrl は pluginfile 以外・秘密パラメータ付きURLを拒否する
        val authed = authedUrl(fileUrl) ?: throw IOException("許可されていないファイルURLです。")
        try {
            return http.newCall(Request.Builder().url(authed).build()).useResponse { resp ->
                if (!resp.isSuccessful) throw MoodleHttpException(resp.code)
                val finalPath = resp.request.url.encodedPath
                if (resp.priorResponse != null && !finalPath.contains("pluginfile.php")) {
                    throw IOException("ファイルの代わりに別のページ ($finalPath) へリダイレクトされました。")
                }
                val body = resp.body ?: throw IOException("空のレスポンス")
                val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (contentType == "text/html" && !allowHtml) throw IOException("ファイルの代わりにHTMLが返されました。")
                var copied = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > maxBytes) throw IOException("ファイルサイズが上限を超えています。")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                Downloaded(copied, contentType)
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    private fun parseWsResponse(resp: okhttp3.Response, token: String): JsonElement {
        if (!resp.isSuccessful) throw MoodleHttpException(resp.code)
        val text = resp.body?.string() ?: throw IOException("空のレスポンス")
        // 一部の関数は本文 "null" を返す。型付きデコードや配列期待の呼び出しでは弾かれる
        if (text.isBlank() || text.trim() == "null") return JsonNull
        val element = runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw MoodleResponseParseException("invalid JSON") }
        // Moodleはエラーも HTTP 200 の {"exception": ...} で返す
        if (element is JsonObject && ("exception" in element.jsonObject || "errorcode" in element.jsonObject)) {
            val err = json.decodeFromJsonElement(WsError.serializer(), element)
            if (err.errorcode in AUTH_ERROR_CODES) onAuthError(token)
            throw MoodleWsException(err.errorcode, err.message ?: err.error ?: "Moodle WSエラー")
        }
        return element
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

        /** draft内パスを "/dir/" 形式に揃える */
        fun normalizeDraftPath(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isBlank() || trimmed == "/") return "/"
            val withLeading = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return if (withLeading.endsWith("/")) withLeading else "$withLeading/"
        }
    }
}
