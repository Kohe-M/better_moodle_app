package dev.rits.bettermoodle

import android.net.Uri
import dev.rits.bettermoodle.data.Course
import dev.rits.bettermoodle.data.CourseModuleInfo
import dev.rits.bettermoodle.data.ForumTarget
import dev.rits.bettermoodle.data.MoodleUrlTarget
import dev.rits.bettermoodle.data.PageTarget
import dev.rits.bettermoodle.data.PreviewKind
import dev.rits.bettermoodle.data.QuizTarget
import dev.rits.bettermoodle.data.UrlPolicy
import dev.rits.bettermoodle.data.parseMoodleUrlTarget
import dev.rits.bettermoodle.data.previewKindFor
import kotlinx.coroutines.CancellationException
import java.net.URI

/**
 * ルート画面の NavHost のルート定義。パターン (composable に渡す文字列) と組み立て関数を同じ場所に置く。
 *
 * ルート引数は `Uri.encode` する。`android.net.Uri` は JVM テストで使えないので、
 * 組み立て関数はエンコーダ ([encode]) とデコーダ ([decode]) を引数で受け、既定で `Uri` を使う。
 * `URLEncoder` などに置き換えない (空白や記号の扱いが変わり、ルートが変わる)。
 */
internal object Routes {
    const val MAIN = "main"
    const val NOTIFICATIONS = "notifications"
    const val SYLLABUS = "syllabus"
    const val PORTAL = "portal"
    const val SETTINGS = "settings"
    const val COURSE = "course/{courseId}?name={name}&code={code}"
    const val PDF = "pdf?url={url}&title={title}"
    const val ASSIGNMENT = "assignment/{courseId}/{moduleId}/{assignId}?title={title}"
    const val FILE_PREVIEW = "filePreview?url={url}&title={title}&kind={kind}"
    const val FORUM =
        "forum?courseId={courseId}&courseModuleId={courseModuleId}&forumInstanceId={forumInstanceId}" +
            "&contextId={contextId}&modName={modName}&title={title}&url={url}"
    const val FORUM_DISCUSSION = "forumDiscussion?discussionId={discussionId}&title={title}&url={url}"
    const val QUIZ =
        "quiz?courseId={courseId}&courseModuleId={courseModuleId}&quizInstanceId={quizInstanceId}" +
            "&contextId={contextId}&modName={modName}&title={title}&url={url}"
    const val PAGE =
        "page?courseId={courseId}&courseModuleId={courseModuleId}" +
            "&contextId={contextId}&modName={modName}&title={title}&url={url}"
    const val MOODLE_WEB = "moodleWeb?url={url}&title={title}"

    private val uriEncode: (String) -> String = { Uri.encode(it) }
    private val uriDecode: (String) -> String = { Uri.decode(it) }

    fun course(id: Long, name: String, code: String?, encode: (String) -> String = uriEncode): String =
        "course/$id?name=${encode(name)}&code=${encode(code ?: "")}"

    fun pdf(url: String, title: String, encode: (String) -> String = uriEncode): String =
        "pdf?url=${encode(url)}&title=${encode(title)}"

    fun assignment(
        courseId: Long,
        moduleId: Long,
        assignId: Long,
        title: String,
        encode: (String) -> String = uriEncode,
    ): String = "assignment/$courseId/$moduleId/$assignId?title=${encode(title)}"

    fun filePreview(url: String, title: String, kind: PreviewKind, encode: (String) -> String = uriEncode): String =
        "filePreview?url=${encode(url)}" +
            "&title=${encode(title)}" +
            "&kind=${encode(kind.name)}"

    fun moodleWeb(url: String, title: String, encode: (String) -> String = uriEncode): String =
        "moodleWeb?url=${encode(url)}&title=${encode(title)}"

    fun forumDiscussion(discussionId: Long, title: String, url: String, encode: (String) -> String = uriEncode): String =
        "forumDiscussion?discussionId=$discussionId" +
            "&title=${encode(title)}" +
            "&url=${encode(url)}"

    fun forum(target: ForumTarget, title: String, encode: (String) -> String = uriEncode): String =
        "forum?courseId=${target.courseId}" +
            "&courseModuleId=${target.courseModuleId}" +
            "&forumInstanceId=${target.forumInstanceId}" +
            "&contextId=${target.contextId ?: 0L}" +
            "&modName=${encode(target.modName)}" +
            "&title=${encode(title)}" +
            "&url=${encode(target.url.orEmpty())}"

    fun quiz(target: QuizTarget, title: String, encode: (String) -> String = uriEncode): String =
        "quiz?courseId=${target.courseId}" +
            "&courseModuleId=${target.courseModuleId}" +
            "&quizInstanceId=${target.quizInstanceId}" +
            "&contextId=${target.contextId ?: 0L}" +
            "&modName=${encode(target.modName)}" +
            "&title=${encode(title)}" +
            "&url=${encode(target.url.orEmpty())}"

    fun page(target: PageTarget, title: String, encode: (String) -> String = uriEncode): String =
        "page?courseId=${target.courseId}" +
            "&courseModuleId=${target.courseModuleId}" +
            "&contextId=${target.contextId ?: 0L}" +
            "&modName=${encode(target.modName)}" +
            "&title=${encode(title)}" +
            "&url=${encode(target.url.orEmpty())}"

    // ルート引数の読み取り。[arg] は NavBackStackEntry の引数 (文字列) を名前で返す

    /** ID 引数。無い、数値でないときは 0 */
    fun longArg(arg: (String) -> String?, name: String): Long = arg(name)?.toLongOrNull() ?: 0L

    private fun contextIdArg(arg: (String) -> String?): Long? =
        arg("contextId")?.toLongOrNull()?.takeIf { it > 0L }

    private fun urlArg(arg: (String) -> String?): String? = arg("url")?.takeIf { it.isNotBlank() }

    fun forumTarget(arg: (String) -> String?): ForumTarget = ForumTarget(
        courseId = longArg(arg, "courseId"),
        courseModuleId = longArg(arg, "courseModuleId"),
        forumInstanceId = longArg(arg, "forumInstanceId"),
        contextId = contextIdArg(arg),
        modName = arg("modName").orEmpty(),
        url = urlArg(arg),
    )

    fun quizTarget(arg: (String) -> String?): QuizTarget = QuizTarget(
        courseId = longArg(arg, "courseId"),
        courseModuleId = longArg(arg, "courseModuleId"),
        quizInstanceId = longArg(arg, "quizInstanceId"),
        contextId = contextIdArg(arg),
        modName = arg("modName").orEmpty(),
        url = urlArg(arg),
    )

    fun pageTarget(arg: (String) -> String?): PageTarget = PageTarget(
        courseId = longArg(arg, "courseId"),
        courseModuleId = longArg(arg, "courseModuleId"),
        contextId = contextIdArg(arg),
        modName = arg("modName").orEmpty(),
        url = urlArg(arg),
    )

    /**
     * pluginfile の画像・PDF・テキストはWebViewではなくアプリ内プレビューで開く
     * (課題説明やフォーラム本文の画像リンクの逃げ道)。対象外なら null。
     */
    fun pluginFile(
        url: String,
        title: String,
        encode: (String) -> String = uriEncode,
        decode: (String) -> String = uriDecode,
    ): String? {
        if (!UrlPolicy.isSafeMoodlePluginFileSourceUrl(url)) return null
        val filename = runCatching { URI(url).path }.getOrNull()?.substringAfterLast('/')?.let { decode(it) }.orEmpty()
        if (filename.isBlank()) return null
        val label = title.ifBlank { filename }
        return when (val kind = previewKindFor(filename, null)) {
            PreviewKind.Pdf -> pdf(url, label, encode)
            PreviewKind.Image,
            PreviewKind.Text,
            -> filePreview(url, label, kind, encode)
            else -> null
        }
    }

    /**
     * Moodle URLをネイティブ画面のrouteに解決する。解決できなければnull
     * (呼び出し側で従来のWebView/Custom Tabにフォールバックする)。
     * 取得の失敗は null / 空として扱うが、キャンセルは再送出する。
     */
    suspend fun resolveNative(
        url: String,
        title: String,
        courseModule: suspend (cmid: Long) -> CourseModuleInfo?,
        enrolledCourses: suspend () -> List<Course>,
        encode: (String) -> String = uriEncode,
    ): String? {
        return when (val target = parseMoodleUrlTarget(url)) {
            is MoodleUrlTarget.Course -> course(target.courseId, title.ifBlank { "コース" }, null, encode)
            is MoodleUrlTarget.ForumDiscussion -> forumDiscussion(target.discussionId, title, url, encode)
            is MoodleUrlTarget.Module -> {
                val cm = runCatching {
                    courseModule(target.cmid)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    null
                } ?: return null

                val routeTitle = cm.name.ifBlank { title }
                when (target.modName) {
                    "assign" -> cm.instance.takeIf { it > 0L }?.let { assignId ->
                        assignment(cm.course, cm.id, assignId, routeTitle, encode)
                    }
                    "quiz" -> cm.instance.takeIf { it > 0L }?.let { quizId ->
                        quiz(
                            QuizTarget(
                                courseId = cm.course,
                                courseModuleId = cm.id,
                                quizInstanceId = quizId,
                                contextId = null,
                                modName = "quiz",
                                url = url,
                            ),
                            routeTitle,
                            encode,
                        )
                    }
                    "forum" -> cm.instance.takeIf { it > 0L }?.let { forumId ->
                        forum(
                            ForumTarget(
                                courseId = cm.course,
                                courseModuleId = cm.id,
                                forumInstanceId = forumId,
                                contextId = null,
                                modName = "forum",
                                url = url,
                            ),
                            routeTitle,
                            encode,
                        )
                    }
                    "page" -> page(
                        PageTarget(
                            courseId = cm.course,
                            courseModuleId = cm.id,
                            contextId = null,
                            modName = "page",
                            url = url,
                        ),
                        routeTitle,
                        encode,
                    )
                    else -> {
                        val enrolled = runCatching {
                            enrolledCourses()
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            emptyList()
                        }.firstOrNull { it.id == cm.course }
                        course(
                            cm.course,
                            enrolled?.displayName?.ifBlank { "コース" } ?: "コース",
                            enrolled?.courseCode,
                            encode,
                        )
                    }
                }
            }
            null -> null
        }
    }
}
