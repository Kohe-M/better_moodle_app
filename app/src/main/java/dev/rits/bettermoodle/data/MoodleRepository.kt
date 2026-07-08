package dev.rits.bettermoodle.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class MoodleRepository(
    private val client: MoodleClient,
) {
    suspend fun siteInfo(): SiteInfo = client.callAs("core_webservice_get_site_info")

    suspend fun enrolledCourses(): List<Course> {
        val resp: CoursesByTimelineResponse = client.callAs(
            "core_course_get_enrolled_courses_by_timeline_classification",
            mapOf(
                "classification" to "inprogress",
                "limit" to "0",
                "offset" to "0",
            ),
        )
        return resp.courses
    }

    /** 今後の締切イベント (課題・小テスト等)。公式アプリのタイムラインと同じAPI。 */
    suspend fun upcomingEvents(fromEpochSec: Long, limit: Int = 50): List<ActionEvent> {
        val events = mutableListOf<ActionEvent>()
        var afterEventId: Long? = null
        var pagesFetched = 0
        var received: Int

        do {
            val resp: ActionEventsResponse = client.callAs(
                "core_calendar_get_action_events_by_timesort",
                actionEventsParams(fromEpochSec, limit, afterEventId),
            )
            received = resp.events.size
            events += resp.events
            pagesFetched += 1
            afterEventId = resp.lastid
        } while (shouldFetchNextPage(limit, received, pagesFetched))

        return events
    }

    /** 課題・小テストの締切だけに絞ったイベント */
    suspend fun upcomingDeadlines(fromEpochSec: Long): List<ActionEvent> =
        upcomingEvents(fromEpochSec).filter { it.modulename in DEADLINE_MODULES }

    suspend fun notifications(limit: Int = 50): NotificationsResponse = client.callAs(
        "message_popup_get_popup_notifications",
        mapOf(
            "useridto" to "0",
            "limit" to limit.toString(),
            "offset" to "0",
        ),
    )

    /** コースの中身 (セクション/アクティビティ一覧) */
    suspend fun courseContents(courseId: Long): List<CourseSection> =
        client.callAs("core_course_get_contents", mapOf("courseid" to courseId.toString()))

    suspend fun assignments(courseId: Long): List<Assignment> {
        val resp: AssignmentsResponse = client.callAs(
            "mod_assign_get_assignments",
            mapOf("courseids[0]" to courseId.toString()),
        )
        return resp.courses.flatMap { it.assignments }
    }

    suspend fun assignment(courseId: Long, cmid: Long, assignId: Long): Assignment? =
        assignments(courseId).firstOrNull { it.id == assignId || it.cmid == cmid }

    suspend fun assignmentStatus(assignId: Long): SubmissionStatusResponse =
        client.callAs("mod_assign_get_submission_status", mapOf("assignid" to assignId.toString()))

    suspend fun quizzes(courseId: Long): List<Quiz> {
        val resp: QuizzesResponse = client.callAs(
            "mod_quiz_get_quizzes_by_courses",
            quizzesByCoursesParams(courseId),
        )
        return resp.quizzes
    }

    suspend fun quiz(courseId: Long, courseModuleId: Long): Quiz? =
        quizzes(courseId).firstOrNull { it.coursemodule == courseModuleId }

    suspend fun quizAttempts(quizId: Long): List<QuizAttempt> {
        require(quizId > 0L) { "Invalid quiz instance ID" }
        val resp: QuizAttemptsResponse = client.callAs(
            "mod_quiz_get_user_attempts",
            quizUserAttemptsParams(quizId),
        )
        return resp.attempts
    }

    suspend fun quizBestGrade(quizId: Long): QuizBestGradeResponse {
        require(quizId > 0L) { "Invalid quiz instance ID" }
        return client.callAs(
            "mod_quiz_get_user_best_grade",
            quizBestGradeParams(quizId),
        )
    }

    suspend fun quizAccessInformation(quizId: Long): QuizAccessInformationResponse {
        require(quizId > 0L) { "Invalid quiz instance ID" }
        return client.callAs(
            "mod_quiz_get_quiz_access_information",
            quizAccessInformationParams(quizId),
        )
    }

    suspend fun pages(courseId: Long): List<MoodlePage> {
        require(courseId > 0L) { "Invalid course ID" }
        val resp: PagesResponse = client.callAs(
            "mod_page_get_pages_by_courses",
            pagesByCoursesParams(courseId),
        )
        return resp.pages
    }

    suspend fun page(courseId: Long, courseModuleId: Long): MoodlePage? =
        pages(courseId).firstOrNull { it.coursemodule == courseModuleId }

    suspend fun startAssignmentSubmission(assignId: Long) {
        client.call("mod_assign_start_submission", mapOf("assignid" to assignId.toString()))
    }

    suspend fun saveOnlineTextSubmission(assignId: Long, htmlText: String) {
        client.call(
            "mod_assign_save_submission",
            mapOf(
                "assignid" to assignId.toString(),
                "plugindata[onlinetext_editor][text]" to htmlText,
                "plugindata[onlinetext_editor][format]" to "1",
                "plugindata[onlinetext_editor][itemid]" to "0",
            ),
        )
    }

    suspend fun saveFileSubmission(assignId: Long, itemId: Long) {
        client.call(
            "mod_assign_save_submission",
            mapOf(
                "assignid" to assignId.toString(),
                "plugindata[files_filemanager]" to itemId.toString(),
            ),
        )
    }

    suspend fun submitAssignmentForGrading(assignId: Long, acceptSubmissionStatement: Boolean) {
        client.call(
            "mod_assign_submit_for_grading",
            mapOf(
                "assignid" to assignId.toString(),
                "acceptsubmissionstatement" to if (acceptSubmissionStatement) "1" else "0",
            ),
        )
    }

    suspend fun uploadSubmissionFile(file: java.io.File, filename: String): UploadedFile {
        val uploaded = client.uploadFile(file, filename)
        val first = uploaded.firstOrNull() ?: throw MoodleWsException("uploadfailed", "Upload returned no file")
        if (first.itemid <= 0) throw MoodleWsException("uploadfailed", "Upload did not return a draft item ID")
        return first
    }

    suspend fun forumDiscussions(forumInstanceId: Long): List<ForumDiscussion> {
        require(forumInstanceId > 0L) { "Invalid forum instance ID" }
        val resp: ForumDiscussionsResponse = client.callAs(
            "mod_forum_get_forum_discussions",
            forumDiscussionsParams(forumInstanceId),
        )
        return resp.discussions
    }

    suspend fun forumPosts(discussionId: Long): List<ForumPost> {
        require(discussionId > 0L) { "Invalid discussion ID" }
        val resp: ForumPostsResponse = client.callAs(
            "mod_forum_get_discussion_posts",
            forumPostsParams(discussionId),
        )
        runCatching {
            client.call(
                "mod_forum_view_forum_discussion",
                forumPostsParams(discussionId),
            )
        }
        return resp.posts
    }

    /**
     * ブラウザで開くための自動ログインURLを生成する。
     * tool_mobile_get_autologin_key はレート制限 (数分に1回) があるため、
     * 失敗時は呼び出し側で元URLにフォールバックすること。
     */
    suspend fun autologinUrl(targetUrl: String): String? = runCatching {
        if (!UrlPolicy.canUseMoodleAutologin(targetUrl)) return null
        val privateToken = client.privateToken() ?: return null
        val key: AutologinKey = client.callAs(
            "tool_mobile_get_autologin_key",
            mapOf("privatetoken" to privateToken),
        )
        if (key.autologinurl.isBlank() || key.key.isBlank()) return null
        if (!UrlPolicy.isAllowedMoodleUrl(key.autologinurl)) return null
        val encoded = java.net.URLEncoder.encode(targetUrl, "UTF-8")
        "${key.autologinurl}?key=${key.key}&urltogo=$encoded"
    }.getOrNull()

    /** pluginfile等のファイルURLにトークンを付与する (認証付きダウンロード用) */
    fun authedFileUrl(fileUrl: String): String? = client.authedUrl(fileUrl)

    /**
     * 時間割: ダッシュボードのカスタムブロック rutime_table のHTMLを取得して解析する。
     */
    suspend fun timetable(): Timetable {
        val resp: DashboardBlocksResponse = client.callAs(
            "core_block_get_dashboard_blocks",
            mapOf("returncontents" to "1"),
        )
        val block = resp.blocks.firstOrNull { it.name == "rutime_table" }
            ?: return Timetable.EMPTY
        val html = block.contents?.content ?: return Timetable.EMPTY
        return parseTimetableHtml(html)
    }

    companion object {
        val DEADLINE_MODULES = setOf("assign", "quiz", "workshop", "lesson")
        private const val MAX_ACTION_EVENT_PAGES = 3

        fun shouldFetchNextPage(pageSize: Int, received: Int, pagesFetched: Int): Boolean =
            pageSize > 0 && received >= pageSize && pagesFetched < MAX_ACTION_EVENT_PAGES

        fun actionEventsParams(fromEpochSec: Long, limit: Int, afterEventId: Long? = null): Map<String, String> =
            buildMap {
                put("timesortfrom", fromEpochSec.toString())
                put("limitnum", limit.toString())
                afterEventId?.let { put("aftereventid", it.toString()) }
            }

        fun forumDiscussionsParams(forumInstanceId: Long): Map<String, String> =
            mapOf(
                "forumid" to forumInstanceId.toString(),
            )

        fun forumPostsParams(discussionId: Long): Map<String, String> =
            mapOf("discussionid" to discussionId.toString())

        fun quizzesByCoursesParams(courseId: Long): Map<String, String> =
            mapOf("courseids[0]" to courseId.toString())

        fun quizUserAttemptsParams(quizId: Long): Map<String, String> =
            mapOf(
                "quizid" to quizId.toString(),
                "status" to "all",
            )

        fun quizBestGradeParams(quizId: Long): Map<String, String> =
            mapOf("quizid" to quizId.toString())

        fun quizAccessInformationParams(quizId: Long): Map<String, String> =
            mapOf("quizid" to quizId.toString())

        fun pagesByCoursesParams(courseId: Long): Map<String, String> =
            mapOf("courseids[0]" to courseId.toString())

        private val DAY_CHARS = listOf("月", "火", "水", "木", "金", "土", "日")
        private val DAY_OF_WEEK_HEADER_REGEX = Regex("([月火水木金土日])曜")

        /**
         * rutime_tableブロックの table.timetable を解析する。
         * 行 = 時限 (td.time に時限番号)、列 = 曜日 (ヘッダ行に曜日名)。
         * 各セルには .subject 要素があり、コースリンクとコース名
         * ("12345:科目名" 形式) が含まれる。
         */
        fun parseTimetableHtml(html: String): Timetable {
            val doc = Jsoup.parse(html)
            val table = doc.selectFirst("table.timetable")
                ?: doc.selectFirst("table")
                ?: return Timetable.EMPTY

            // ヘッダから列index → 曜日indexの対応を作る
            val headerCells = table.select("tr").firstOrNull()?.children() ?: return Timetable.EMPTY
            val colToDay = mutableMapOf<Int, Int>()
            headerCells.forEachIndexed { col, cell ->
                val text = cell.text()
                val dayIdx = DAY_OF_WEEK_HEADER_REGEX.find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.let(DAY_CHARS::indexOf)
                    ?: DAY_CHARS.indexOfFirst { text.contains(it) }
                if (dayIdx >= 0) {
                    colToDay[col] = dayIdx
                }
            }

            val entries = mutableListOf<TimetableEntry>()
            for (row in table.select("tr").drop(1)) {
                val cells = row.children()
                val periodCell = cells.firstOrNull { it.hasClass("time") } ?: cells.firstOrNull()
                val period = periodCell?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                    ?: continue

                cells.forEachIndexed { col, cell ->
                    if (cell === periodCell) return@forEachIndexed
                    val dayIdx = colToDay[col] ?: run {
                        // ヘッダに曜日がなければ「時限セルを除く左からの位置」で月〜日を割り当てる
                        val offset = if (cells.indexOf(periodCell) < col) col - 1 else col
                        offset.takeIf { it in 0..6 }
                    } ?: return@forEachIndexed

                    val subjects = cell.select(".subject").ifEmpty {
                        if (cell.selectFirst("a[href*=course/view.php]") != null) listOf(cell) else emptyList()
                    }
                    for (subject in subjects) {
                        parseSubject(subject, dayIdx, period)?.let(entries::add)
                    }
                }
            }

            val usedDays = entries.map { it.dayIndex }.toSortedSet()
            val maxDay = maxOf(usedDays.maxOrNull() ?: 4, 4) // 最低 月〜金
            return Timetable(
                entries = entries,
                dayLabels = DAY_CHARS.subList(0, (maxDay + 1).coerceAtMost(7)),
            )
        }

        private fun parseSubject(subject: Element, dayIdx: Int, period: Int): TimetableEntry? {
            val link = subject.selectFirst("a[href*=course/view.php]")
            val rawText = (link?.text()?.takeIf { it.isNotBlank() } ?: subject.text()).trim()
            if (rawText.isBlank()) return null
            val code = Course.COURSE_CODE_REGEX.find(rawText)?.groupValues?.get(1)
            val title = rawText
                .replace(Regex("^\\d+\\s*[:：]\\s*"), "")
                .substringBefore('§')
                .trim()
            return TimetableEntry(
                dayIndex = dayIdx,
                period = period,
                title = title.ifBlank { rawText },
                courseCode = code,
                courseUrl = link?.attr("href"),
                room = extractRoom(subject, dayIdx, period),
            )
        }

        /**
         * 教室名を抽出する。rutime_tableでは .subject .room 要素に
         * "月1: 教室名" 形式で入る (曜日+時限プレフィックスを除去する)。
         */
        private fun extractRoom(subject: Element, dayIdx: Int, period: Int): String? {
            val roomText = subject.select(".room").joinToString("\n") { room ->
                room.wholeText().ifBlank { room.text() }
            }
            return extractRoomForSlot(roomText, dayIdx, period)
        }

        fun extractRoomForSlot(roomText: String?, dayIdx: Int, period: Int): String? {
            val text = roomText?.trim().orEmpty()
            if (text.isBlank()) return null

            val targetDay = DAY_CHARS.getOrNull(dayIdx) ?: return null
            val slotRegex = Regex("([月火水木金土日])\\s*(\\d+)\\s*[:：]\\s*")
            val matches = slotRegex.findAll(text).toList()
            if (matches.isEmpty()) return text.ifBlank { null }

            matches.forEachIndexed { index, match ->
                val day = match.groupValues[1]
                val matchedPeriod = match.groupValues[2].toIntOrNull()
                if (day == targetDay && matchedPeriod == period) {
                    val start = match.range.last + 1
                    val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
                    return text.substring(start, end)
                        .trim()
                        .trim(';', '；', ',', '、', '/', '／')
                        .trim()
                        .ifBlank { null }
                }
            }
            return null
        }
    }
}
