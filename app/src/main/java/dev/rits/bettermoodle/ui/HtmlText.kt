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
        body.childNodes().forEach { appendNode(it, listener) }
    }
    val end = annotated.text.indexOfLast { it != '\n' }
    return if (end < 0) AnnotatedString("") else annotated.subSequence(0, end + 1)
}

private fun AnnotatedString.Builder.appendNode(
    node: Node,
    linkInteractionListener: LinkInteractionListener?,
) {
    when (node) {
        is TextNode -> appendNormalizedText(node.text())
        is Element -> appendElement(node, linkInteractionListener)
    }
}

private fun AnnotatedString.Builder.appendElement(
    element: Element,
    linkInteractionListener: LinkInteractionListener?,
) {
    when (element.normalName()) {
        "br" -> appendLineBreak()
        "img" -> appendTextToken("[画像]")
        "li" -> {
            appendLineBreakIfNeeded()
            append("・")
            element.childNodes().forEach { appendNode(it, linkInteractionListener) }
            appendLineBreak()
        }
        "b",
        "strong",
        -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            element.childNodes().forEach { appendNode(it, linkInteractionListener) }
        }
        "a" -> {
            val href = element.attr("href").trim()
            if (href.isBlank()) {
                element.childNodes().forEach { appendNode(it, linkInteractionListener) }
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
                    element.childNodes().forEach { appendNode(it, linkInteractionListener) }
                    if (length == start) append(href)
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
        -> appendBlock(element, linkInteractionListener)
        else -> element.childNodes().forEach { appendNode(it, linkInteractionListener) }
    }
}

private fun AnnotatedString.Builder.appendBlock(
    element: Element,
    linkInteractionListener: LinkInteractionListener?,
) {
    appendLineBreakIfNeeded()
    element.childNodes().forEach { appendNode(it, linkInteractionListener) }
    appendLineBreak()
}

private fun AnnotatedString.Builder.appendNormalizedText(text: String) {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isNotEmpty()) appendTextToken(normalized)
}

private fun AnnotatedString.Builder.appendTextToken(text: String) {
    if (length > 0) {
        val previous = currentText().last()
        if (
            previous != '\n' &&
            !previous.isWhitespace() &&
            previous != '・' &&
            text.firstOrNull()?.isSpacingPunctuation() == false
        ) {
            append(' ')
        }
    }
    append(text)
}

private fun AnnotatedString.Builder.appendLineBreakIfNeeded() {
    if (length > 0 && currentText().last() != '\n') append('\n')
}

private fun AnnotatedString.Builder.appendLineBreak() {
    if (length == 0 || currentText().last() != '\n') append('\n')
}

private fun AnnotatedString.Builder.currentText(): String = toAnnotatedString().text

private fun Char.isSpacingPunctuation(): Boolean =
    this in setOf(',', '.', ';', ':', '!', '?', ')', ']', '}', '・', '、', '。', '，', '．', '）', '】')
