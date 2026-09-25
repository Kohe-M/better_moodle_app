package dev.rits.bettermoodle.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class MoodleRepository(
    private val client: MoodleClient,
) {
    suspend fun siteInfo(): SiteInfo = client.callAs("core_webservice_get_site_info")

    /**
     * 履修コース一覧。[classification] は Moodle の timeline classification
     * ("inprogress" / "past" / "future" / "all")。UIでは既定を「進行中」とし、
     * 過去・未来のコースも切り替えて見られるようにする。
     */
    suspend fun enrolledCourses(classification: String = "inprogress"): List<Course> {
        require(classification in COURSE_CLASSIFICATIONS) { "Unknown classification" }
        val resp: CoursesByTimelineResponse = client.callAs(
            "core_course_get_enrolled_courses_by_timeline_classification",
            mapOf(
                "classification" to classification,
                "limit" to "0",
                "offset" to "0",
            ),
        )
        return resp.courses
    }

    /**
     * 今後のイベント (課題・小テスト等) を1ページ以上取得する。公式アプリのタイムラインと同じAPI。
     * [maxPages] ページ取得しても続きがある場合は [ActionEventPage.hasMore] で知らせ、
     * 打ち切りを「全件」と誤認させない。
     */
    suspend fun upcomingEvents(
        fromEpochSec: Long,
        limit: Int = 50,
        afterEventId: Long? = null,
        maxPages: Int = DEFAULT_ACTION_EVENT_PAGES,
    ): ActionEventPage {
        val events = mutableListOf<ActionEvent>()
        val seenIds = mutableSetOf<Long>()
        var cursor = afterEventId
        var pagesFetched = 0
        var hasMore: Boolean
        var incompleteReason: String? = null

        do {
            val resp: ActionEventsResponse = client.callAs(
                "core_calendar_get_action_events_by_timesort",
                actionEventsParams(fromEpochSec, limit, cursor),
            )
            val fresh = resp.events.filter { seenIds.add(it.id) }
            events += fresh
            pagesFetched += 1
            val pageFull = resp.events.size >= limit && limit > 0
            // lastid が進まない応答で同一ページを取り続けない。ただし満杯ページでカーソルが
            // 進まない・既知IDしか返らないのは終端ではなくプロトコル異常なので、End と区別する
            val cursorAdvanced = resp.lastid > 0L && resp.lastid != cursor
            val cycled = resp.events.isNotEmpty() && fresh.isEmpty()
            when {
                pageFull && !cursorAdvanced -> {
                    incompleteReason = "サーバーの応答でページ位置 (lastid) が進みませんでした"
                    hasMore = false
                }
                cycled -> {
                    incompleteReason = "同じイベントが繰り返し返されました"
                    hasMore = false
                }
                else -> hasMore = pageFull
            }
            if (cursorAdvanced) cursor = resp.lastid
        } while (hasMore && pagesFetched < maxPages)

        return ActionEventPage(
            events = events,
            hasMore = hasMore,
            nextAfterEventId = cursor.takeIf { hasMore },
            incompleteReason = incompleteReason,
        )
    }

    /** 課題・小テストの締切だけに絞ったイベント (ページ情報付き) */
    suspend fun upcomingDeadlines(
        fromEpochSec: Long,
        afterEventId: Long? = null,
        maxPages: Int = DEFAULT_ACTION_EVENT_PAGES,
    ): ActionEventPage {
        val page = upcomingEvents(fromEpochSec, afterEventId = afterEventId, maxPages = maxPages)
        return page.copy(events = page.events.filter { it.modulename in DEADLINE_MODULES })
    }

    /**
     * [fromEpochSec]..[untilEpochSec] の締切を取得する (Worker用)。
     * timesort 昇順で返る前提で、範囲を超えたイベントが出るか続きが無くなるまで取得する。
     * ページ上限 [maxPages] やカーソル異常で終端を確認できなかった場合は
     * [DeadlineWindow.complete] を false にし理由を添える。「取り切った」と断定するのは complete=true のときだけ。
     */
    suspend fun deadlinesWithin(
        fromEpochSec: Long,
        untilEpochSec: Long,
        maxPages: Int = WORKER_ACTION_EVENT_MAX_PAGES,
        /** 前回ページ上限で打ち切った位置から続ける場合の aftereventid */
        afterEventId: Long? = null,
    ): DeadlineWindow {
        val collected = mutableListOf<ActionEvent>()
        var cursor: Long? = afterEventId
        var pages = 0
        fun window(complete: Boolean, reason: String?, next: Long? = null) = DeadlineWindow(
            events = collected.distinctBy { it.id }
                .filter { it.modulename in DEADLINE_MODULES && it.timesort <= untilEpochSec },
            complete = complete,
            incompleteReason = reason.takeIf { !complete },
            nextAfterEventId = next.takeIf { !complete },
        )
        while (true) {
            val page = upcomingEvents(fromEpochSec, afterEventId = cursor, maxPages = 1)
            pages += 1
            collected += page.events
            val beyondWindow = page.events.any { it.timesort > untilEpochSec }
            if (beyondWindow) return window(complete = true, reason = null)
            if (page.isIncomplete) return window(complete = false, reason = page.incompleteReason)
            if (!page.hasMore) return window(complete = true, reason = null)
            if (pages >= maxPages) {
                return window(
                    complete = false,
                    reason = "取得ページ数の上限 ($maxPages) に達しました",
                    next = page.nextAfterEventId,
                )
            }
            cursor = page.nextAfterEventId
        }
    }

    /** 通知一覧。[offset] から [limit] 件。続きの有無は [NotificationsPage.hasMore]。 */
    suspend fun notifications(limit: Int = 50, offset: Int = 0): NotificationsPage {
        val resp: NotificationsResponse = client.callAs(
            "message_popup_get_popup_notifications",
            mapOf(
                "useridto" to "0",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )
        return NotificationsPage(
            notifications = resp.notifications,
            unreadCount = resp.unreadcount,
            hasMore = resp.notifications.size >= limit && limit > 0,
            nextOffset = offset + resp.notifications.size,
        )
    }

    /** コースの中身 (セクション/アクティビティ一覧) */
    suspend fun courseContents(courseId: Long): List<CourseSection> =
        client.callAs("core_course_get_contents", mapOf("courseid" to courseId.toString()))

    /** cmidからコースID・インスタンスID・モジュール種別を補完する (通知URL等のネイティブ解決用) */
    suspend fun courseModule(cmid: Long): CourseModuleInfo? {
        require(cmid > 0L) { "Invalid course module ID" }
        val resp: CourseModuleInfoResponse = client.callAs(
            "core_course_get_course_module",
            mapOf("cmid" to cmid.toString()),
        )
        return resp.cm?.takeIf { it.id > 0L && it.course > 0L }
    }

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

    /**
     * 制限時間付き課題の提出開始 (計測開始)。通常課題では `timelimitnotenabled` warning が返るため、
     * 呼び出し側は [Assignment.hasTimeLimit] の場合だけ使うこと。
     * 引数名は取得系と同じ `assignid` (保存・最終提出の `assignmentid` とは異なる)。
     */
    suspend fun startAssignmentSubmission(assignId: Long): StartSubmissionResponse {
        val element = client.call("mod_assign_start_submission", startSubmissionParams(assignId))
        if (element !is kotlinx.serialization.json.JsonObject) {
            throw MoodleResponseParseException("start_submission did not return an object")
        }
        val resp: StartSubmissionResponse = client.decodeStrict(element)
        // warnings は配列でなければならない (省略は許容、文字列などの不正は形式エラー)
        element["warnings"]?.let { client.decodeWarnings(it) }
        if (resp.warnings.isNotEmpty()) throw AssignmentSubmissionRejectedException(resp.warnings)
        return resp
    }

    /**
     * オンラインテキストを保存する。[htmlText] は HTML (format=1) であること。
     * warning が返った場合は保存されていない可能性があるため例外にする。
     */
    suspend fun saveOnlineTextSubmission(assignmentId: Long, htmlText: String) {
        val warnings = client.callForWarningList(
            "mod_assign_save_submission",
            saveOnlineTextParams(assignmentId, htmlText),
        )
        if (warnings.isNotEmpty()) throw AssignmentSubmissionRejectedException(warnings)
    }

    /**
     * ファイル提出領域を draft [draftItemId] の内容で置き換える。
     * Moodle は draft に無い既存ファイルを削除するため、[draftItemId] には
     * 残したい既存ファイルも含めておく必要がある ([AssignmentFileDraft] を使う)。
     */
    suspend fun saveFileSubmission(assignmentId: Long, draftItemId: Long) {
        val warnings = client.callForWarningList(
            "mod_assign_save_submission",
            saveFileSubmissionParams(assignmentId, draftItemId),
        )
        if (warnings.isNotEmpty()) throw AssignmentSubmissionRejectedException(warnings)
    }

    suspend fun submitAssignmentForGrading(assignmentId: Long, acceptSubmissionStatement: Boolean) {
        val warnings = client.callForWarningList(
            "mod_assign_submit_for_grading",
            submitForGradingParams(assignmentId, acceptSubmissionStatement),
        )
        if (warnings.isNotEmpty()) throw AssignmentSubmissionRejectedException(warnings)
    }

    /** 空のdraft領域IDを取得する (既存ファイルを全削除する場合や、先にIDが必要な場合に使う) */
    suspend fun unusedDraftItemId(): Long {
        val resp: UnusedDraftItemIdResponse = client.callAs("core_files_get_unused_draft_itemid")
        if (resp.itemid <= 0L) throw MoodleWsException("nodraft", "draft領域を作成できませんでした")
        return resp.itemid
    }

    /**
     * ファイルを draft 領域へアップロードする。[draftItemId] が null なら新規 draft を作る。
     * [filepath] は draft 内ディレクトリ。応答の itemid / filename / filepath が要求と一致することを確認する。
     */
    suspend fun uploadSubmissionFile(
        file: java.io.File,
        filename: String,
        draftItemId: Long? = null,
        filepath: String = "/",
    ): UploadedFile {
        val uploaded = client.uploadFile(file, filename, draftItemId, filepath)
        val first = uploaded.firstOrNull() ?: throw MoodleWsException("uploadfailed", "Upload returned no file")
        if (first.itemid <= 0) throw MoodleWsException("uploadfailed", "Upload did not return a draft item ID")
        if (draftItemId != null && first.itemid != draftItemId) {
            throw MoodleWsException("uploadfailed", "Upload went to an unexpected draft area")
        }
        if (first.filename.isNotBlank() && first.filename != filename) {
            throw MoodleWsException("uploadfailed", "Upload stored a different filename (${first.filename})")
        }
        val expectedPath = MoodleClient.normalizeDraftPath(filepath)
        if (first.filepath.isNotBlank() && MoodleClient.normalizeDraftPath(first.filepath) != expectedPath) {
            throw MoodleWsException("uploadfailed", "Upload stored a different path (${first.filepath})")
        }
        return first
    }

    /**
     * 提出済みファイルを一時ファイルへ取得する (draft引き継ぎ用)。
     * サイズが分かっている場合は一致を確認し、HTML応答や途中切断を再アップロードしない。
     */
    suspend fun downloadSubmissionFile(file: MoodleFile, dest: java.io.File, maxBytes: Long) {
        val url = file.fileurl ?: throw MoodleWsException("nofileurl", "ファイルURLがありません")
        val expectedSize = file.filesize
            ?: throw java.io.IOException("既存ファイル ${file.filename} のサイズが不明なため、取得内容を検証できません。Moodleで編集してください。")
        // 提出物自体が HTML (mimetype / 拡張子) のときだけ text/html 応答を受け入れる。
        // それ以外で HTML が返るのはログインページ等の可能性が高いので再アップロードしない
        val downloaded = client.downloadToFile(url, dest, maxBytes, allowHtml = isHtmlSubmission(file))
        if (downloaded.bytes != expectedSize) {
            dest.delete()
            throw java.io.IOException(
                "既存ファイル ${file.filename} の取得サイズ (${downloaded.bytes}) が提出物の情報 ($expectedSize) と一致しません。",
            )
        }
    }

    private fun isHtmlSubmission(file: MoodleFile): Boolean {
        val mime = file.mimetype?.substringBefore(';')?.trim()?.lowercase()
        val name = file.filename.lowercase()
        return mime == "text/html" || name.endsWith(".html") || name.endsWith(".htm")
    }

    /**
     * 通知を既読にする。契約: 応答は `{notificationid, warnings}` で、要求した ID と一致し warnings が空なら成功。
     * warnings があれば false、形式が違えば応答形式エラー。
     */
    suspend fun markNotificationRead(notificationId: Long, token: String? = null): Boolean {
        require(notificationId > 0L) { "Invalid notification ID" }
        val element = client.call(
            "core_message_mark_notification_read",
            mapOf("notificationid" to notificationId.toString()),
            tokenOverride = token,
        )
        if (element !is kotlinx.serialization.json.JsonObject) {
            throw MoodleResponseParseException("mark_notification_read did not return an object")
        }
        val resp: MarkNotificationReadResponse = client.decodeStrict(element)
        element["warnings"]?.let { client.decodeWarnings(it) }
        if (resp.notificationid != notificationId) {
            throw MoodleResponseParseException("mark_notification_read returned a different id (${resp.notificationid})")
        }
        return resp.warnings.isEmpty()
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
        // 閲覧記録は失敗しても投稿の表示は続ける。ただし画面離脱のキャンセルは握りつぶさない (G13)
        try {
            client.call(
                "mod_forum_view_forum_discussion",
                forumPostsParams(discussionId),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return resp.posts
    }

    @Volatile
    private var lastAutologinKeyRequestAtMillis = 0L

    /**
     * ブラウザで開くための自動ログインURLを生成する。
     * tool_mobile_get_autologin_key はレート制限 (数分に1回) があるため、
     * 制限内の再要求はWS呼び出し自体を省いて即nullを返す。
     * 失敗時は呼び出し側で元URLにフォールバックすること。
     */
    suspend fun autologinUrl(targetUrl: String): String? = runCatching {
        if (!UrlPolicy.canUseMoodleAutologin(targetUrl)) return null
        val privateToken = client.privateToken() ?: return null
        val now = System.currentTimeMillis()
        if (now - lastAutologinKeyRequestAtMillis < AUTOLOGIN_KEY_MIN_INTERVAL_MILLIS) return null
        val key: AutologinKey = client.callAs(
            "tool_mobile_get_autologin_key",
            mapOf("privatetoken" to privateToken),
        )
        // サーバーが鍵を発行したときだけ待ち時間を数える (通信失敗で6分間使えなくならないように。G08)
        lastAutologinKeyRequestAtMillis = now
        if (key.autologinurl.isBlank() || key.key.isBlank()) return null
        if (!UrlPolicy.isAllowedMoodleUrl(key.autologinurl)) return null
        val encoded = java.net.URLEncoder.encode(targetUrl, "UTF-8")
        "${key.autologinurl}?key=${key.key}&urltogo=$encoded"
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        null
    }

    /** ログアウト時に呼ぶ。次のアカウントが直後に自動ログインURLを使えるようにする */
    fun resetAutologinThrottle() {
        lastAutologinKeyRequestAtMillis = 0L
    }

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
        val COURSE_CLASSIFICATIONS = listOf("inprogress", "future", "past", "all")
        const val DEFAULT_ACTION_EVENT_PAGES = 3
        const val WORKER_ACTION_EVENT_MAX_PAGES = 20

        // サイト側の tool_mobile_get_autologin_key レート制限 (6分) に合わせる
        private const val AUTOLOGIN_KEY_MIN_INTERVAL_MILLIS = 6 * 60 * 1000L

        fun shouldFetchNextPage(
            pageSize: Int,
            received: Int,
            pagesFetched: Int,
            maxPages: Int = DEFAULT_ACTION_EVENT_PAGES,
        ): Boolean = pageSize > 0 && received >= pageSize && pagesFetched < maxPages

        fun actionEventsParams(fromEpochSec: Long, limit: Int, afterEventId: Long? = null): Map<String, String> =
            buildMap {
                put("timesortfrom", fromEpochSec.toString())
                put("limitnum", limit.toString())
                afterEventId?.let { put("aftereventid", it.toString()) }
            }

        fun startSubmissionParams(assignId: Long): Map<String, String> =
            mapOf("assignid" to assignId.toString())

        fun saveOnlineTextParams(assignmentId: Long, htmlText: String): Map<String, String> =
            mapOf(
                "assignmentid" to assignmentId.toString(),
                "plugindata[onlinetext_editor][text]" to htmlText,
                "plugindata[onlinetext_editor][format]" to "1",
                "plugindata[onlinetext_editor][itemid]" to "0",
            )

        fun saveFileSubmissionParams(assignmentId: Long, draftItemId: Long): Map<String, String> =
            mapOf(
                "assignmentid" to assignmentId.toString(),
                "plugindata[files_filemanager]" to draftItemId.toString(),
            )

        fun submitForGradingParams(assignmentId: Long, acceptSubmissionStatement: Boolean): Map<String, String> =
            mapOf(
                "assignmentid" to assignmentId.toString(),
                "acceptsubmissionstatement" to if (acceptSubmissionStatement) "1" else "0",
            )

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
