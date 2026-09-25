package dev.rits.bettermoodle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 課題画面が扱うサーバー側データ */
data class AssignmentData(
    val assignment: Assignment,
    val status: SubmissionStatusResponse,
    val ui: AssignmentUiModel,
)

sealed interface AssignmentLoad {
    data object Loading : AssignmentLoad
    data class Failed(val message: String) : AssignmentLoad
    data class Ready(val data: AssignmentData) : AssignmentLoad
}

/** 進行中の操作。busy の内訳を画面に出し、離脱・連打の判断に使う。 */
enum class EditorPhase(val label: String?) {
    Idle(null),
    Refreshing(null),
    PreparingFile("ファイルを準備中"),
    ChangingFiles("ファイル提出を更新中"),
    Saving("保存中"),
    Starting("提出を開始中"),
    Submitting("最終提出中"),
    Reconciling("送信結果を確認中"),
}

/**
 * サーバーへ送ったが結果を確認できていない書き込み。
 * 結果が分かるまで同じ種類の書き込みを再送しない (二重提出・古い内容の上書きを防ぐ)。
 *
 * [acknowledged] は「サーバーが成功応答を返したが、その後の状態照会に失敗した」場合。
 * 応答自体が消えた場合 (false) と違い、照会で未反映に見えても再送可能とは断定しない。
 */
@kotlinx.serialization.Serializable
sealed interface PendingWrite {
    val description: String
    val acknowledged: Boolean

    /** 送信した HTML (適用済みならサーバーの内容がこれと一致する) */
    @kotlinx.serialization.Serializable
    data class SaveText(val html: String, override val acknowledged: Boolean = false) : PendingWrite {
        override val description get() = "文章の保存"
    }

    @kotlinx.serialization.Serializable
    data class ChangeFiles(
        val expected: Set<SubmissionFileFingerprint>,
        override val acknowledged: Boolean = false,
    ) : PendingWrite {
        override val description get() = "ファイル提出の更新"
    }

    @kotlinx.serialization.Serializable
    data class Submit(override val acknowledged: Boolean = false) : PendingWrite {
        override val description get() = "最終提出"
    }

    @kotlinx.serialization.Serializable
    data class Start(override val acknowledged: Boolean = false) : PendingWrite {
        override val description get() = "提出の開始"
    }

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** プロセス再生成をまたいで保持するための直列化 */
        fun encode(pending: PendingWrite): String = json.encodeToString(serializer(), pending)

        fun decode(raw: String?): PendingWrite? =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }

    fun asAcknowledged(): PendingWrite = when (this) {
        is SaveText -> copy(acknowledged = true)
        is ChangeFiles -> copy(acknowledged = true)
        is Submit -> copy(acknowledged = true)
        is Start -> copy(acknowledged = true)
    }
}

/**
 * 課題画面の編集状態。
 *
 * - [draftText] は編集欄の内容、[savedText] はサーバーに保存済みと確認できた内容 (本文の基準値)
 * - 差があれば [isDirty]。再読込・離脱で無断に捨てない。未編集なら再読込で両方を新しい本文に揃える
 * - 編集中に Web 側で本文が変わったら [textConflict]。解決 (再読込で破棄) するまで自動保存しない
 * - [phase] が Idle 以外 ([busy]) の間は重複操作を受け付けない
 * - [pendingWrite] がある間 (結果未確認) は、照会で確定するまで書き込みを受け付けない
 * - 失敗は [error] に載せ、取得済みデータと草稿は保持する
 */
data class AssignmentEditorState(
    val load: AssignmentLoad = AssignmentLoad.Loading,
    val draftText: String = "",
    val savedText: String = "",
    val textConflict: Boolean = false,
    val phase: EditorPhase = EditorPhase.Idle,
    val pendingWrite: PendingWrite? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val isDirty: Boolean get() = draftText != savedText
    val data: AssignmentData? get() = (load as? AssignmentLoad.Ready)?.data
    val busy: Boolean get() = phase != EditorPhase.Idle
    val busyLabel: String? get() = phase.label

    /** 結果未確認の書き込みが残っている */
    val outcomeUnknown: Boolean get() = pendingWrite != null
}

/** 課題編集に必要な I/O。テストでは差し替える。 */
interface AssignmentGateway {
    suspend fun loadAssignment(courseId: Long, moduleId: Long, assignId: Long): Assignment?
    suspend fun submissionStatus(assignId: Long): SubmissionStatusResponse
    suspend fun startSubmission(assignId: Long)
    suspend fun saveOnlineText(assignmentId: Long, html: String)
    suspend fun buildFileDraft(plan: AssignmentFileDraft.Plan, maxBytesPerFile: Long): Long
    suspend fun saveFiles(assignmentId: Long, draftItemId: Long)
    suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean)
}

class RepositoryAssignmentGateway(
    private val repository: MoodleRepository,
    private val fileDraft: AssignmentFileDraft,
) : AssignmentGateway {
    override suspend fun loadAssignment(courseId: Long, moduleId: Long, assignId: Long): Assignment? =
        repository.assignment(courseId, moduleId, assignId)

    override suspend fun submissionStatus(assignId: Long): SubmissionStatusResponse =
        repository.assignmentStatus(assignId)

    override suspend fun startSubmission(assignId: Long) {
        repository.startAssignmentSubmission(assignId)
    }

    override suspend fun saveOnlineText(assignmentId: Long, html: String) =
        repository.saveOnlineTextSubmission(assignmentId, html)

    override suspend fun buildFileDraft(plan: AssignmentFileDraft.Plan, maxBytesPerFile: Long): Long =
        fileDraft.build(plan, maxBytesPerFile)

    override suspend fun saveFiles(assignmentId: Long, draftItemId: Long) =
        repository.saveFileSubmission(assignmentId, draftItemId)

    override suspend fun submitForGrading(assignmentId: Long, acceptStatement: Boolean) =
        repository.submitAssignmentForGrading(assignmentId, acceptStatement)
}

class AssignmentEditor(
    private val gateway: AssignmentGateway,
    private val courseId: Long,
    private val moduleId: Long,
    private val assignId: Long,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * 復元に必要な状態が変わった時点で同期的に呼ばれる保存フック (V01)。
     * 未確認の書き込みは「送信より前」にこのフックで永続化されるので、送信中・照会中に
     * プロセスが終了しても再生成後に未確認操作として復元できる。
     */
    private val onCheckpoint: (AssignmentEditorState) -> Unit = {},
) {
    private val _state = MutableStateFlow(AssignmentEditorState())
    val state: StateFlow<AssignmentEditorState> = _state
    private val operation = Mutex()

    /** pendingWrite の変更は必ずこの経路で行い、変更直後に保存フックを呼ぶ */
    private fun setPending(pending: PendingWrite?, transform: (AssignmentEditorState) -> AssignmentEditorState = { it }) {
        _state.update { transform(it).copy(pendingWrite = pending) }
        onCheckpoint(_state.value)
    }
    /** プロセス再生成後の復元内容。草稿だけでなく、編集開始時の本文基準値と競合状態も含む (S02) */
    data class RestoredDraft(
        val text: String,
        /** 編集開始時にサーバーから読んだ本文 (正規化済み)。null は旧形式で不明 */
        val baseline: String?,
        /** 再生成前に検出していた外部変更との競合 */
        val conflict: Boolean,
    )

    private var restoredDraft: RestoredDraft? = null

    /** 旧形式 (草稿文字列だけ) の復元。基準値が無いので、最新本文と差があれば競合の可能性ありとして扱う */
    fun restoreDraft(text: String) = restoreDraft(RestoredDraft(text, baseline = null, conflict = false))

    /** プロセス再生成後などに保存していた草稿・基準値・競合状態を復元する (サーバー読込より先に呼ぶ) */
    fun restoreDraft(restored: RestoredDraft) {
        _state.update { it.copy(draftText = restored.text, savedText = restored.baseline ?: it.savedText, textConflict = restored.conflict) }
        restoredDraft = restored
    }

    fun updateText(text: String) {
        _state.update { it.copy(draftText = text, notice = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** 画面側で起きた失敗 (ファイル読込など) を同じエラー表示に載せる */
    fun reportError(message: String) = fail(message)

    /**
     * 結果未確認の書き込みを利用者の明示的な判断で解除する (Moodle で確認済みの場合)。
     * 自動では呼ばない。解除後はサーバーの最新状態を取り直す (古い画面のまま操作させない)。
     */
    suspend fun dismissPendingWrite(): Boolean =
        runExclusive(EditorPhase.Reconciling) {
            // 最新状態を取得できたときだけ解除する。取得できなければ未確認のまま保持し、旧状態での書き込みを許さない (V02)
            val data = try {
                withContext(NonCancellable) { fetch() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("最新の状態を取得できないため解除できません: ${describe(e)}。通信を確認して再試行するか、Moodleで確認してください。")
                return@runExclusive false
            }
            setPending(null) { s -> s.withReloadedText(data).copy(error = null, notice = "未確認の操作を解除し、最新の状態を取り込みました。") }
            true
        } ?: false

    /** プロセス再生成後に、未確認のまま残していた書き込みを復元する (サーバー読込より先に呼ぶ) */
    fun restorePendingWrite(pending: PendingWrite) {
        _state.update { it.copy(pendingWrite = pending) }
    }

    /** 保存フックに現在の状態を渡す (画面側で草稿を変更した直後などに使う) */
    fun checkpoint() = onCheckpoint(_state.value)

    /**
     * サーバーから再取得する。未保存の草稿があり [discardDraft] が false なら
     * 草稿を残したまま (呼び出し側が確認ダイアログを出す前提)。
     * 結果未確認の書き込みがあれば、再取得した状態でその結果を確定する。
     */
    suspend fun refresh(discardDraft: Boolean = false) {
        runExclusive(EditorPhase.Refreshing) {
            if (_state.value.data == null) _state.update { it.copy(load = AssignmentLoad.Loading) }
            val data = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s.data == null) {
                        s.copy(load = AssignmentLoad.Failed(loadErrorMessage(e)))
                    } else {
                        s.copy(error = "再読込に失敗しました: ${describe(e)}")
                    }
                }
                return@runExclusive
            }
            val serverText = OnlineTextFormat.htmlToEditableText(data.ui.onlineText)
            _state.update { s ->
                val restored = restoredDraft
                restoredDraft = null
                if (discardDraft) {
                    s.copy(load = AssignmentLoad.Ready(data), savedText = serverText, draftText = serverText, textConflict = false)
                } else if (restored != null) {
                    val restoredDirty = restored.text != serverText
                    // 基準値が分かっていれば「編集開始後に Web 側が変わったか」を判定できる。
                    // 基準値が無い旧形式は、最新本文と草稿に差があれば競合の可能性ありとして保護側に倒す
                    val externallyChanged = when (restored.baseline) {
                        null -> restoredDirty
                        else -> restored.baseline != serverText
                    }
                    s.copy(
                        load = AssignmentLoad.Ready(data),
                        savedText = serverText,
                        draftText = if (restoredDirty) restored.text else serverText,
                        textConflict = restoredDirty && (restored.conflict || externallyChanged),
                    )
                } else {
                    s.withReloadedText(data, serverText)
                }
            }
            _state.value.pendingWrite?.let { settlePending(it, data, viaReconcile = true) }
        }
    }

    /**
     * 結果未確認の書き込みを照会して確定する。照会できれば true (適用の有無は状態に反映)。
     * (通信断のまま呼ばれた場合は false のまま pendingWrite を保持する)
     */
    suspend fun reconcile(): Boolean =
        runExclusive(EditorPhase.Reconciling) {
            val pending = _state.value.pendingWrite ?: return@runExclusive true
            val data = try {
                withContext(NonCancellable) { fetch() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("${pending.description}の結果を確認できませんでした: ${describe(e)}。通信を確認してもう一度「結果を確認」を押してください。")
                return@runExclusive false
            }
            settlePending(pending, data, viaReconcile = true)
            true
        } ?: false

    /**
     * 文章を保存する。送信前にサーバーの現在の本文を再照会し、画面読込時の基準値 ([AssignmentEditorState.savedText])
     * から変わっていれば外部更新として停止する (書式が付いた・権限が変わった場合も同様)。
     * 再照会と保存の間の同時編集は防げない (Moodle の API に条件付き更新がないため)。
     */
    suspend fun saveText(): Boolean {
        if (_state.value.data == null) return false
        if (!ensureNoPendingWrite()) return false
        if (_state.value.textConflict) {
            fail("Web側で本文が変更されています。再読込して内容を確認してから保存してください。")
            return false
        }
        return runExclusive(EditorPhase.Saving) {
            // 保存前の再照会: 外部更新・書式・権限を最新で判定する
            val fresh = reloadAfterWrite(prefix = "保存前の状態確認に失敗しました") ?: return@runExclusive false
            val freshText = OnlineTextFormat.htmlToEditableText(fresh.ui.onlineText)
            val baseline = _state.value.savedText
            val draft = OnlineTextFormat.normalizeEditableText(_state.value.draftText)
            if (freshText != baseline) {
                if (freshText == draft) {
                    // 同じ内容が既に保存されている (別端末など)。送らずに一致させる
                    _state.update {
                        it.copy(load = AssignmentLoad.Ready(fresh), savedText = freshText, draftText = freshText, textConflict = false, notice = "同じ内容が既に保存されていました")
                    }
                    return@runExclusive true
                }
                _state.update {
                    it.copy(
                        load = AssignmentLoad.Ready(fresh),
                        savedText = freshText,
                        textConflict = true,
                        error = "Web側で本文が変更されています。上書きを避けるため保存を中止しました。再読込で最新の本文を確認してください。",
                    )
                }
                return@runExclusive false
            }
            if (fresh.ui.onlineTextIsRich) {
                _state.update { it.copy(load = AssignmentLoad.Ready(fresh)) }
                fail("保存済みの文章に書式が含まれているため、このアプリからは上書きできません。Moodleで編集してください。")
                return@runExclusive false
            }
            if (AssignmentAction.Edit !in fresh.ui.actions) {
                _state.update { it.copy(load = AssignmentLoad.Ready(fresh)) }
                fail("この課題は現在編集できません。")
                return@runExclusive false
            }
            val html = OnlineTextFormat.plainTextToHtml(draft)
            performWrite(PendingWrite.SaveText(html)) { gateway.saveOnlineText(fresh.assignment.id, html) }
        } ?: false
    }

    /**
     * ファイルを追加する。[prepare] (ContentProvider からのコピー等) も排他区間に含め、
     * コピー中に最終提出などの他操作が割り込まないようにする。
     */
    suspend fun addFile(prepare: suspend () -> AssignmentFileDraft.LocalFile): Boolean {
        if (_state.value.data == null) return false
        if (!ensureNoPendingWrite()) return false
        return runExclusive(EditorPhase.PreparingFile) {
            val local = try {
                prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("選択したファイルを読み込めませんでした。")
                return@runExclusive false
            }
            changeFilesLocked(add = local, remove = emptySet())
        } ?: false
    }

    suspend fun addFile(file: AssignmentFileDraft.LocalFile): Boolean = addFile { file }

    /** サイズを明示する呼び出し (ファイルが既に削除されていても検証できるように) */
    suspend fun addFile(file: AssignmentFileDraft.LocalFile, sizeBytes: Long): Boolean =
        addFile { file.copy(sizeBytes = sizeBytes) }

    suspend fun removeFile(key: SubmissionFileKey): Boolean {
        if (_state.value.data == null) return false
        if (!ensureNoPendingWrite()) return false
        return runExclusive(EditorPhase.ChangingFiles) {
            changeFilesLocked(add = null, remove = setOf(key))
        } ?: false
    }

    /** 排他区間内で呼ぶ。変更前にサーバー状態を再照会し、画面表示時の集合と違えば中断する。 */
    private suspend fun changeFilesLocked(add: AssignmentFileDraft.LocalFile?, remove: Set<SubmissionFileKey>): Boolean {
        val shown = _state.value.data ?: return false
        val assignment = shown.assignment
        if (AssignmentAction.Edit !in shown.ui.actions) {
            fail("この課題は現在編集できません。")
            return false
        }
        setPhase(EditorPhase.ChangingFiles)

        // 古いスナップショットで新しい提出物を消さないよう、直前のサーバー状態と比べる
        val fresh = reloadAfterWrite(prefix = "変更前の状態確認に失敗しました") ?: return false
        val shownFingerprints = extractSubmittedMoodleFiles(shown.status).map { SubmissionFileFingerprint.of(it) }.toSet()
        val freshFiles = extractSubmittedMoodleFiles(fresh.status)
        if (freshFiles.map { SubmissionFileFingerprint.of(it) }.toSet() != shownFingerprints) {
            _state.update { s ->
                s.withReloadedText(fresh).copy(
                    error = "サーバー側で提出ファイルが変更されています。一覧を確認してからやり直してください。",
                )
            }
            return false
        }

        val plan = AssignmentFileDraft.plan(freshFiles, add, remove)
        val violation = AssignmentFileDraft.fileCountViolation(plan, assignment.maxFileSubmissions)
            ?: add?.let { AssignmentFileDraft.fileSizeViolation(it.sizeBytes, assignment.maxSubmissionSizeBytes) }
        if (violation != null) {
            fail(violation)
            return false
        }
        val expected = plan.expectedFingerprints
        val maxBytes = assignment.maxSubmissionSizeBytes.takeIf { it > 0L } ?: Long.MAX_VALUE
        // draft の組み立て (download/upload) は再送しても副作用がないので通常の失敗扱い
        val draftId = try {
            gateway.buildFileDraft(plan, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("ファイルの準備に失敗しました: ${describe(e)}")
            return false
        }
        return performWrite(PendingWrite.ChangeFiles(expected)) { gateway.saveFiles(assignment.id, draftId) }
    }

    suspend fun start(): Boolean {
        val data = _state.value.data ?: return false
        if (AssignmentAction.Start !in data.ui.actions) {
            fail("この課題は開始できません。")
            return false
        }
        if (!ensureNoPendingWrite()) return false
        return runExclusive(EditorPhase.Starting) {
            performWrite(PendingWrite.Start()) { gateway.startSubmission(data.assignment.id) }
        } ?: false
    }

    /**
     * 最終提出。未保存の文章があれば先に保存し、保存が確認できた場合だけ提出する。
     * 外部更新との競合が未解決なら提出しない。応答を確認できなかった場合は結果未確認として保持し、
     * 照会で確定するまで再送しない。
     */
    suspend fun finalSubmit(acceptStatement: Boolean): Boolean {
        if (_state.value.data == null) return false
        if (!ensureNoPendingWrite()) return false
        val current = _state.value.data ?: return false
        if (AssignmentAction.FinalSubmit !in current.ui.actions) {
            fail(
                if (current.ui.submission == AssignmentSubmissionLabel.Submitted) {
                    "この課題は既に最終提出されています。"
                } else {
                    "この課題は現在最終提出できません。"
                },
            )
            return false
        }
        if (_state.value.textConflict) {
            fail("Web側で本文が変更されています。再読込して内容を確認してから提出してください。")
            return false
        }
        if (_state.value.isDirty && current.assignment.supports("onlinetext")) {
            if (!saveText()) return false
        }
        return runExclusive(EditorPhase.Submitting) {
            performWrite(PendingWrite.Submit()) { gateway.submitForGrading(current.assignment.id, acceptStatement) }
        } ?: false
    }

    // ---- 内部処理: 書き込みの状態機械 (S01) ----
    //
    // 書き込みは開始時点から PendingWrite を所有する。
    //   送信 → (Moodle が明示的に拒否)            → 解除・失敗
    //        → (成功応答)  acknowledged=true      → 照会
    //        → (応答消失/形式不明) acknowledged=false → 照会
    //   照会 → 適用済み                           → 解除・成功
    //        → 照会失敗                           → 保持 (結果未確認)
    //        → 未反映                             → 保持 (処理中の可能性があり、未適用とは断定しない)
    // 保持中は「結果を確認」(再照会) か、利用者が Moodle で確認したうえでの明示解除だけが状態を進める。
    // 最初の照会と後日の reconcile は同じ settlePending を使う。

    private enum class SendOutcome { Sent, Rejected, Lost }

    /**
     * 書き込みを実行し、送信結果に応じて [PendingWrite] を登録したうえで照会・確定まで行う。
     * 適用が確認できたときだけ true。送信中はコルーチンのキャンセル (画面離脱) で中断しない:
     * 中断すると結果が分からなくなるため。
     */
    private suspend fun performWrite(pending: PendingWrite, block: suspend () -> Unit): Boolean {
        // 送信前に未確認操作として所有・保存する (送信中の終了でも復元後に再送しない)
        setPending(pending)
        val outcome = try {
            withContext(NonCancellable) { block() }
            SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssignmentSubmissionRejectedException) {
            // 明示的な拒否だけが未確認操作を解除する
            setPending(null) { it.copy(error = "${pending.description}がMoodleに拒否されました: ${e.message}", notice = null) }
            SendOutcome.Rejected
        } catch (e: MoodleWsException) {
            setPending(null) { it.copy(error = "${pending.description}に失敗しました: ${describe(e)}", notice = null) }
            SendOutcome.Rejected
        } catch (e: MoodleResponseParseException) {
            SendOutcome.Lost
        } catch (e: Exception) {
            SendOutcome.Lost
        }
        if (outcome == SendOutcome.Rejected) return false

        val owned = if (outcome == SendOutcome.Sent) pending.asAcknowledged() else pending
        setPending(owned)
        val data = try {
            withContext(NonCancellable) { fetch() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(
                if (owned.acknowledged) {
                    "${owned.description}はサーバーに受理されましたが、結果の確認に失敗しました: ${describe(e)}。通信を確認して「結果を確認」を押してください。"
                } else {
                    "${owned.description}の結果を確認できませんでした (${describe(e)})。通信を確認して「結果を確認」を押してください。"
                },
            )
            return false
        }
        return settlePending(owned, data, viaReconcile = false)
    }

    /**
     * 再取得した状態で未確認の書き込みを確定する。
     * - 適用済み: 成功扱いにして解除
     * - 未反映: 応答の有無にかかわらず解除しない (直後の読み取りが旧状態でも、処理中の書き込みが後から反映され得る)
     */
    private fun settlePending(pending: PendingWrite, data: AssignmentData, viaReconcile: Boolean): Boolean {
        // 未反映の具体的な理由 (null なら適用済み)
        val notAppliedReason: String? = when (pending) {
            is PendingWrite.SaveText ->
                if (OnlineTextFormat.editableTextOf(data.ui.onlineText) == OnlineTextFormat.editableTextOf(pending.html)) null
                else "サーバーの本文が保存した内容と一致しません"
            is PendingWrite.ChangeFiles ->
                AssignmentFileDraft.verifySaved(pending.expected, extractSubmittedMoodleFiles(data.status))
            is PendingWrite.Submit ->
                if (data.ui.submission == AssignmentSubmissionLabel.Submitted) null else "提出状態が「提出済み」になっていません"
            is PendingWrite.Start ->
                if ((data.status.lastattempt?.submission?.timestarted ?: 0L) > 0L || data.ui.canEdit) null else "開始後も編集可能になっていません"
        }
        val applied = notAppliedReason == null
        val serverText = OnlineTextFormat.htmlToEditableText(data.ui.onlineText)
        _state.update { s ->
            if (applied) {
                val base = if (pending is PendingWrite.SaveText) {
                    // 保存が確認できたら草稿も正規化後の内容に揃え、dirty を残さない
                    s.copy(load = AssignmentLoad.Ready(data), savedText = serverText, draftText = serverText, textConflict = false)
                } else {
                    s.withReloadedText(data, serverText)
                }
                base.copy(
                    pendingWrite = null,
                    error = null,
                    notice = if (viaReconcile) "${pending.description}はサーバーに反映されていました。" else appliedNotice(pending),
                )
            } else {
                s.withReloadedText(data, serverText).copy(
                    pendingWrite = pending,
                    notice = null,
                    error = if (pending.acknowledged) {
                        "${pending.description}はサーバーに受理されましたが、まだ反映を確認できません ($notAppliedReason)。" +
                            "しばらくしてから「結果を確認」を押すか、Moodleで確認してください。"
                    } else {
                        "${pending.description}の応答を受け取れず、サーバー上でも反映を確認できません ($notAppliedReason)。" +
                            "処理中の可能性があるため再送しません。「結果を確認」で再確認するか、Moodleで確認したうえで解除してください。"
                    },
                )
            }
        }
        onCheckpoint(_state.value)
        return applied
    }

    private fun appliedNotice(pending: PendingWrite): String = when (pending) {
        is PendingWrite.SaveText -> "提出内容を保存しました"
        is PendingWrite.ChangeFiles -> "ファイル提出を更新しました"
        is PendingWrite.Submit -> "最終提出しました"
        is PendingWrite.Start -> "提出を開始しました"
    }

    /**
     * サーバー本文を再読込したときの草稿の扱い (R02):
     * - 未編集 (draft == saved) なら草稿も新しい本文に揃える。古い本文が「未保存草稿」になって
     *   最終提出前の自動保存で Web 側の更新を巻き戻すことを防ぐ
     * - 編集中に本文が外部で変わっていれば草稿は残し、[AssignmentEditorState.textConflict] を立てる
     */
    private fun AssignmentEditorState.withReloadedText(
        data: AssignmentData,
        serverText: String = OnlineTextFormat.htmlToEditableText(data.ui.onlineText),
    ): AssignmentEditorState {
        val wasDirty = isDirty
        val externallyChanged = serverText != savedText
        return copy(
            load = AssignmentLoad.Ready(data),
            savedText = serverText,
            draftText = if (wasDirty) draftText else serverText,
            textConflict = if (wasDirty) (textConflict || externallyChanged) else false,
        )
    }

    /** 結果未確認の書き込みが残っていれば照会し、確定できなければ書き込みを受け付けない */
    private suspend fun ensureNoPendingWrite(): Boolean {
        if (_state.value.pendingWrite == null) return true
        reconcile()
        val stillPending = _state.value.pendingWrite ?: return true
        fail("前回の${stillPending.description}の結果が未確認です。通信を確認して「結果を確認」を押してください。")
        return false
    }

    private suspend fun fetch(): AssignmentData {
        val assignment = gateway.loadAssignment(courseId, moduleId, assignId)
            ?: throw AssignmentUnavailableException()
        val status = gateway.submissionStatus(assignment.id)
        return AssignmentData(assignment, status, buildAssignmentUiModel(assignment, status, nowEpochSeconds()))
    }

    /** 書き込み前の再取得。失敗したらエラーを載せて null。 */
    private suspend fun reloadAfterWrite(prefix: String): AssignmentData? =
        try {
            withContext(NonCancellable) { fetch() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("$prefix。再読込で状態を確認してください: ${describe(e)}")
            null
        }

    private fun fail(message: String) {
        _state.update { it.copy(error = message, notice = null) }
    }

    private fun setPhase(phase: EditorPhase) {
        _state.update { it.copy(phase = phase) }
    }

    /** busy 中は null を返して重複操作を捨てる */
    private suspend fun <T> runExclusive(phase: EditorPhase, block: suspend () -> T): T? {
        if (!operation.tryLock()) return null
        try {
            _state.update { it.copy(phase = phase, error = null, notice = null) }
            return block()
        } finally {
            _state.update { it.copy(phase = EditorPhase.Idle) }
            operation.unlock()
        }
    }

    private fun loadErrorMessage(e: Exception): String = when (e) {
        is AssignmentUnavailableException -> "この課題はMoodle Web Serviceから取得できません。Moodleで開いてください。"
        else -> "課題を読み込めませんでした: ${describe(e)}"
    }

    private fun describe(e: Exception): String = when (e) {
        is AssignmentSubmissionRejectedException -> e.message.orEmpty()
        is MoodleWsException -> e.message ?: e.errorCode ?: "Moodleエラー"
        is MoodleHttpException -> "サーバーエラー (HTTP ${e.status})"
        is MoodleResponseParseException -> "サーバー応答の形式が想定と異なります"
        is java.io.IOException -> "通信に失敗しました"
        else -> e.message ?: e::class.simpleName.orEmpty()
    }
}

class AssignmentUnavailableException : Exception("Assignment is not available through the web service")
