package dev.rits.bettermoodle.data

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

enum class PageLoadErrorKind {
    InvalidPageTarget,
    InvalidParameter,
    InvalidRecord,
    AccessDenied,
    FunctionUnavailable,
    Network,
    ResponseParse,
    Other,
}

data class PageTarget(
    val courseId: Long,
    val courseModuleId: Long,
    val contextId: Long?,
    val modName: String,
    val url: String?,
) {
    val isValid: Boolean
        get() = modName == "page" && courseId > 0L && courseModuleId > 0L
}

fun CourseModule.toPageTarget(courseId: Long): PageTarget =
    PageTarget(
        courseId = courseId,
        courseModuleId = id,
        contextId = contextId.takeIf { it > 0L },
        modName = modName,
        url = url,
    )

fun classifyPageLoadError(error: Throwable): PageLoadErrorKind {
    val code = (error as? MoodleWsException)?.errorCode?.lowercase()
    return when (code) {
        "invalidparameter" -> PageLoadErrorKind.InvalidParameter
        "invalidrecord" -> PageLoadErrorKind.InvalidRecord
        "accessexception" -> PageLoadErrorKind.AccessDenied
        "servicenotavailable",
        "functionnotfound",
        "invalidfunction",
        "disabledfunction",
        "missingfunction",
        -> PageLoadErrorKind.FunctionUnavailable
        null -> when (error) {
            is MoodleResponseParseException -> PageLoadErrorKind.ResponseParse
            is MoodleHttpException -> PageLoadErrorKind.Network
            is java.io.IOException -> PageLoadErrorKind.Network
            else -> PageLoadErrorKind.Other
        }
        else -> PageLoadErrorKind.Other
    }
}

fun pageLoadErrorMessage(kind: PageLoadErrorKind): String =
    when (kind) {
        PageLoadErrorKind.FunctionUnavailable,
        PageLoadErrorKind.AccessDenied,
        -> "このMoodle環境ではページの閲覧APIを利用できません。Moodle画面で確認してください。"
        else -> "ページを読み込めませんでした。再試行してください。"
    }

fun pageHttpStatus(error: Throwable): Int? =
    (error as? MoodleHttpException)?.status

fun pageMoodleErrorCode(error: Throwable): String? =
    (error as? MoodleWsException)?.errorCode

fun PageLoadErrorKind.canFallbackToWebView(): Boolean =
    this == PageLoadErrorKind.FunctionUnavailable || this == PageLoadErrorKind.AccessDenied

fun PageLoadErrorKind.diagnosticCode(): String =
    when (this) {
        PageLoadErrorKind.InvalidPageTarget -> "INVALID_PAGE_TARGET"
        PageLoadErrorKind.InvalidParameter -> "INVALID_PARAMETER"
        PageLoadErrorKind.InvalidRecord -> "INVALID_RECORD"
        PageLoadErrorKind.AccessDenied -> "ACCESS_DENIED"
        PageLoadErrorKind.FunctionUnavailable -> "FUNCTION_UNAVAILABLE"
        PageLoadErrorKind.Network -> "NETWORK"
        PageLoadErrorKind.ResponseParse -> "RESPONSE_PARSE"
        PageLoadErrorKind.Other -> "UNKNOWN"
    }

/** ページ本文をテキスト(HTML断片)と画像に分割した表示ブロック */
sealed interface PageBlock {
    data class Html(val html: String) : PageBlock
    data class Image(val src: String) : PageBlock
}

private val IMG_MARKER = Regex("@@BM_IMG_(\\d+)@@")

/**
 * ページ本文HTMLを、テキストブロックと <img> の並びに分割する。
 * img要素を一意のマーカーに置換してから位置で切るため、画像の出現順序が保たれる。
 * 分割位置でタグが欠けても、描画側 (HtmlText) がJsoupで寛容にパースするので問題ない。
 */
fun splitHtmlToBlocks(html: String?): List<PageBlock> {
    if (html.isNullOrBlank()) return emptyList()
    val body = Jsoup.parse(html).body()
    val srcs = mutableListOf<String>()
    body.select("img").forEach { img ->
        val marker = "@@BM_IMG_${srcs.size}@@"
        srcs += img.attr("src").trim()
        img.replaceWith(TextNode(marker))
    }
    val bodyHtml = body.html()
    val blocks = mutableListOf<PageBlock>()
    var lastIndex = 0
    for (match in IMG_MARKER.findAll(bodyHtml)) {
        val before = bodyHtml.substring(lastIndex, match.range.first)
        if (hasVisibleText(before)) blocks += PageBlock.Html(before)
        val src = match.groupValues[1].toIntOrNull()?.let { srcs.getOrNull(it) }.orEmpty()
        blocks += PageBlock.Image(src)
        lastIndex = match.range.last + 1
    }
    val tail = bodyHtml.substring(lastIndex)
    if (hasVisibleText(tail)) blocks += PageBlock.Html(tail)
    return blocks
}

private fun hasVisibleText(htmlFragment: String): Boolean =
    htmlFragment.isNotBlank() && Jsoup.parse(htmlFragment).text().isNotBlank()

/** pluginfile URLのパス末尾セグメントをデコードしてファイル名として返す */
fun pluginFileNameFromUrl(url: String): String {
    val path = runCatching { java.net.URI(url.trim()).rawPath }.getOrNull().orEmpty()
    val raw = path.trimEnd('/').substringAfterLast('/')
    if (raw.isBlank()) return "ファイル"
    return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
