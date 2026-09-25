package dev.rits.bettermoodle

import androidx.lifecycle.SavedStateHandle
import dev.rits.bettermoodle.data.AssignmentDraftPersistence
import dev.rits.bettermoodle.data.AssignmentEditorState
import dev.rits.bettermoodle.data.DraftCodec
import dev.rits.bettermoodle.data.FileDraftStore
import dev.rits.bettermoodle.data.LayeredDraftStore
import dev.rits.bettermoodle.ui.AssignmentViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/** G02: 課題の下書きを画面の寿命から切り離して端末に保存する */
class DraftStorageTest {

    private val dir: File = Files.createTempDirectory("drafts").toFile()

    /** 書き込みを明示的に流すための Executor */
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private object PlainCodec : DraftCodec {
        override fun encode(plain: String) = "enc:$plain"
        override fun decode(stored: String) = stored.removePrefix("enc:").takeIf { stored.startsWith("enc:") }
    }

    private val direct = Executor { it.run() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file() = FileDraftStore.fileFor(dir, moduleId = 7, assignId = 42)

    @Test
    fun `values survive a new store instance and the file is encoded`() {
        val store = FileDraftStore(file(), PlainCodec, direct)
        store["a"] = "本文"
        store["b"] = "1"

        assertTrue(file().readText().startsWith("enc:"))
        val reopened = FileDraftStore(file(), PlainCodec, direct)
        assertEquals("本文", reopened["a"])
        assertEquals("1", reopened["b"])
        assertEquals(1, FileDraftStore.count(dir))
    }

    @Test
    fun `file is deleted when every key is cleared`() {
        val store = FileDraftStore(file(), PlainCodec, direct)
        store["a"] = "x"
        store["a"] = null
        assertFalse(file().exists())
        assertEquals(0, FileDraftStore.count(dir))
    }

    @Test
    fun `only the latest snapshot is written when writes queue up`() {
        val writer = ManualExecutor()
        val store = FileDraftStore(file(), PlainCodec, writer)
        store["a"] = "1"
        store["a"] = "12"
        store["a"] = "123"
        // 読み取りは書き込み前でもメモリの最新値
        assertEquals("123", store["a"])
        writer.runAll()
        assertEquals("123", FileDraftStore(file(), PlainCodec, direct)["a"])
    }

    @Test
    fun `undecodable file is treated as empty`() {
        file().parentFile!!.mkdirs()
        file().writeText("garbage")
        assertNull(FileDraftStore(file(), PlainCodec, direct)["a"])
    }

    @Test
    fun `module id is preferred over the instance id for the file name`() {
        assertEquals("cm7.draft", FileDraftStore.fileFor(dir, 7, 42).name)
        assertEquals("a42.draft", FileDraftStore.fileFor(dir, 0, 42).name)
    }

    @Test
    fun `layered store reads legacy saved state values and writes both layers`() {
        val handle = SavedStateHandle(mapOf(AssignmentDraftPersistence.KEY_CONFLICT to true))
        val saved = AssignmentViewModel.SavedStateDraftStore(handle)
        val primary = FileDraftStore(file(), PlainCodec, direct)
        val layered = LayeredDraftStore(primary, saved)

        assertEquals(true, layered[AssignmentDraftPersistence.KEY_CONFLICT])
        layered[AssignmentDraftPersistence.KEY_DRAFT] = "x"
        assertEquals("x", primary[AssignmentDraftPersistence.KEY_DRAFT])
        assertEquals("x", handle.get<String>(AssignmentDraftPersistence.KEY_DRAFT))
    }

    @Test
    fun `draft survives a new screen stack after forced re-login of the same user`() {
        val editing = AssignmentEditorState(draftText = "長い本文")
        // 1回目のログイン: 画面の SavedStateHandle とファイルへ保存
        val first = AssignmentDraftPersistence(
            LayeredDraftStore(FileDraftStore(file(), PlainCodec, direct), AssignmentViewModel.SavedStateDraftStore(SavedStateHandle())),
            accountId = "u:100",
            legacyAccountIds = setOf("tokenhashA"),
        )
        first.save(editing)

        // invalidtoken → 再ログイン: 新しい画面スタック (空の SavedStateHandle)・新しいトークン、同じユーザーID
        val second = AssignmentDraftPersistence(
            LayeredDraftStore(FileDraftStore(file(), PlainCodec, direct), AssignmentViewModel.SavedStateDraftStore(SavedStateHandle())),
            accountId = "u:100",
            legacyAccountIds = setOf("tokenhashB"),
        )
        val restored = second.load()
        assertNotNull(restored)
        assertTrue(second.belongsToCurrentAccount(restored!!))
        assertEquals("長い本文", restored.draft)
    }

    @Test
    fun `draft of another user is not treated as current`() {
        val store = FileDraftStore(file(), PlainCodec, direct)
        AssignmentDraftPersistence(store, accountId = "u:100").save(AssignmentEditorState(draftText = "Aの本文"))

        val other = AssignmentDraftPersistence(FileDraftStore(file(), PlainCodec, direct), accountId = "u:200")
        val saved = other.load()!!
        assertFalse(other.belongsToCurrentAccount(saved))
        other.moveToOrphan(saved, nowMillis = 1L)
        val orphan = other.loadOrphan()!!
        assertTrue(other.orphanIsForeign(orphan))
        assertFalse(other.orphanBelongsToCurrent(orphan))
    }

    @Test
    fun `draft saved with the old token hash is restored for the same token`() {
        val store = FileDraftStore(file(), PlainCodec, direct)
        AssignmentDraftPersistence(store, accountId = "abc123").save(AssignmentEditorState(draftText = "旧版の本文"))

        val upgraded = AssignmentDraftPersistence(
            FileDraftStore(file(), PlainCodec, direct),
            accountId = "u:100",
            legacyAccountIds = setOf("abc123", "t:abc123"),
        )
        assertTrue(upgraded.belongsToCurrentAccount(upgraded.load()!!))
    }

    @Test
    fun `deleteAll removes every draft`() {
        FileDraftStore(FileDraftStore.fileFor(dir, 1, 1), PlainCodec, direct)["a"] = "x"
        FileDraftStore(FileDraftStore.fileFor(dir, 2, 2), PlainCodec, direct)["a"] = "y"
        assertEquals(2, FileDraftStore.count(dir))
        FileDraftStore.deleteAll(dir)
        assertEquals(0, FileDraftStore.count(dir))
    }
}
