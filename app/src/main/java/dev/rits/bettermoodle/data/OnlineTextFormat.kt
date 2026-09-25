package dev.rits.bettermoodle.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * オンラインテキスト提出のプレーンテキスト入力と Moodle 側 HTML (format=1) の相互変換。
 *
 * 契約:
 * - 入力欄はプレーンテキスト。保存時は `<`, `>`, `&`, `"` をエスケープし、
 *   空行で段落 (`<p>`)、単独の改行を `<br />` に変換する
 * - 行末の空白は落とし、3行以上の連続空行は1つの空行に畳む。行内の連続空白・タブは
 *   そのまま保存する (HTML表示では畳まれるが、保存内容としては保持される)
 * - 既存HTMLは「段落・改行だけで、属性を持たない」場合のみ編集用テキストへ可逆変換できる。
 *   それ以外 (太字・色・配置・class・画像・表・リンク等) は [isRichHtml] で判定して Web 編集へ誘導し、
 *   プレーン編集で書式を落とさない
 */
object OnlineTextFormat {

    /** 編集用テキストへ可逆変換できるタグ (属性なしに限る) */
    private val plainTags = setOf("p", "br", "div", "body", "html", "head", "span")

    /** 段落/改行だけで構成されていない (可逆変換できない) HTMLか。属性付き要素も書式ありとみなす。 */
    fun isRichHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val doc = Jsoup.parseBodyFragment(html)
        return doc.body().allElements.any { el ->
            el.normalName() !in plainTags || (el.normalName() !in setOf("body", "html", "head") && el.attributes().size() > 0)
        }
    }

    /** 既存HTMLを編集欄用のプレーンテキストに変換する。段落は空行、`<br>` は改行にする。 */
    fun htmlToEditableText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        val doc: Document = Jsoup.parseBodyFragment(html)
        val sb = StringBuilder()
        appendEditable(doc.body(), sb)
        return normalizeEditableText(sb.toString())
    }

    /** 入力テキストの正規化 (保存時と同じ規則)。保存後に dirty が残らないよう、草稿もこれで揃える。 */
    fun normalizeEditableText(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .lines()
            .joinToString("\n") { line -> line.trimEnd().ifBlank { "" } }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim('\n')

    /** プレーンテキストを Moodle へ保存する HTML に変換する。 */
    fun plainTextToHtml(text: String): String {
        val normalized = normalizeEditableText(text)
        if (normalized.isBlank()) return ""
        return normalized.split("\n\n")
            .joinToString("") { paragraph ->
                "<p>" + paragraph.split('\n').joinToString("<br />") { escape(it) } + "</p>"
            }
    }

    /**
     * 保存内容の比較用。HTML→編集テキストの可逆変換を通した結果を厳密に比較する
     * (空白をすべて畳むと意味のある差まで隠れるため、行末空白と連続空行の正規化だけにとどめる)。
     */
    fun editableTextOf(html: String?): String = htmlToEditableText(html)

    private fun escape(s: String): String =
        buildString(s.length) {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }

    private fun appendEditable(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.wholeText.replace(Regex("[\\n\\r]+"), " ").replace(" ", " "))
            is Element -> when (node.normalName()) {
                "br" -> sb.append('\n')
                "p", "div" -> {
                    if (sb.isNotEmpty() && !sb.endsWith("\n\n")) {
                        if (sb.endsWith("\n")) sb.append('\n') else sb.append("\n\n")
                    }
                    node.childNodes().forEach { appendEditable(it, sb) }
                    if (!sb.endsWith("\n\n")) {
                        if (sb.endsWith("\n")) sb.append('\n') else sb.append("\n\n")
                    }
                }
                else -> node.childNodes().forEach { appendEditable(it, sb) }
            }
        }
    }
}
