package dev.rits.bettermoodle

import androidx.lifecycle.SavedStateHandle
import dev.rits.bettermoodle.data.AssignmentDraftPersistence
import dev.rits.bettermoodle.data.AssignmentEditorState
import dev.rits.bettermoodle.data.AssignmentLoad
import dev.rits.bettermoodle.data.DraftStateStore
import dev.rits.bettermoodle.data.PendingWrite
import dev.rits.bettermoodle.ui.AssignmentViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIFTH_FOLLOWUP_AUDIT W01–W03 の回帰テスト (実 SavedStateHandle アダプターを通す) */
class FifthAuditRegressionTest {

    private fun adapter(handle: SavedStateHandle): DraftStateStore = AssignmentViewModel.SavedStateDraftStore(handle)

    private fun persistence(handle: SavedStateHandle, account: String?) =
        AssignmentDraftPersistence(adapter(handle), account)

    // ---- W01: 保存形式の移行 ----

    /** 監査プローブ previousBooleanConflictMustLoadThroughActualSavedStateAdapter */
    @Test
    fun `previous boolean conflict must load through the actual saved state adapter`() {
        val handle = SavedStateHandle(
            mapOf(
                AssignmentDraftPersistence.KEY_DRAFT to "unsaved text",
                AssignmentDraftPersistence.KEY_BASELINE to "original",
                AssignmentDraftPersistence.KEY_ACCOUNT to "account-a",
                // 旧版は Boolean で保存していた
                AssignmentDraftPersistence.KEY_CONFLICT to true,
            ),
        )
        val restored = persistence(handle, "account-a").load()
        assertEquals("unsaved text", restored?.draft)
        assertEquals("original", restored?.baseline)
        assertTrue("previous conflict must survive migration", restored!!.conflict)
        assertTrue(persistence(handle, "account-a").belongsToCurrentAccount(restored))
    }

    @Test
    fun `conflict flag migration covers boolean, string, missing and unknown types`() {
        fun conflictFor(value: Any?): Boolean {
            val map = mutableMapOf<String, Any?>(AssignmentDraftPersistence.KEY_DRAFT to "d")
            if (value != null) map[AssignmentDraftPersistence.KEY_CONFLICT] = value
            return persistence(SavedStateHandle(map), null).load()!!.conflict
        }
        assertTrue(conflictFor(true))
        assertFalse(conflictFor(false))
        assertTrue(conflictFor("1"))
        assertTrue(conflictFor("true"))
        assertFalse(conflictFor("0"))
        assertFalse("キーなしは競合なし", conflictFor(null))
        assertTrue("未知の型は保護側へ倒す", conflictFor(42))
    }

    @Test
    fun `legacy state without account is not treated as the current account and keeps its pending write readable`() {
        val handle = SavedStateHandle(
            mapOf(
                AssignmentDraftPersistence.KEY_DRAFT to "legacy",
                AssignmentDraftPersistence.KEY_CONFLICT to true,
                AssignmentDraftPersistence.KEY_PENDING to PendingWrite.encode(PendingWrite.Submit(acknowledged = true)),
            ),
        )
        val p = persistence(handle, "account-a")
        val saved = p.load()!!
        assertTrue(saved.isLegacy)
        assertFalse(p.belongsToCurrentAccount(saved))
        assertNotNull(saved.pending)

        // アカウント不一致も本人扱いしない。未ログイン (accountId=null) でも本人扱いしない
        val mismatch = SavedStateHandle(
            mapOf(
                AssignmentDraftPersistence.KEY_DRAFT to "theirs",
                AssignmentDraftPersistence.KEY_ACCOUNT to "account-b",
            ),
        )
        assertFalse(persistence(mismatch, "account-a").let { it.belongsToCurrentAccount(it.load()!!) })
        assertFalse(persistence(mismatch, null).let { it.belongsToCurrentAccount(it.load()!!) })
    }

    @Test
    fun `new format round trips through the adapter`() {
        val handle = SavedStateHandle()
        val p = persistence(handle, "account-a")
        p.save(
            AssignmentEditorState(
                load = AssignmentLoad.Loading,
                draftText = "typing",
            ),
        )
        assertEquals("typing", p.load()?.draft)
        assertEquals(AssignmentDraftPersistence.FORMAT_VERSION, handle.get<String>(AssignmentDraftPersistence.KEY_FORMAT))
        p.clear()
        assertNull(p.load())
        assertNull(handle.get<String>(AssignmentDraftPersistence.KEY_FORMAT))
    }

    // ---- W02: 保留草稿の寿命 ----

    @Test
    fun `orphaned draft survives a new persistence instance until adopted or discarded`() {
        val handle = SavedStateHandle(
            mapOf(
                AssignmentDraftPersistence.KEY_DRAFT to "legacy text",
                AssignmentDraftPersistence.KEY_CONFLICT to true,
            ),
        )
        val first = persistence(handle, "account-a")
        first.moveToOrphan(first.load()!!, nowMillis = 1_700_000_000_000)
        // 編集領域は消え、保留領域に残る
        assertNull(first.load())
        assertEquals("legacy text", first.loadOrphan()?.text)

        // 新しいインスタンス (= プロセス再生成後の新しい ViewModel) でも保留は見える
        val second = persistence(handle, "account-a")
        val orphan = second.loadOrphan()!!
        assertEquals("legacy text", orphan.text)
        assertEquals(1_700_000_000_000, orphan.savedAtMillis)
        assertNull("旧形式は所有者不明", orphan.ownerAccount)
        assertFalse(orphan.isKnownMismatch("account-a"))

        // 編集領域の保存・消去では保留は消えない
        second.save(AssignmentEditorState(load = AssignmentLoad.Loading, draftText = "new typing"))
        assertNotNull(second.loadOrphan())
        second.clear()
        assertNotNull(second.loadOrphan())

        // 明示的な破棄でだけ消える
        second.clearOrphan()
        assertNull(second.loadOrphan())
    }

    @Test
    fun `orphan keeps the owner account so the original account can recover it`() {
        val handle = SavedStateHandle(
            mapOf(
                AssignmentDraftPersistence.KEY_DRAFT to "A's text",
                AssignmentDraftPersistence.KEY_ACCOUNT to "account-a",
            ),
        )
        // B でログインした状態で退避
        val asB = persistence(handle, "account-b")
        asB.moveToOrphan(asB.load()!!, nowMillis = 1)
        val orphanForB = asB.loadOrphan()!!
        assertTrue("B からは所有者不一致", orphanForB.isKnownMismatch("account-b"))
        assertFalse(orphanForB.belongsTo("account-b"))

        // A が戻ってくれば自分のものとして扱える
        val asA = persistence(handle, "account-a")
        val orphanForA = asA.loadOrphan()!!
        assertFalse(orphanForA.isKnownMismatch("account-a"))
        assertTrue(orphanForA.belongsTo("account-a"))
    }

    @Test
    fun `blank draft is not kept as an orphan`() {
        val handle = SavedStateHandle(
            mapOf(
                AssignmentDraftPersistence.KEY_DRAFT to "   ",
                AssignmentDraftPersistence.KEY_ACCOUNT to "account-b",
            ),
        )
        val p = persistence(handle, "account-a")
        p.moveToOrphan(p.load()!!, nowMillis = 1)
        assertNull(p.loadOrphan())
        assertNull(p.load())
    }

    // ---- W03: 本人確認前に本文を出さない ----

    @Test
    fun `orphan info exposed to the ui carries no text and blocks adoption on mismatch`() {
        val mismatch = dev.rits.bettermoodle.ui.OrphanedDraftInfo(ownerMismatch = true, savedAtMillis = 1, characters = 42)
        assertFalse("別アカウント由来は取り込ませない", mismatch.canAdopt)
        val unknownOwner = dev.rits.bettermoodle.ui.OrphanedDraftInfo(ownerMismatch = false, savedAtMillis = null, characters = 42)
        assertTrue(unknownOwner.canAdopt)
        // 画面へ渡す型に本文は含まれない (コンパイル時に保証される)
        assertEquals(42, mismatch.characters)
    }
}
