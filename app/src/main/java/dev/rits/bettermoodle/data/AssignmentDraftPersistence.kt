package dev.rits.bettermoodle.data

/**
 * 復元用の小さな key-value 保存先。Android では SavedStateHandle、テストでは Map で実装する。
 *
 * 値は [Any] で受け渡す。旧バージョンが別の型 (例: Boolean) で保存した値も読めるようにし、
 * 型が違うだけで復元内容を落とさないため ([AssignmentDraftPersistence] が移行する)。
 */
interface DraftStateStore {
    operator fun get(key: String): Any?
    operator fun set(key: String, value: String?)
}

/**
 * 課題画面の復元境界 (V01/V04/W01/W02)。
 *
 * 保存領域は2つある。
 * - **編集領域**: 現在のアカウントの草稿・本文の基準値・競合状態・未確認の書き込み。
 *   [save] は [AssignmentEditor] の保存フックから「送信より前」にも呼ばれるので、送信中・照会中に
 *   プロセスが終了しても未確認操作として復元できる
 * - **保留領域**: 本人確認できない草稿 (旧形式・別アカウント) を、所有者情報と一緒に退避する。
 *   編集領域の [save] / [clear] では消えず、採用の保存完了か明示的な破棄まで残る
 *
 * 保存形式には [KEY_FORMAT] でバージョンを持たせる。旧バージョン (バージョンキーなし) は
 * 競合フラグを Boolean で保存していたため、[load] で型を判定して移行する。
 *
 * SavedStateHandle が保証するのは Activity/プロセス再生成 (システム主導) の範囲。利用者が
 * タスクを終了した場合や端末再起動では復元されない (より強い永続化は現状行わない)。
 */
class AssignmentDraftPersistence(
    private val store: DraftStateStore,
    /**
     * 現在のアカウント識別子。Moodle のユーザーID ("u:<userid>") を使う (G02)。取得できないときは
     * 認証トークンのハッシュ ("t:<hash>") で代用する。
     */
    private val accountId: String?,
    /**
     * 同じ利用者を指す旧形式の識別子 (旧版が保存したトークンハッシュなど)。これらで保存された内容も
     * 現在のアカウントのものとして復元する。
     */
    private val legacyAccountIds: Set<String> = emptySet(),
) {
    data class Saved(
        val draft: String,
        val baseline: String?,
        val conflict: Boolean,
        val pending: PendingWrite?,
        /** null は旧形式 (アカウント不明) */
        val account: String?,
    ) {
        val isLegacy: Boolean get() = account == null
    }

    /** 本人確認できない保留草稿。本文は所有者を確認できるまで画面へ出さない (W03) */
    data class Orphan(
        val text: String,
        /** 保存時のアカウント識別子。null は旧形式で不明 */
        val ownerAccount: String?,
        val savedAtMillis: Long?,
    ) {
        /** 保存時のアカウントが分かっていて、いまのアカウントと違う */
        fun isKnownMismatch(currentAccountId: String?): Boolean =
            ownerAccount != null && ownerAccount != currentAccountId

        fun belongsTo(currentAccountId: String?): Boolean =
            ownerAccount != null && ownerAccount == currentAccountId
    }

    // ---- 編集領域 ----

    fun load(): Saved? {
        val draft = store[KEY_DRAFT].asStringOrNull()
        val pending = PendingWrite.decode(store[KEY_PENDING].asStringOrNull())
        if (draft == null && pending == null) return null
        return Saved(
            draft = draft.orEmpty(),
            baseline = store[KEY_BASELINE].asStringOrNull(),
            conflict = store[KEY_CONFLICT].asConflictFlag(),
            pending = pending,
            account = store[KEY_ACCOUNT].asStringOrNull(),
        )
    }

    /** 現在のアカウントのものとして扱えるか */
    fun belongsToCurrentAccount(saved: Saved): Boolean = isCurrent(saved.account)

    /** 保留草稿が現在のアカウントのものか */
    fun orphanBelongsToCurrent(orphan: Orphan): Boolean = isCurrent(orphan.ownerAccount)

    /** 保留草稿の所有者が分かっていて、現在のアカウントと違う */
    fun orphanIsForeign(orphan: Orphan): Boolean = orphan.ownerAccount != null && !isCurrent(orphan.ownerAccount)

    private fun isCurrent(account: String?): Boolean =
        accountId != null && account != null && (account == accountId || account in legacyAccountIds)

    /**
     * 復元に必要な情報を保存する。草稿が無く未確認の書き込みも無く競合も無ければ消す。
     * 基準値 (savedText) は「この草稿を編集し始めた時点のサーバー本文」として保存し、
     * 復元時に Web 側の変更を判定できるようにする。
     */
    fun save(state: AssignmentEditorState) {
        val pending = state.pendingWrite
        if (state.data == null && pending == null) {
            // まだ読み込めていない間は入力中の文字列だけ保存する (基準値は不明のまま)
            if (state.draftText.isNotEmpty()) {
                store[KEY_FORMAT] = FORMAT_VERSION
                store[KEY_DRAFT] = state.draftText
                store[KEY_ACCOUNT] = accountId
            }
            return
        }
        if (!state.isDirty && !state.textConflict && pending == null) {
            clear()
            return
        }
        store[KEY_FORMAT] = FORMAT_VERSION
        store[KEY_ACCOUNT] = accountId
        store[KEY_DRAFT] = state.draftText
        store[KEY_BASELINE] = if (state.data != null) state.savedText else null
        store[KEY_CONFLICT] = if (state.textConflict) "1" else "0"
        store[KEY_PENDING] = pending?.let(PendingWrite::encode)
    }

    /** 編集領域だけを消す (保留領域は残る) */
    fun clear() {
        store[KEY_DRAFT] = null
        store[KEY_BASELINE] = null
        store[KEY_CONFLICT] = null
        store[KEY_PENDING] = null
        store[KEY_ACCOUNT] = null
        store[KEY_FORMAT] = null
    }

    // ---- 保留領域 ----

    /**
     * 本人確認できない草稿を保留領域へ退避する。編集領域より先に書き、後から編集領域を消すので、
     * 途中でプロセスが終了しても文章が消えない。
     */
    fun moveToOrphan(saved: Saved, nowMillis: Long) {
        if (saved.draft.isBlank()) {
            clear()
            return
        }
        store[KEY_ORPHAN_DRAFT] = saved.draft
        store[KEY_ORPHAN_ACCOUNT] = saved.account
        store[KEY_ORPHAN_SAVED_AT] = nowMillis.toString()
        clear()
    }

    fun loadOrphan(): Orphan? {
        val text = store[KEY_ORPHAN_DRAFT].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Orphan(
            text = text,
            ownerAccount = store[KEY_ORPHAN_ACCOUNT].asStringOrNull(),
            savedAtMillis = store[KEY_ORPHAN_SAVED_AT].asStringOrNull()?.toLongOrNull(),
        )
    }

    fun clearOrphan() {
        store[KEY_ORPHAN_DRAFT] = null
        store[KEY_ORPHAN_ACCOUNT] = null
        store[KEY_ORPHAN_SAVED_AT] = null
    }

    private fun Any?.asStringOrNull(): String? = this as? String

    /**
     * 競合フラグの移行 (W01)。新形式は "1"/"0"、旧形式は Boolean。
     * 解釈できない型は「競合の可能性あり」として保護側に倒す (黙って false にしない)。
     */
    private fun Any?.asConflictFlag(): Boolean = when (this) {
        null -> false
        is Boolean -> this
        is String -> this == "1" || this.equals("true", ignoreCase = true)
        else -> true
    }

    companion object {
        const val KEY_DRAFT = "assignment_draft_text"
        const val KEY_BASELINE = "assignment_draft_baseline"
        const val KEY_CONFLICT = "assignment_draft_conflict"
        const val KEY_PENDING = "assignment_pending_write"
        const val KEY_ACCOUNT = "assignment_draft_account"
        const val KEY_FORMAT = "assignment_draft_format"
        const val KEY_ORPHAN_DRAFT = "assignment_orphan_draft"
        const val KEY_ORPHAN_ACCOUNT = "assignment_orphan_account"
        const val KEY_ORPHAN_SAVED_AT = "assignment_orphan_saved_at"

        /** 現在の保存形式。旧形式 (キーなし) は Boolean の競合フラグを持つ */
        const val FORMAT_VERSION = "2"
    }
}
