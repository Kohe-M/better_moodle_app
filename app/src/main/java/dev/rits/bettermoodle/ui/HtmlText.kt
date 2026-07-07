package dev.rits.bettermoodle.ui

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

fun htmlToPlainText(html: String?): String =
    Jsoup.parse(Jsoup.clean(html.orEmpty(), Safelist.basic())).text().trim()

