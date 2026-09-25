package dev.rits.bettermoodle.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rits.bettermoodle.AppContainer
import dev.rits.bettermoodle.data.AssignmentDraftPersistence
import dev.rits.bettermoodle.data.AssignmentEditor
import dev.rits.bettermoodle.data.AssignmentEditorState
import dev.rits.bettermoodle.data.AssignmentFileDraft
import dev.rits.bettermoodle.data.DraftStateStore
import dev.rits.bettermoodle.data.RepositoryAssignmentGateway
import dev.rits.bettermoodle.data.SubmissionFileKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 本人確認できない保留草稿の画面向け情報 (W03)。
 * **本文は含めない**: 所有者を確認できるまで、別アカウント由来の文章を現在の利用者へ見せない。
 */
data class OrphanedDraftInfo(
    /** 保存時のアカウントが分かっていて、現在のアカウントと違う */
    val ownerMismatch: Boolean,
    val savedAtMillis: Long?,
    val characters: Int,
) {
    /** 現在の利用者が取り込んでよいか (所有者不明の旧形式のみ、明示操作で許可) */
    val canAdopt: Boolean get() = !ownerMismatch
}

/**
 * 課題画面の状態保持。復元は [AssignmentDraftPersistence] に委ね、
 * [AssignmentEditor] の保存フック経由で「未確認の書き込みは送信より前に」保存する (V01)。
 *
 * 復元の方針 (V04 / W02 / W03):
 * - 保存時と同じアカウント (認証トークンのハッシュ) なら草稿・基準値・競合・未確認操作を復元する
 * - それ以外 (旧形式でアカウント不明 / 別アカウント) は編集領域から**保留領域へ退避**し、
 *   採用の保存完了か明示的な破棄まで保存したまま残す。新しい ViewModel でも保留として見える
 * - 保留中は本文を画面へ出さない。所有者が分かっていて不一致なら取り込みもできない
 *   (そのアカウントでログインすれば通常の復元経路に入る)。所有者不明の旧形式のみ、
 *   利用者の明示操作で取り込める
 * - 別アカウントの未確認操作は復元しない
 *
 * トークンのハッシュは認証情報の識別であり Moodle ユーザーIDではないため、同じ利用者が
 * ログインし直した場合も「所有者不明ではなく不一致」になる。その場合は元のトークンが
 * 失われているので取り込みは許可せず、保留として残す (黙って消しはしない)。
 *
 * 書き込み系操作は [AssignmentEditor] 内で NonCancellable にしているため、画面離脱で
 * ViewModel が破棄されても送信と結果確認は完了する。
 */
class AssignmentViewModel(
    private val container: AppContainer,
    savedState: SavedStateHandle,
    courseId: Long,
    moduleId: Long,
    assignId: Long,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val appContext: Context = container.appContext()

    /** 現在のアカウントの識別子 (トークンのハッシュ)。復元内容が同じアカウントのものかの照合に使う */
    private val accountId: String? = container.authSession.currentToken()?.let(::hash)
    private val persistence = AssignmentDraftPersistence(SavedStateDraftStore(savedState), accountId)

    private val editor = AssignmentEditor(
        gateway = RepositoryAssignmentGateway(
            repository = container.moodleRepository,
            fileDraft = AssignmentFileDraft(container.moodleRepository, appContext.cacheDir),
        ),
        courseId = courseId,
        moduleId = moduleId,
        assignId = assignId,
        onCheckpoint = persistence::save,
    )

    val state: StateFlow<AssignmentEditorState> = editor.state

    private val _orphanedDraft = MutableStateFlow<OrphanedDraftInfo?>(null)

    /** 本人確認できない保留草稿の情報 (本文は含まない) */
    val orphanedDraft: StateFlow<OrphanedDraftInfo?> = _orphanedDraft

    init {
        val saved = persistence.load()
        if (saved != null) {
            if (persistence.belongsToCurrentAccount(saved)) {
                if (saved.draft.isNotEmpty() || saved.baseline != null) {
                    editor.restoreDraft(
                        AssignmentEditor.RestoredDraft(text = saved.draft, baseline = saved.baseline, conflict = saved.conflict),
                    )
                }
                saved.pending?.let(editor::restorePendingWrite)
            } else {
                // 旧形式または別アカウント: 保留領域へ退避してから編集領域を消す (先に書くので消えない)
                persistence.moveToOrphan(saved, nowMillis())
            }
        }
        publishOrphan()
        viewModelScope.launch {
            editor.refresh()
            editor.checkpoint()
        }
    }

    /**
     * 保留中の草稿を現在の編集欄へ取り込む。基準値は不明なので、最新本文と差があれば競合として保護する。
     * 保留領域は取り込み後の保存が済んでから消す (途中で終了しても文章が消えない)。
     */
    fun adoptOrphanedDraft(): Job = viewModelScope.launch {
        val orphan = persistence.loadOrphan() ?: return@launch
        if (orphan.isKnownMismatch(accountId)) return@launch
        editor.restoreDraft(orphan.text)
        editor.refresh()
        editor.checkpoint()
        // 編集領域へ保存できたことを確認してから保留を消す
        val saved = persistence.load()
        if (saved != null && persistence.belongsToCurrentAccount(saved)) {
            persistence.clearOrphan()
        }
        publishOrphan()
    }

    /** 利用者の明示的な破棄。ここでだけ保留草稿を削除する。 */
    fun discardOrphanedDraft() {
        persistence.clearOrphan()
        publishOrphan()
    }

    private fun publishOrphan() {
        val orphan = persistence.loadOrphan()
        _orphanedDraft.value = when {
            orphan == null -> null
            // 保存時と同じアカウントに戻ってきたら通常の復元対象として扱えるよう、取り込み可能にする
            orphan.belongsTo(accountId) -> OrphanedDraftInfo(false, orphan.savedAtMillis, orphan.text.length)
            else -> OrphanedDraftInfo(orphan.isKnownMismatch(accountId), orphan.savedAtMillis, orphan.text.length)
        }
    }

    fun updateText(text: String) {
        editor.updateText(text)
        editor.checkpoint()
    }

    fun clearMessages() = editor.clearMessages()

    /** 利用者が Moodle で確認したうえで、結果未確認の書き込みを解除する。最新状態を取得できたときだけ解除される */
    fun dismissPendingWrite(): Job = viewModelScope.launch {
        editor.dismissPendingWrite()
        editor.checkpoint()
    }

    fun refresh(discardDraft: Boolean = false): Job = viewModelScope.launch {
        editor.refresh(discardDraft)
        editor.checkpoint()
    }

    /** 結果未確認の書き込みを照会して確定する */
    fun reconcile(): Job = viewModelScope.launch {
        editor.reconcile()
        editor.checkpoint()
    }

    fun saveText(): Job = viewModelScope.launch {
        editor.saveText()
        editor.checkpoint()
    }

    fun start(): Job = viewModelScope.launch {
        editor.start()
        editor.checkpoint()
    }

    fun finalSubmit(acceptStatement: Boolean): Job = viewModelScope.launch {
        editor.finalSubmit(acceptStatement)
        editor.checkpoint()
    }

    fun removeFile(key: SubmissionFileKey): Job = viewModelScope.launch {
        editor.removeFile(key)
        editor.checkpoint()
    }

    /**
     * 選択したファイルをキャッシュへコピーしてから追加する。コピーも Editor の排他区間で行うので、
     * コピー中に最終提出などを割り込ませない。一時ファイルは必ず削除する。
     *
     * - 課題のサイズ上限を超えるファイルはコピー前に断る。サイズを申告しないプロバイダでも、
     *   上限を超えた時点でコピーを打ち切る (巨大ファイルでストレージを使い切らない。G06)
     * - ファイル名は Moodle が保存する名前に整形してから使う (G03)
     */
    fun addFile(uri: Uri): Job = viewModelScope.launch {
        var tmp: File? = null
        val maxBytes = editor.state.value.data?.assignment?.maxSubmissionSizeBytes?.takeIf { it > 0L }
        try {
            editor.addFile {
                withContext(Dispatchers.IO) {
                    val resolver = appContext.contentResolver
                    val meta = queryMeta(resolver, uri)
                    if (maxBytes != null && meta.size != null && meta.size > maxBytes) throw tooLarge(maxBytes)
                    val file = File.createTempFile("assignment_upload_", ".bin", appContext.cacheDir)
                    tmp = file
                    val original = meta.displayName ?: "submission.bin"
                    val filename = AssignmentFileDraft.cleanFilename(original)
                    val size = resolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "選択したファイルを読み込めません。" }
                        file.outputStream().use { output -> copyWithLimit(input, output, maxBytes) }
                    }
                    AssignmentFileDraft.LocalFile(
                        file = file,
                        filename = filename,
                        sizeBytes = size,
                        originalFilename = original.takeIf { it != filename },
                    )
                }
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { tmp?.delete() }
            editor.checkpoint()
        }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private data class PickedFileMeta(val displayName: String?, val size: Long?)

    private fun queryMeta(resolver: android.content.ContentResolver, uri: Uri): PickedFileMeta =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            PickedFileMeta(
                displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null,
                size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
            )
        } ?: PickedFileMeta(null, null)

    private fun tooLarge(maxBytes: Long) = AssignmentFileDraft.LocalFileRejectedException(
        AssignmentFileDraft.fileSizeViolation(maxBytes + 1, maxBytes) ?: "ファイルサイズが上限を超えています。",
    )

    /** [maxBytes] を超えたら打ち切る copyTo */
    private fun copyWithLimit(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long?): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (maxBytes != null && copied > maxBytes) throw tooLarge(maxBytes)
            output.write(buffer, 0, read)
        }
        return copied
    }

    /**
     * SavedStateHandle を [DraftStateStore] として扱う。
     * 読み取りは [Any] のまま返し、旧バージョンが別の型で保存した値 (Boolean の競合フラグなど) も
     * 型エラーにせず [AssignmentDraftPersistence] 側で移行できるようにする (W01)。
     */
    internal class SavedStateDraftStore(private val handle: SavedStateHandle) : DraftStateStore {
        override fun get(key: String): Any? = handle.get<Any?>(key)
        override fun set(key: String, value: String?) {
            if (value == null) handle.remove<Any?>(key) else handle[key] = value
        }
    }
}
