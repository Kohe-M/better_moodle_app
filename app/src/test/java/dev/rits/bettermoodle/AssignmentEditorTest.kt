package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Assignment
import dev.rits.bettermoodle.data.AssignmentConfig
import dev.rits.bettermoodle.data.AssignmentEditor
import dev.rits.bettermoodle.data.AssignmentFileDraft
import dev.rits.bettermoodle.data.AssignmentGateway
import dev.rits.bettermoodle.data.AssignmentLastAttempt
import dev.rits.bettermoodle.data.AssignmentLoad
import dev.rits.bettermoodle.data.AssignmentSubmission
import dev.rits.bettermoodle.data.AssignmentSubmissionRejectedException
import dev.rits.bettermoodle.data.EditorPhase
import dev.rits.bettermoodle.data.MoodleFile
import dev.rits.bettermoodle.data.MoodleResponseParseException
import dev.rits.bettermoodle.data.PendingWrite
import dev.rits.bettermoodle.data.SubmissionEditorField
import dev.rits.bettermoodle.data.SubmissionFileArea
import dev.rits.bettermoodle.data.SubmissionFileFingerprint
import dev.rits.bettermoodle.data.SubmissionFileKey
import dev.rits.bettermoodle.data.SubmissionPlugin
import dev.rits.bettermoodle.data.SubmissionStatusResponse
import dev.rits.bettermoodle.data.WsWarning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * F03/F05, N01/N02: 草稿保護・既存ファイル保持 (パス付き)・warning・結果不明の照会・排他。
 * Fake のサーバー状態は「save が適用された時点」で更新する (draft 組み立てでは変えない)。
 */
class AssignmentEditorTest {

    private class FakeGateway : AssignmentGateway {
        var assignment = Assignment(
            id = 42,
            cmid = 7,
            course = 1,
            name = "レポート",
            configs = listOf(
                AssignmentConfig("onlinetext", "assignsubmission", "enabled", "1"),
                AssignmentConfig("file", "assignsubmission", "enabled", "1"),
                AssignmentConfig("file", "assignsubmission", "maxfilesubmissions", "2"),
            ),
        )
        var serverHtml: String = ""
        var serverFiles: List<MoodleFile> = emptyList()
        var submissionStatus: String = "new"
        var failSave: Exception? = null
        var failSubmit: Exception? = null
        var failStatus: Exception? = null
        var failSaveFiles: Exception? = null
        /** サーバーで適用された後に応答だけ失う */
        var loseSubmitResponse = false
        var loseSaveResponse = false
        var loseSaveFilesResponse = false
        var serverEchoesSave = true
        /** draft に入れたファイル (saveFiles で提出領域へ反映) */
        val drafts = mutableMapOf<Long, List<MoodleFile>>()
        val savedHtml = mutableListOf<String>()
        val builtPlans = mutableListOf<AssignmentFileDraft.Plan>()
        val savedDraftIds = mutableListOf<Long>()
        var submitted = 0
        var started = 0
        var statusCalls = 0
        /** submissionStatus を止めて排他を観察するためのゲート */
        var statusGate: CompletableDeferred<Unit>? = null

        override suspend fun loadAssignment(courseId: Long, moduleId: Long, assignId: Long): Assignment = assignment

        override suspend fun submissionStatus(assignId: Long): SubmissionStatusResponse {
            statusCalls++
            statusGate?.await()
            failStatus?.let { throw it }
            return SubmissionStatusResponse(
                lastattempt = AssignmentLastAttempt(
                    submissionsenabled = true,
                    canedit = submissionStatus != "submitted",
                    cansubmit = submissionStatus != "submitted",
                    submission = AssignmentSubmission(
                        status = submissionStatus,
                        plugins = listOf(
                            SubmissionPlugin(
                                type = "onlinetext",
                                editorfields = listOf(SubmissionEditorField(name = "onlinetext", text = serverHtml)),
                            ),
                            SubmissionPlugin(
                                type = "file",
                                fileareas = listOf(SubmissionFileArea("submission_files", serverFiles)),
                            ),
                        ),
                    ),
                ),
            )
        }

        override suspend fun startSubmission(assignId: Long) {
            started++
        }

        override suspend fun saveOnlineText(assignmentId: Long, html: String) {
            failSave?.let { throw it }
            savedHtml += html
            if (serverEchoesSave) serverHtml = html
            if (submissionStatus == "new") submissionStatus = "draft"
            if (loseSaveResponse) throw IOException("response lost after commit")
        }

        override suspend fun buildFileDraft(plan: AssignmentFileDraft.Plan, maxBytesPerFile: Long): Long {
            builtPlans += plan
            val id = 1000L + builtPlans.size
            drafts[id] = plan.keep + listOfNotNull(
                plan.add?.let {
                    MoodleFile(
                        filename = it.filename,
                        filepath = "/",
                        filesize = it.sizeBytes,
                        fileurl = "https://lms.ritsumei.ac.jp/pluginfile.php/1/${it.filename}",
                    )
                },
            )
            return id
        }

        override suspend fun saveFiles(assignmentId: Long, draftItemId: Long) {
            failSaveFiles?.let { throw it }
            savedDraftIds += draftItemId
            serverFiles = drafts.getValue(draftItemId)
            if (submissionStatus == "new") submissionStatus = "draft"
            if (loseSaveFilesResponse) throw IOException("response lost after commit")
        }

        override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) {
            failSubmit?.let { throw it }
            submitted++
            submissionStatus = "submitted"
            if (loseSubmitResponse) throw IOException("response lost after commit")
        }
    }

    private fun editor(gateway: FakeGateway) = AssignmentEditor(gateway, courseId = 1, moduleId = 7, assignId = 42)

    private fun serverFile(name: String, path: String = "/", size: Long = 10) =
        MoodleFile(filename = name, filepath = path, filesize = size, fileurl = "https://lms.ritsumei.ac.jp/pluginfile.php/1$path$name")

    private inline fun <T> withTemp(prefix: String, content: String = "x", block: (File) -> T): T {
        val f = File.createTempFile(prefix, ".pdf").apply { writeText(content) }
        try {
            return block(f)
        } finally {
            f.delete()
        }
    }

    // ---- 草稿保護 ----

    @Test
    fun `refresh keeps unsaved draft unless discarded`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>server</p>" }
        val editor = editor(gateway)
        editor.refresh()
        assertEquals("server", editor.state.value.draftText)
        assertFalse(editor.state.value.isDirty)

        editor.updateText("edited")
        editor.refresh()
        assertEquals("edited", editor.state.value.draftText)
        assertTrue(editor.state.value.isDirty)

        editor.refresh(discardDraft = true)
        assertEquals("server", editor.state.value.draftText)
        assertFalse(editor.state.value.isDirty)
    }

    @Test
    fun `restored draft survives initial load when it differs from server`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>server</p>" }
        val editor = editor(gateway)
        editor.restoreDraft("typed before process death")
        editor.refresh()
        assertEquals("typed before process death", editor.state.value.draftText)
        assertTrue(editor.state.value.isDirty)
    }

    @Test
    fun `save converts plain text to html and verifies server echo`() = runBlocking {
        val gateway = FakeGateway()
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("a < b\nline2\n\npara2 & more")

        assertTrue(editor.saveText())

        assertEquals("<p>a &lt; b<br />line2</p><p>para2 &amp; more</p>", gateway.savedHtml.single())
        assertFalse(editor.state.value.isDirty)
        assertEquals("提出内容を保存しました", editor.state.value.notice)
        assertNull(editor.state.value.error)
    }

    @Test
    fun `save normalizes trailing whitespace so no dirty state remains`() = runBlocking {
        val gateway = FakeGateway()
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("line one   \n\n\n\nline two\n")

        assertTrue(editor.saveText())

        assertEquals("line one\n\nline two", editor.state.value.draftText)
        assertFalse("正規化後の差が dirty として残らない", editor.state.value.isDirty)
    }

    @Test
    fun `save rejected by warning keeps draft and shows reason`() = runBlocking {
        val gateway = FakeGateway().apply {
            failSave = AssignmentSubmissionRejectedException(listOf(WsWarning(warningcode = "couldnotsavesubmission", message = "Deadline passed")))
        }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("late text")

        assertFalse(editor.saveText())

        assertEquals("late text", editor.state.value.draftText)
        assertTrue(editor.state.value.isDirty)
        assertTrue(editor.state.value.error!!.contains("Deadline passed"))
        assertNotNull(editor.state.value.data)
        assertNull("明示的な拒否は結果不明ではない", editor.state.value.pendingWrite)
    }

    @Test
    fun `save mismatch with server content is reported not hidden`() = runBlocking {
        val gateway = FakeGateway().apply { serverEchoesSave = false; serverHtml = "<p>old</p>" }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("new")

        assertFalse(editor.saveText())
        assertTrue(editor.state.value.error!!.contains("一致しません"))
        assertTrue("受理済み・未反映として保持", editor.state.value.pendingWrite?.acknowledged == true)
        assertTrue(editor.state.value.isDirty)
    }

    @Test
    fun `rich existing text blocks plain text overwrite`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p><span style=\"color:red\">see</span></p>" }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("plain")

        assertFalse(editor.saveText())
        assertTrue(gateway.savedHtml.isEmpty())
        assertTrue(editor.state.value.error!!.contains("Moodleで編集"))
    }

    // ---- 最終提出 ----

    @Test
    fun `final submit saves dirty text first and aborts when save fails`() = runBlocking {
        val gateway = FakeGateway().apply { failSave = IOException("offline") }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("unsaved")

        assertFalse(editor.finalSubmit(acceptStatement = true))

        assertEquals(0, gateway.submitted)
        assertEquals("unsaved", editor.state.value.draftText)
    }

    @Test
    fun `final submit saves then submits and confirms submitted status`() = runBlocking {
        val gateway = FakeGateway()
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("final text")

        assertTrue(editor.finalSubmit(acceptStatement = true))

        assertEquals(listOf("<p>final text</p>"), gateway.savedHtml)
        assertEquals(1, gateway.submitted)
        assertEquals("submitted", gateway.submissionStatus)
        assertEquals("最終提出しました", editor.state.value.notice)
    }

    @Test
    fun `final submit not reflected in status is reported`() = runBlocking {
        val gateway = FakeGateway()
        // submit succeeds on the wire but the server status never becomes submitted
        val stubborn = object : AssignmentGateway by gateway {
            override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) { gateway.submitted++ }
        }
        val editor = AssignmentEditor(stubborn, 1, 7, 42)
        editor.refresh()

        assertFalse(editor.finalSubmit(acceptStatement = false))
        assertEquals(1, gateway.submitted)
        assertTrue(editor.state.value.error!!.contains("提出済み"))
    }

    /** T03: 応答消失後の再送禁止 */
    @Test
    fun `lost final submit response is reconciled and not resent`() = runBlocking {
        val gateway = FakeGateway().apply { loseSubmitResponse = true }
        val editor = editor(gateway)
        editor.refresh()

        assertTrue("照会で適用済みと分かれば成功扱い", editor.finalSubmit(false))
        assertNull(editor.state.value.pendingWrite)
        assertEquals("最終提出しました", editor.state.value.notice)

        assertFalse(editor.finalSubmit(false))
        assertEquals("second action must not repeat a write already applied", 1, gateway.submitted)
        assertTrue(editor.state.value.error!!.contains("既に最終提出"))
    }

    @Test
    fun `lost response with reconcile failure blocks further writes until reconciled`() = runBlocking {
        val gateway = FakeGateway().apply { loseSubmitResponse = true }
        val editor = editor(gateway)
        editor.refresh()
        // 送信後の照会も失敗する (通信断)
        gateway.failStatus = IOException("still offline")

        assertFalse(editor.finalSubmit(false))
        assertTrue(editor.state.value.pendingWrite is PendingWrite.Submit)
        assertTrue(editor.state.value.outcomeUnknown)

        // 結果不明のまま別の書き込みを出しても再送しない
        editor.updateText("x")
        assertFalse(editor.saveText())
        assertTrue(gateway.savedHtml.isEmpty())
        assertEquals(1, gateway.submitted)
        assertTrue(editor.state.value.error!!.contains("結果が未確認"))

        // 通信復帰後の照会で確定する
        gateway.failStatus = null
        assertTrue(editor.reconcile())
        assertNull(editor.state.value.pendingWrite)
        assertEquals(1, gateway.submitted)
    }

    @Test
    fun `lost save response that was not applied allows retry after reconcile`() = runBlocking {
        val gateway = FakeGateway()
        // 送信は失敗 (サーバー未適用) かつ応答は IOException
        val flaky = object : AssignmentGateway by gateway {
            var calls = 0
            override suspend fun saveOnlineText(assignmentId: Long, html: String) {
                calls++
                if (calls == 1) throw IOException("connection reset before commit")
                gateway.saveOnlineText(assignmentId, html)
            }
        }
        val editor = AssignmentEditor(flaky, 1, 7, 42)
        editor.refresh()
        editor.updateText("text")

        assertFalse(editor.saveText())
        // S01: 直後の読み取りが旧状態でも「未適用」とは断定しない → 保持し、再送しない
        val pending = editor.state.value.pendingWrite
        assertTrue(pending is PendingWrite.SaveText && !pending.acknowledged)
        assertTrue(editor.state.value.error!!.contains("再送しません"))
        assertTrue(editor.state.value.isDirty)
        assertFalse(editor.saveText())
        assertEquals(1, flaky.calls)

        // 利用者が Moodle で確認して解除 → 最新状態を取り直し、再送できる
        editor.dismissPendingWrite()
        assertNull(editor.state.value.pendingWrite)
        assertTrue(editor.state.value.isDirty)
        assertTrue(editor.saveText())
        assertEquals(listOf("<p>text</p>"), gateway.savedHtml)
    }

    // ---- S01: 応答成功/消失 × 照会失敗/旧状態/適用済み ----

    /** 監査プローブ acknowledgedWriteWithOldStatusMustRemainPending */
    @Test
    fun `acknowledged write with old status must remain pending`() = runBlocking {
        val gateway = FakeGateway()
        // 成功応答だが status はまだ draft のまま (サーバー処理中)
        val slow = object : AssignmentGateway by gateway {
            override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) { gateway.submitted++ }
        }
        val e = AssignmentEditor(slow, 1, 7, 42)
        e.refresh()
        assertFalse(e.finalSubmit(false))
        assertTrue(e.state.value.pendingWrite?.acknowledged == true)
        assertFalse(e.finalSubmit(false))
        assertEquals("successful response plus old status must not allow a second submit", 1, gateway.submitted)

        // 後からサーバーが反映 → 照会で解除
        gateway.submissionStatus = "submitted"
        assertTrue(e.reconcile())
        assertNull(e.state.value.pendingWrite)
        assertTrue(e.state.value.notice!!.contains("反映されていました"))
    }

    /** 監査プローブ lostResponseWithOldStatusMustRemainPending */
    @Test
    fun `lost response with old status must remain pending`() = runBlocking {
        val gateway = FakeGateway()
        val lossy = object : AssignmentGateway by gateway {
            override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) {
                gateway.submitted++
                throw IOException("response lost while server processing")
            }
        }
        val e = AssignmentEditor(lossy, 1, 7, 42)
        e.refresh()
        assertFalse(e.finalSubmit(false))
        assertTrue(e.state.value.pendingWrite is PendingWrite.Submit)
        assertFalse(e.finalSubmit(false))
        assertEquals("old read cannot prove that the outstanding write was not applied", 1, gateway.submitted)
    }

    @Test
    fun `write outcome matrix never resends without explicit dismissal`() = runBlocking {
        // (応答成功 / 応答消失) × (照会失敗 / 旧状態 / 適用済み)
        data class Case(val lose: Boolean, val statusMode: String, val expectPending: Boolean, val expectSuccess: Boolean)
        val cases = listOf(
            Case(lose = false, statusMode = "fail", expectPending = true, expectSuccess = false),
            Case(lose = false, statusMode = "old", expectPending = true, expectSuccess = false),
            Case(lose = false, statusMode = "applied", expectPending = false, expectSuccess = true),
            Case(lose = true, statusMode = "fail", expectPending = true, expectSuccess = false),
            Case(lose = true, statusMode = "old", expectPending = true, expectSuccess = false),
            Case(lose = true, statusMode = "applied", expectPending = false, expectSuccess = true),
        )
        for (c in cases) {
            val gateway = FakeGateway()
            val g = object : AssignmentGateway by gateway {
                override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) {
                    gateway.submitted++
                    when (c.statusMode) {
                        "fail" -> gateway.failStatus = IOException("status unavailable")
                        "applied" -> gateway.submissionStatus = "submitted"
                    }
                    if (c.lose) throw IOException("lost")
                }
            }
            val e = AssignmentEditor(g, 1, 7, 42)
            e.refresh()
            assertEquals(c.toString(), c.expectSuccess, e.finalSubmit(false))
            assertEquals(c.toString(), c.expectPending, e.state.value.pendingWrite != null)
            if (c.expectPending) {
                assertEquals(c.toString(), !c.lose, e.state.value.pendingWrite!!.acknowledged)
            }
            e.finalSubmit(false)
            assertEquals("$c: 連打しても再送しない", 1, gateway.submitted)
        }
    }

    // ---- S02: プロセス復元で本文競合を忘れない ----

    /** 監査プローブ restoredDraftMustNotForgetDetectedExternalConflict (旧形式: 草稿だけの復元) */
    @Test
    fun `restored draft must not forget detected external conflict`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>original</p>" }
        val original = editor(gateway)
        original.refresh()
        original.updateText("local unsaved")
        gateway.serverHtml = "<p>new web content</p>"
        original.refresh()
        assertTrue(original.state.value.textConflict)

        // 草稿だけを復元 (旧形式)
        val restored = editor(gateway)
        restored.restoreDraft(original.state.value.draftText)
        restored.refresh()
        assertFalse(restored.saveText())
        assertEquals("process restoration must preserve conflict protection", 0, gateway.savedHtml.size)
        assertTrue(restored.state.value.textConflict)
    }

    @Test
    fun `restored draft with baseline distinguishes web change from no change`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>original</p>" }
        val original = editor(gateway)
        original.refresh()
        original.updateText("local unsaved")
        val snapshot = AssignmentEditor.RestoredDraft(
            text = original.state.value.draftText,
            baseline = original.state.value.savedText,
            conflict = original.state.value.textConflict,
        )

        // Web 側変更なし → 競合ではなく、通常の未保存草稿として保存できる
        val noChange = editor(gateway)
        noChange.restoreDraft(snapshot)
        noChange.refresh()
        assertFalse(noChange.state.value.textConflict)
        assertTrue(noChange.saveText())
        assertEquals(listOf("<p>local unsaved</p>"), gateway.savedHtml)

        // Web 側変更あり (編集開始時の基準値と違う) → 競合として保存・最終提出を止める
        gateway.savedHtml.clear()
        gateway.serverHtml = "<p>changed on web</p>"
        val changed = editor(gateway)
        changed.restoreDraft(snapshot)
        changed.refresh()
        assertTrue(changed.state.value.textConflict)
        assertEquals("local unsaved", changed.state.value.draftText)
        assertFalse(changed.saveText())
        assertFalse(changed.finalSubmit(false))
        assertEquals(0, gateway.savedHtml.size)
        assertEquals(0, gateway.submitted)

        // 再生成前に検出済みの競合はそのまま復元される
        val detected = editor(gateway)
        detected.restoreDraft(snapshot.copy(conflict = true, baseline = "changed on web"))
        detected.refresh()
        assertTrue(detected.state.value.textConflict)
    }

    @Test
    fun `restored pending write is settled on first refresh and blocks writes until then`() = runBlocking {
        val gateway = FakeGateway()
        val e = editor(gateway)
        e.restorePendingWrite(PendingWrite.Submit(acknowledged = true))
        gateway.failStatus = IOException("offline")
        e.refresh()
        assertTrue(e.state.value.pendingWrite != null)
        assertFalse(e.finalSubmit(false))
        assertEquals(0, gateway.submitted)

        gateway.failStatus = null
        gateway.submissionStatus = "submitted"
        assertTrue(e.reconcile())
        assertNull(e.state.value.pendingWrite)
        assertFalse(e.finalSubmit(false))
        assertEquals(0, gateway.submitted)

        // 直列化の往復
        val encoded = PendingWrite.encode(PendingWrite.ChangeFiles(setOf(SubmissionFileFingerprint(SubmissionFileKey.root("a.pdf"), 3)), acknowledged = false))
        val decoded = PendingWrite.decode(encoded)
        assertTrue(decoded is PendingWrite.ChangeFiles && decoded.expected.single().sizeBytes == 3L)
        assertNull(PendingWrite.decode("garbage"))
    }

    @Test
    fun `malformed write response is treated as unknown then reconciled`() = runBlocking {
        val gateway = FakeGateway()
        val malformed = object : AssignmentGateway by gateway {
            override suspend fun saveFiles(assignmentId: Long, draftItemId: Long) {
                gateway.saveFiles(assignmentId, draftItemId)
                throw MoodleResponseParseException("warnings is not an array")
            }
        }
        val editor = AssignmentEditor(malformed, 1, 7, 42)
        editor.refresh()

        val ok = withTemp("upload-a") { f -> editor.addFile(AssignmentFileDraft.LocalFile(f, "A.pdf")) }
        assertTrue("照会でサーバーに反映済みと分かれば成功", ok)
        assertEquals(listOf("A.pdf"), gateway.serverFiles.map { it.filename })
    }

    // ---- ファイル ----

    @Test
    fun `adding a file keeps existing files with their paths in the draft`() = runBlocking {
        val gateway = FakeGateway().apply {
            serverFiles = listOf(serverFile("report.pdf", "/chapter1/"), serverFile("report.pdf", "/chapter2/"))
            assignment = assignment.copy(configs = assignment.configs.filter { it.name != "maxfilesubmissions" })
        }
        val editor = editor(gateway)
        editor.refresh()

        val ok = withTemp("upload-b", "bbbbb") { f -> editor.addFile(AssignmentFileDraft.LocalFile(f, "B.pdf")) }
        assertTrue(ok)

        val plan = gateway.builtPlans.single()
        assertEquals(
            listOf("/chapter1/" to "report.pdf", "/chapter2/" to "report.pdf"),
            plan.keep.map { it.filepath to it.filename },
        )
        assertEquals("B.pdf", plan.add?.filename)
        assertEquals(listOf(1001L), gateway.savedDraftIds)
        assertEquals(
            listOf("/chapter1/report.pdf", "/chapter2/report.pdf", "/B.pdf"),
            editor.state.value.data!!.ui.files.map { it.filepath + it.filename },
        )
        assertNull(editor.state.value.error)
    }

    /** T02: 同名置換はサイズまで検証する */
    @Test
    fun `same name replacement must verify more than filename`() = runBlocking {
        val gateway = FakeGateway().apply { serverFiles = listOf(serverFile("report.pdf", size = 3)) }
        // save は「成功」するがサーバーは古いファイルのまま
        val stale = object : AssignmentGateway by gateway {
            override suspend fun saveFiles(assignmentId: Long, draftItemId: Long) {
                gateway.savedDraftIds += draftItemId
            }
        }
        val editor = AssignmentEditor(stale, 1, 7, 42)
        editor.refresh()

        val ok = withTemp("review-replacement-", "replacement has different size") { f ->
            editor.addFile(AssignmentFileDraft.LocalFile(f, "report.pdf"))
        }
        assertFalse(ok)
        assertTrue(editor.state.value.error!!.contains("サイズ"))
        assertEquals("report.pdf", gateway.builtPlans.single().replaced?.filename)
    }

    @Test
    fun `adding beyond max file count is refused before any upload`() = runBlocking {
        val gateway = FakeGateway().apply { serverFiles = listOf(serverFile("A.pdf"), serverFile("B.pdf")) }
        val editor = editor(gateway)
        editor.refresh()

        val ok = withTemp("upload-c") { f -> editor.addFile(AssignmentFileDraft.LocalFile(f, "C.pdf")) }
        assertFalse(ok)
        assertTrue(gateway.builtPlans.isEmpty())
        assertTrue(editor.state.value.error!!.contains("最大2件"))
    }

    @Test
    fun `removing a file rebuilds the draft without it and can remove the last one`() = runBlocking {
        val gateway = FakeGateway().apply { serverFiles = listOf(serverFile("A.pdf"), serverFile("B.pdf")) }
        val editor = editor(gateway)
        editor.refresh()

        assertTrue(editor.removeFile(SubmissionFileKey("/", "A.pdf")))
        assertEquals(listOf("B.pdf"), gateway.builtPlans.last().keep.map { it.filename })
        assertEquals(listOf("B.pdf"), editor.state.value.data!!.ui.files.map { it.filename })

        assertTrue(editor.removeFile(SubmissionFileKey("/", "B.pdf")))
        assertTrue(gateway.builtPlans.last().keep.isEmpty())
        assertTrue(editor.state.value.data!!.ui.files.isEmpty())
    }

    @Test
    fun `file change is refused when server files changed since the screen was loaded`() = runBlocking {
        val gateway = FakeGateway().apply { serverFiles = listOf(serverFile("A.pdf")) }
        val editor = editor(gateway)
        editor.refresh()
        // 別端末/Webで B が追加された
        gateway.serverFiles = gateway.serverFiles + serverFile("B.pdf")

        val ok = withTemp("upload-c") { f -> editor.addFile(AssignmentFileDraft.LocalFile(f, "C.pdf")) }
        assertFalse(ok)
        assertTrue(gateway.builtPlans.isEmpty())
        assertTrue(editor.state.value.error!!.contains("サーバー側で提出ファイルが変更"))
        assertEquals(listOf("A.pdf", "B.pdf"), editor.state.value.data!!.ui.files.map { it.filename })
    }

    @Test
    fun `download failure while preparing draft is a normal failure without pending write`() = runBlocking {
        val gateway = FakeGateway().apply { serverFiles = listOf(serverFile("A.pdf")) }
        val failing = object : AssignmentGateway by gateway {
            override suspend fun buildFileDraft(plan: AssignmentFileDraft.Plan, maxBytesPerFile: Long): Long =
                throw IOException("既存ファイル A.pdf の取得サイズが一致しません")
        }
        val editor = AssignmentEditor(failing, 1, 7, 42)
        editor.refresh()

        val ok = withTemp("upload-b") { f -> editor.addFile(AssignmentFileDraft.LocalFile(f, "B.pdf")) }
        assertFalse(ok)
        assertNull(editor.state.value.pendingWrite)
        assertTrue(editor.state.value.error!!.contains("ファイルの準備に失敗"))
        assertEquals(listOf("A.pdf"), editor.state.value.data!!.ui.files.map { it.filename })
    }

    @Test
    fun `file change keeps unsaved text draft`() = runBlocking {
        val gateway = FakeGateway()
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("still typing")

        val ok = withTemp("upload-b") { f -> editor.addFile(AssignmentFileDraft.LocalFile(f, "B.pdf")) }
        assertTrue(ok)
        assertEquals("still typing", editor.state.value.draftText)
        assertTrue(editor.state.value.isDirty)
    }

    /** N02: ファイル準備中 (コピー中) は他の書き込みを受け付けない */
    @Test
    fun `final submit during file preparation is rejected as busy`() = runBlocking {
        val gateway = FakeGateway()
        val editor = editor(gateway)
        editor.refresh()
        val gate = CompletableDeferred<Unit>()
        val adding = async {
            editor.addFile {
                gate.await()
                withTemp("upload-b") { f -> AssignmentFileDraft.LocalFile(f, "B.pdf") }
            }
        }
        yield()
        assertEquals(EditorPhase.PreparingFile, editor.state.value.phase)
        assertTrue(editor.state.value.busy)

        assertFalse(editor.finalSubmit(false))
        assertEquals(0, gateway.submitted)

        gate.complete(Unit)
        assertTrue(adding.await())
        assertEquals(EditorPhase.Idle, editor.state.value.phase)
    }

    // ---- 再読込・照会 ----

    @Test
    fun `refresh failure after data is loaded keeps data and shows error`() = runBlocking {
        val gateway = FakeGateway()
        val editor = editor(gateway)
        editor.refresh()
        gateway.failStatus = IOException("offline")

        editor.refresh()

        assertTrue(editor.state.value.load is AssignmentLoad.Ready)
        assertTrue(editor.state.value.error!!.contains("再読込に失敗"))
    }

    @Test
    fun `status failure before save sends nothing and keeps draft dirty`() = runBlocking {
        val gateway = FakeGateway()
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("text")
        gateway.failStatus = IOException("connection reset")

        assertFalse(editor.saveText())

        assertTrue("保存前の再照会に失敗したら送らない", gateway.savedHtml.isEmpty())
        assertTrue(editor.state.value.error!!.contains("保存前の状態確認"))
        assertEquals("text", editor.state.value.draftText)
        assertTrue(editor.state.value.isDirty)
        assertNull(editor.state.value.pendingWrite)
    }

    // ---- R01: 成功応答の後に照会だけ失敗 ----

    /** 監査プローブ acknowledgedSubmitWithFailedReloadMustNotResubmit */
    @Test
    fun `acknowledged submit with failed reload must not resubmit`() = runBlocking {
        val gateway = FakeGateway()
        // 提出は適用されて正常応答、その後の状態取得だけ失敗する
        val lossy = object : AssignmentGateway by gateway {
            override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) {
                gateway.submitForGrading(assignmentId, acceptStatement)
                gateway.failStatus = IOException("status unavailable")
            }
        }
        val e = AssignmentEditor(lossy, 1, 7, 42)
        e.refresh()

        assertFalse(e.finalSubmit(false))
        val pending = e.state.value.pendingWrite
        assertTrue(pending is PendingWrite.Submit && pending.acknowledged)
        assertTrue(e.state.value.error!!.contains("受理"))

        assertFalse(e.finalSubmit(false))
        assertEquals("successful write then failed read must lock further writes", 1, gateway.submitted)

        // 照会が復旧すれば適用済みとして確定し、その後も再送しない
        gateway.failStatus = null
        assertTrue(e.reconcile())
        assertNull(e.state.value.pendingWrite)
        assertFalse(e.finalSubmit(false))
        assertEquals(1, gateway.submitted)
        assertTrue(e.state.value.error!!.contains("既に最終提出"))
    }

    @Test
    fun `acknowledged save with failed reload locks writes and settles on reconcile`() = runBlocking {
        val gateway = FakeGateway()
        val lossy = object : AssignmentGateway by gateway {
            override suspend fun saveOnlineText(assignmentId: Long, html: String) {
                gateway.saveOnlineText(assignmentId, html)
                gateway.failStatus = IOException("status unavailable")
            }
        }
        val e = AssignmentEditor(lossy, 1, 7, 42)
        e.refresh()
        e.updateText("text")

        assertFalse(e.saveText())
        assertTrue(e.state.value.pendingWrite is PendingWrite.SaveText)
        assertTrue(e.state.value.isDirty)

        // 結果未確認のままファイル変更も受け付けない
        assertFalse(e.removeFile(SubmissionFileKey.root("none")))
        assertTrue(gateway.builtPlans.isEmpty())

        gateway.failStatus = null
        assertTrue(e.reconcile())
        assertNull(e.state.value.pendingWrite)
        assertFalse("適用済みが確認できたら草稿も一致", e.state.value.isDirty)
        assertEquals(listOf("<p>text</p>"), gateway.savedHtml)
    }

    @Test
    fun `acknowledged write that still reads as not applied stays pending until explicitly dismissed`() = runBlocking {
        val gateway = FakeGateway().apply { serverEchoesSave = false }
        // 保存の成功応答の後、照会は成功するが本文は反映されていない (サーバー遅延を想定)
        val lossy = object : AssignmentGateway by gateway {
            var first = true
            override suspend fun saveOnlineText(assignmentId: Long, html: String) {
                gateway.saveOnlineText(assignmentId, html)
                if (first) { first = false; gateway.failStatus = IOException("status unavailable") }
            }
        }
        val e = AssignmentEditor(lossy, 1, 7, 42)
        e.refresh()
        e.updateText("text")
        assertFalse(e.saveText())
        gateway.failStatus = null

        assertTrue(e.reconcile())
        assertTrue("受理済みで未反映なら再送可能とは断定しない", e.state.value.pendingWrite?.acknowledged == true)
        assertFalse(e.saveText())
        assertEquals(1, gateway.savedHtml.size)

        e.dismissPendingWrite()
        assertNull(e.state.value.pendingWrite)
    }

    // ---- R02: ファイル操作後の本文基準値 ----

    /** 監査プローブ changingFilesMustNotTurnOldTextIntoADraftAndOverwriteWebEdit */
    @Test
    fun `changing files must not turn old text into a draft and overwrite web edit`() = runBlocking {
        val gateway = FakeGateway().apply {
            serverHtml = "<p>original</p>"
            serverFiles = listOf(serverFile("A.pdf", size = 3))
        }
        val editor = editor(gateway)
        editor.refresh()
        gateway.serverHtml = "<p>changed on web</p>"

        assertTrue(editor.removeFile(SubmissionFileKey.root("A.pdf")))
        assertFalse("未編集なら草稿も新しい本文に揃う", editor.state.value.isDirty)
        assertEquals("changed on web", editor.state.value.draftText)

        assertTrue(editor.finalSubmit(false))
        assertEquals("file-only action must preserve externally updated text", "<p>changed on web</p>", gateway.serverHtml)
        assertTrue(gateway.savedHtml.isEmpty())
    }

    @Test
    fun `edited text plus web change after file operation becomes a conflict and blocks submit`() = runBlocking {
        val gateway = FakeGateway().apply {
            serverHtml = "<p>original</p>"
            serverFiles = listOf(serverFile("A.pdf", size = 3))
        }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("my local edit")
        gateway.serverHtml = "<p>changed on web</p>"

        assertTrue(editor.removeFile(SubmissionFileKey.root("A.pdf")))
        assertEquals("my local edit", editor.state.value.draftText)
        assertTrue(editor.state.value.textConflict)

        assertFalse(editor.finalSubmit(false))
        assertEquals(0, gateway.submitted)
        assertTrue(gateway.savedHtml.isEmpty())
        assertEquals("<p>changed on web</p>", gateway.serverHtml)

        // 再読込で破棄すれば解消
        editor.refresh(discardDraft = true)
        assertFalse(editor.state.value.textConflict)
        assertEquals("changed on web", editor.state.value.draftText)
    }

    // ---- R03: 保存前の外部更新チェック ----

    /** 監査プローブ saveMustRecheckExternalRichTextBeforeOverwriting */
    @Test
    fun `save must recheck external rich text before overwriting`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>original</p>" }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("local draft")
        gateway.serverHtml = "<p><strong>new web content</strong></p>"

        assertFalse(editor.saveText())
        assertEquals("external rich text must trigger conflict", 0, gateway.savedHtml.size)
        assertTrue(editor.state.value.textConflict)
        assertEquals("local draft", editor.state.value.draftText)
        assertTrue(editor.state.value.error!!.contains("Web側で本文が変更"))
    }

    @Test
    fun `save detects plain external change and permission loss before sending`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>original</p>" }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("local draft")
        gateway.serverHtml = "<p>plain change on web</p>"
        assertFalse(editor.saveText())
        assertEquals(0, gateway.savedHtml.size)
        assertTrue(editor.state.value.textConflict)

        // 権限が消えた場合も送らない
        val gateway2 = FakeGateway().apply { serverHtml = "<p>original</p>" }
        val editor2 = editor(gateway2)
        editor2.refresh()
        editor2.updateText("local draft")
        gateway2.submissionStatus = "submitted"
        assertFalse(editor2.saveText())
        assertEquals(0, gateway2.savedHtml.size)
        assertTrue(editor2.state.value.error!!.contains("編集できません"))
    }

    @Test
    fun `save skips sending when the same content was already saved elsewhere`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>original</p>" }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("same text")
        gateway.serverHtml = "<p>same text</p>"

        assertTrue(editor.saveText())
        assertEquals(0, gateway.savedHtml.size)
        assertFalse(editor.state.value.isDirty)
        assertFalse(editor.state.value.textConflict)
    }

    @Test
    fun `final submit auto save is protected by the same external change check`() = runBlocking {
        val gateway = FakeGateway().apply { serverHtml = "<p>original</p>" }
        val editor = editor(gateway)
        editor.refresh()
        editor.updateText("local draft")
        gateway.serverHtml = "<p>changed on web</p>"

        assertFalse(editor.finalSubmit(false))
        assertEquals(0, gateway.submitted)
        assertEquals(0, gateway.savedHtml.size)
        assertEquals("<p>changed on web</p>", gateway.serverHtml)
    }
}
