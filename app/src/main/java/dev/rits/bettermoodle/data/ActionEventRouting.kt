package dev.rits.bettermoodle.data

fun parseCourseModuleIdFromUrl(url: String?): Long? {
    return parseQueryParameterId(url, "id")
}

fun parseQueryParameterId(url: String?, name: String): Long? {
    if (name.isBlank()) return null
    val query = url
        ?.takeIf { it.isNotBlank() }
        ?.replace("&amp;", "&")
        ?.substringAfter('?', missingDelimiterValue = "")
        ?.substringBefore('#')
        ?: return null
    if (query.isBlank()) return null

    return query
        .split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            if (key == name) part.substringAfter('=', missingDelimiterValue = "") else null
        }
        .firstNotNullOfOrNull { value ->
            value.toLongOrNull()?.takeIf { it > 0L }
        }
}
