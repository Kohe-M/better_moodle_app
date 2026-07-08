package dev.rits.bettermoodle.data

import java.net.URI

sealed interface MoodleUrlTarget {
    data class Course(val courseId: Long) : MoodleUrlTarget
    data class Module(val cmid: Long, val modName: String) : MoodleUrlTarget
    data class ForumDiscussion(val discussionId: Long) : MoodleUrlTarget
}

/**
 * MoodleのURLをネイティブ遷移候補に分類する。
 * 対象外 (非Moodleホスト・未知のパス・id欠落) は null。
 */
fun parseMoodleUrlTarget(url: String?): MoodleUrlTarget? {
    val source = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!UrlPolicy.isAllowedMoodleUrl(source)) return null

    val uri = runCatching { URI(source) }.getOrNull() ?: return null
    val segments = uri.path
        .orEmpty()
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }

    if (segments.size == 3 && segments[0] == "mod" && segments[2] == "view.php") {
        val cmid = parseCourseModuleIdFromUrl(source) ?: return null
        return MoodleUrlTarget.Module(cmid, segments[1])
    }

    if (segments == listOf("mod", "forum", "discuss.php")) {
        val discussionId = parseQueryParameterId(source, "d") ?: return null
        return MoodleUrlTarget.ForumDiscussion(discussionId)
    }

    if (segments.size == 2 && segments[0] == "course" && segments[1] == "view.php") {
        val courseId = parseCourseModuleIdFromUrl(source) ?: return null
        return MoodleUrlTarget.Course(courseId)
    }

    return null
}
