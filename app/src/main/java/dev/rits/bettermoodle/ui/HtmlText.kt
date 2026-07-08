package dev.rits.bettermoodle.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

private val MoodleHtmlSafelist: Safelist = Safelist.basic()
    .addTags("div", "img")
    .addAttributes("img", "alt", "src")

fun htmlToPlainText(html: String?): String =
    Jsoup.parse(Jsoup.clean(html.orEmpty(), MoodleHtmlSafelist)).text().trim()

fun htmlToAnnotatedString(html: String?): AnnotatedString =
    htmlToAnnotatedString(html, onOpenUrl = null)

@Composable
fun HtmlText(
    html: String?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
) {
    val annotated = htmlToAnnotatedString(html, onOpenUrl)
    if (annotated.text.isNotBlank()) {
        Text(
            text = annotated,
            modifier = modifier,
            style = style,
            color = color,
        )
    }
}

private fun htmlToAnnotatedString(
    html: String?,
    onOpenUrl: ((String) -> Unit)?,
): AnnotatedString {
    val cleaned = Jsoup.clean(html.orEmpty(), MoodleHtmlSafelist)
    val body = Jsoup.parse(cleaned).body()
    val listener = onOpenUrl?.let {
        LinkInteractionListener { link ->
            if (link is LinkAnnotation.Url) it(link.url)
        }
    }

    val annotated = buildAnnotatedString {
        val tracker = AppendTracker()
        body.childNodes().forEach { appendNode(it, listener, tracker) }
    }
    val end = annotated.text.indexOfLast { it != '\n' }
    return if (end < 0) AnnotatedString("") else annotated.subSequence(0, end + 1)
}

private class AppendTracker {
    var lastChar: Char? = null
        private set

    fun record(text: String) {
        if (text.isNotEmpty()) lastChar = text.last()
    }

    fun record(char: Char) {
        lastChar = char
    }
}

private fun AnnotatedString.Builder.appendNode(
    node: Node,
    linkInteractionListener: LinkInteractionListener?,
    tracker: AppendTracker,
) {
    when (node) {
        is TextNode -> appendNormalizedText(node.text(), tracker)
        is Element -> appendElement(node, linkInteractionListener, tracker)
    }
}

private fun AnnotatedString.Builder.appendElement(
    element: Element,
    linkInteractionListener: LinkInteractionListener?,
    tracker: AppendTracker,
) {
    when (element.normalName()) {
        "br" -> appendLineBreak(tracker)
        "img" -> appendTextToken("[画像]", tracker)
        "li" -> {
            appendLineBreakIfNeeded(tracker)
            appendTracked("・", tracker)
            element.childNodes().forEach { appendNode(it, linkInteractionListener, tracker) }
            appendLineBreak(tracker)
        }
        "b",
        "strong",
        -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            element.childNodes().forEach { appendNode(it, linkInteractionListener, tracker) }
        }
        "a" -> {
            val href = element.attr("href").trim()
            if (href.isBlank()) {
                element.childNodes().forEach { appendNode(it, linkInteractionListener, tracker) }
            } else {
                val start = length
                withLink(
                    LinkAnnotation.Url(
                        url = href,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = Color(0xFF0B57D0),
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = linkInteractionListener,
                    ),
                ) {
                    element.childNodes().forEach { appendNode(it, linkInteractionListener, tracker) }
                    if (length == start) appendTracked(href, tracker)
                }
            }
        }
        "p",
        "div",
        "section",
        "article",
        "header",
        "footer",
        "blockquote",
        "ul",
        "ol",
        "table",
        "tbody",
        "thead",
        "tr",
        -> appendBlock(element, linkInteractionListener, tracker)
        else -> element.childNodes().forEach { appendNode(it, linkInteractionListener, tracker) }
    }
}

private fun AnnotatedString.Builder.appendBlock(
    element: Element,
    linkInteractionListener: LinkInteractionListener?,
    tracker: AppendTracker,
) {
    appendLineBreakIfNeeded(tracker)
    element.childNodes().forEach { appendNode(it, linkInteractionListener, tracker) }
    appendLineBreak(tracker)
}

private fun AnnotatedString.Builder.appendNormalizedText(text: String, tracker: AppendTracker) {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isNotEmpty()) appendTextToken(normalized, tracker)
}

private fun AnnotatedString.Builder.appendTextToken(text: String, tracker: AppendTracker) {
    if (length > 0) {
        val previous = checkNotNull(tracker.lastChar)
        if (
            previous != '\n' &&
            !previous.isWhitespace() &&
            previous != '・' &&
            text.firstOrNull()?.isSpacingPunctuation() == false
        ) {
            appendTracked(' ', tracker)
        }
    }
    appendTracked(text, tracker)
}

private fun AnnotatedString.Builder.appendLineBreakIfNeeded(tracker: AppendTracker) {
    if (length > 0 && tracker.lastChar != '\n') appendTracked('\n', tracker)
}

private fun AnnotatedString.Builder.appendLineBreak(tracker: AppendTracker) {
    if (length == 0 || tracker.lastChar != '\n') appendTracked('\n', tracker)
}

private fun AnnotatedString.Builder.appendTracked(text: String, tracker: AppendTracker) {
    append(text)
    tracker.record(text)
}

private fun AnnotatedString.Builder.appendTracked(char: Char, tracker: AppendTracker) {
    append(char)
    tracker.record(char)
}

private fun Char.isSpacingPunctuation(): Boolean =
    this in setOf(',', '.', ';', ':', '!', '?', ')', ']', '}', '・', '、', '。', '，', '．', '）', '】')
