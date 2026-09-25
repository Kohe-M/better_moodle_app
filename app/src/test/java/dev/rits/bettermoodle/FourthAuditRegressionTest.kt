package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.Assignment
import dev.rits.bettermoodle.data.AssignmentConfig
import dev.rits.bettermoodle.data.AssignmentDraftPersistence
import dev.rits.bettermoodle.data.AssignmentEditor
import dev.rits.bettermoodle.data.AssignmentEditorState
import dev.rits.bettermoodle.data.AssignmentFileDraft
import dev.rits.bettermoodle.data.AssignmentGateway
import dev.rits.bettermoodle.data.AssignmentLastAttempt
import dev.rits.bettermoodle.data.AssignmentSubmission
import dev.rits.bettermoodle.data.DraftStateStore
import dev.rits.bettermoodle.data.PendingWrite
import dev.rits.bettermoodle.data.SubmissionEditorField
import dev.rits.bettermoodle.data.SubmissionPlugin
import dev.rits.bettermoodle.data.SubmissionStatusResponse
import dev.rits.bettermoodle.ui.htmlHasRenderableContent
import dev.rits.bettermoodle.ui.htmlToAnnotatedString
import dev.rits.bettermoodle.ui.htmlToPlainText
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
import java.io.IOException

/** FOURTH_FOLLOWUP_AUDIT V01–V04 の回帰テスト */
class FourthAuditRegressionTest {

    private class Gateway : AssignmentGateway {
        var html = "<p>original</p>"
        var status = "draft"
        var submits = 0
        var saves = 0
        var offline = false
        var submitGate: CompletableDeferred<Unit>? = null
        var applyOnSubmit = true
        override suspend fun loadAssignment(courseId: Long, moduleId: Long, assignId: Long) = Assignment(
            id = 42, cmid = 7, course = 1,
            configs = listOf(AssignmentConfig("onlinetext", "assignsubmission", "enabled", "1")),
        )
        override suspend fun submissionStatus(assignId: Long): SubmissionStatusResponse {
            if (offline) throw IOException("offline")
            return SubmissionStatusResponse(
                lastattempt = AssignmentLastAttempt(
                    submissionsenabled = true, canedit = status != "submitted", cansubmit = status != "submitted",
                    submission = AssignmentSubmission(
                        status = status,
                        plugins = listOf(SubmissionPlugin(type = "onlinetext", editorfields = listOf(SubmissionEditorField(name = "onlinetext", text = html)))),
                    ),
                ),
            )
        }
        override suspend fun startSubmission(assignId: Long) {}
        override suspend fun saveOnlineText(assignmentId: Long, html: String) { saves++; this.html = html }
        override suspend fun buildFileDraft(plan: AssignmentFileDraft.Plan, maxBytesPerFile: Long) = 1L
        override suspend fun saveFiles(assignmentId: Long, draftItemId: Long) {}
        override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) {
            submits++
            submitGate?.await()
            if (applyOnSubmit) status = "submitted"
        }
    }

    /** テスト用の保存先 (SavedStateHandle の代わり) */
    private class MapStore : DraftStateStore {
        val map = mutableMapOf<String, String>()
        val history = mutableListOf<Map<String, String>>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
            history += map.toMap()
        }
    }

    private fun editorWith(gateway: Gateway, persistence: AssignmentDraftPersistence? = null) =
        AssignmentEditor(gateway, 1, 7, 42, onCheckpoint = { s -> persistence?.save(s) })

    // ---- V01 ----

    /** 監査プローブ inFlightSubmissionMustAlreadyOwnPendingWrite */
    @Test
    fun `in-flight submission must already own pending write and be persisted before send`() = runBlocking {
        val gateway = Gateway()
        val store = MapStore()
        val persistence = AssignmentDraftPersistence(store, accountId = "acct")
        val editor = editorWith(gateway, persistence)
        editor.refresh()
        val gate = CompletableDeferred<Unit>()
        gateway.submitGate = gate
        val job = async { editor.finalSubmit(false) }
        yield()
        val duringSend = editor.state.value.pendingWrite
        // 送信中に保存先へ書かれている内容 (= ここでプロセスが終了しても復元できる)
        val persistedDuringSend = persistence.load()
        gate.complete(Unit)
        job.await()

        assertNotNull("pending must exist while server write is in flight", duringSend)
        assertTrue(duringSend is PendingWrite.Submit && !duringSend.acknowledged)
        assertNotNull("pending must be persisted before send", persistedDuringSend?.pending)
        assertEquals("acct", persistedDuringSend?.account)
        // 適用確認後は保存先から消える
        assertNull(persistence.load()?.pending)
    }

    @Test
    fun `pending persisted before send is restored and blocks resend after process death`() = runBlocking {
        val gateway = Gateway().apply { applyOnSubmit = false }
        val store = MapStore()
        val persistence = AssignmentDraftPersistence(store, accountId = "acct")
        val editor = editorWith(gateway, persistence)
        editor.refresh()
        val gate = CompletableDeferred<Unit>()
        gateway.submitGate = gate
        val job = async { editor.finalSubmit(false) }
        yield()
        // 「送信待ち」時点の保存内容でプロセスが終了したとする
        val snapshot = store.map.toMap()
        gate.complete(Unit)
        job.await()

        val restoredStore = MapStore().apply { map.putAll(snapshot) }
        val restoredPersistence = AssignmentDraftPersistence(restoredStore, accountId = "acct")
        val saved = restoredPersistence.load()!!
        assertTrue(restoredPersistence.belongsToCurrentAccount(saved))
        val restored = editorWith(gateway, restoredPersistence)
        saved.pending?.let(restored::restorePendingWrite)
        restored.refresh() // status はまだ draft → 未反映として保持
        assertNotNull(restored.state.value.pendingWrite)
        assertFalse(restored.finalSubmit(false))
        assertEquals("restored pending must block a second submit", 1, gateway.submits)
    }

    @Test
    fun `checkpoints cover before send, after acknowledge and after settle`() = runBlocking {
        val gateway = Gateway()
        val store = MapStore()
        val persistence = AssignmentDraftPersistence(store, accountId = "acct")
        val editor = editorWith(gateway, persistence)
        editor.refresh()
        store.history.clear()
        editor.finalSubmit(false)
        val pendingSnapshots = store.history.map { PendingWrite.decode(it[AssignmentDraftPersistence.KEY_PENDING]) }
        assertTrue("送信前: 未確認 (acknowledged=false)", pendingSnapshots.any { it is PendingWrite.Submit && !it.acknowledged })
        assertTrue("応答後: 受理済み (acknowledged=true)", pendingSnapshots.any { it is PendingWrite.Submit && it.acknowledged })
        assertNull("確定後: 解除", persistence.load()?.pending)
    }

    // ---- V02 ----

    /** 監査プローブ releaseMustNotEnableWritesWhenLatestStateCannotBeLoaded */
    @Test
    fun `release must not enable writes when latest state cannot be loaded`() = runBlocking {
        val gateway = Gateway().apply { applyOnSubmit = false }
        val editor = editorWith(gateway)
        editor.refresh()
        editor.finalSubmit(false)
        assertNotNull(editor.state.value.pendingWrite)
        gateway.offline = true
        assertFalse(editor.dismissPendingWrite())
        assertNotNull("解除できないので未確認のまま", editor.state.value.pendingWrite)
        assertTrue(editor.state.value.error!!.contains("解除できません"))
        editor.finalSubmit(false)
        assertEquals("release failed to refresh but allowed another write using stale state", 1, gateway.submits)

        // 通信復帰後の解除は最新状態を取り込み、以降の操作は最新状態に基づく
        gateway.offline = false
        gateway.status = "submitted"
        assertTrue(editor.dismissPendingWrite())
        assertNull(editor.state.value.pendingWrite)
        assertFalse(editor.finalSubmit(false))
        assertEquals(1, gateway.submits)
    }

    // ---- V03 ----

    /** 監査プローブ imageOnlyIntroMustReachHtmlRenderer */
    @Test
    fun `image only intro must reach html renderer`() {
        val html = "<p><img src='https://lms.ritsumei.ac.jp/pluginfile.php/1/instructions.png' alt='課題の説明'></p>"
        assertTrue(htmlToAnnotatedString(html).text.isNotBlank())
        assertTrue("intro gate must not suppress content the renderer can display", htmlToPlainText(html).isNotBlank())
        assertTrue(htmlHasRenderableContent(html))
        assertEquals("[画像: 課題の説明]", htmlToPlainText(html))

        assertTrue(htmlHasRenderableContent("<p><img src='x.png'></p>"))
        assertEquals("[画像]", htmlToPlainText("<p><img src='x.png'></p>"))
        assertEquals("本文 [画像: 図]", htmlToPlainText("<p>本文<img src='x.png' alt='図'></p>"))
        assertFalse("空タグだけは表示なし", htmlHasRenderableContent("<p></p><div><br></div>"))
        assertTrue(htmlHasRenderableContent("<p><a href='https://example.com/'>リンク</a></p>"))
        assertFalse(htmlHasRenderableContent(null))
        assertEquals("一行目 二行目", htmlToPlainText("<p>一行目</p><p>二行目</p>"))
    }

    // ---- V04 ----

    @Test
    fun `persistence distinguishes legacy, foreign and own drafts`() {
        // 旧形式: KEY_DRAFT だけ
        val legacy = MapStore().apply { map[AssignmentDraftPersistence.KEY_DRAFT] = "old draft" }
        val legacyPersistence = AssignmentDraftPersistence(legacy, accountId = "acct")
        val legacySaved = legacyPersistence.load()!!
        assertTrue(legacySaved.isLegacy)
        assertFalse("旧形式は本人確認できないので無条件に復元しない", legacyPersistence.belongsToCurrentAccount(legacySaved))
        assertEquals("old draft", legacySaved.draft)

        // 別アカウント (トークン更新後の同一利用者を含む)
        val foreign = MapStore().apply {
            map[AssignmentDraftPersistence.KEY_DRAFT] = "their draft"
            map[AssignmentDraftPersistence.KEY_ACCOUNT] = "other"
            map[AssignmentDraftPersistence.KEY_PENDING] = PendingWrite.encode(PendingWrite.Submit(acknowledged = true))
        }
        val foreignPersistence = AssignmentDraftPersistence(foreign, accountId = "acct")
        val foreignSaved = foreignPersistence.load()!!
        assertFalse(foreignPersistence.belongsToCurrentAccount(foreignSaved))
        assertNotNull("草稿は保留として残す (黙って消さない)", foreignSaved.draft)

        // 本人
        val own = MapStore()
        val ownPersistence = AssignmentDraftPersistence(own, accountId = "acct")
        ownPersistence.save(
            AssignmentEditorState(
                load = dev.rits.bettermoodle.data.AssignmentLoad.Loading,
                draftText = "typing before load",
            ),
        )
        val early = ownPersistence.load()!!
        assertTrue(ownPersistence.belongsToCurrentAccount(early))
        assertNull("読込前は基準値不明", early.baseline)

        // 未ログイン (accountId=null) では自分のものとして扱わない
        val anon = AssignmentDraftPersistence(own, accountId = null)
        assertFalse(anon.belongsToCurrentAccount(anon.load()!!))
    }

    @Test
    fun `legacy draft adopted through the protective path becomes a conflict when web text differs`() = runBlocking {
        val gateway = Gateway().apply { html = "<p>changed on web</p>" }
        val editor = editorWith(gateway)
        editor.restoreDraft("old local draft") // 旧形式 (基準値なし)
        editor.refresh()
        assertEquals("old local draft", editor.state.value.draftText)
        assertTrue(editor.state.value.textConflict)
        assertFalse(editor.saveText())
        assertEquals(0, gateway.saves)

        // 差が無ければそのまま編集を続けられる
        val same = editorWith(Gateway().apply { html = "<p>same</p>" })
        same.restoreDraft("same")
        same.refresh()
        assertFalse(same.state.value.textConflict)
        assertFalse(same.state.value.isDirty)
    }
}
