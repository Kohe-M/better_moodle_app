package dev.rits.bettermoodle.data

import java.io.File

/** 提出ファイルの識別子。同名でもディレクトリが違えば別ファイル。 */
@kotlinx.serialization.Serializable
data class SubmissionFileKey(val filepath: String, val filename: String) {
    companion object {
        fun of(file: MoodleFile): SubmissionFileKey =
            SubmissionFileKey(MoodleClient.normalizeDraftPath(file.filepath), file.filename)

        fun root(filename: String): SubmissionFileKey = SubmissionFileKey("/", filename)
    }
}

/**
 * 提出ファイル集合の「指紋」。サーバー側の変更検出・保存後検証に使う。
 * [sizeBytes] が null なのは「不明」であり、0 バイトの既知サイズとは区別する。
 */
@kotlinx.serialization.Serializable
data class SubmissionFileFingerprint(val key: SubmissionFileKey, val sizeBytes: Long?) {
    companion object {
        fun of(file: MoodleFile): SubmissionFileFingerprint =
            SubmissionFileFingerprint(SubmissionFileKey.of(file), file.filesize)
    }
}

/**
 * ファイル提出のdraft領域を「提出物の全体集合」として組み立てる。
 *
 * Moodleの `mod_assign_save_submission` は `files_filemanager` のdraft領域にある
 * ファイル集合で提出領域を丸ごと置き換える (draftに無い既存ファイルは削除される)。
 * 「1件追加」をそのまま新規draftで保存すると既存ファイルが消えるため、
 * 残す既存ファイルをサーバーから取得して同じdraftへ (同じパスで) 再アップロードしてから保存する。
 * これは公式モバイルアプリの uploadOrReuploadFiles と同じ方針。
 */
class AssignmentFileDraft(
    private val repository: MoodleRepository,
    private val tempDir: File,
) {
    data class Plan(
        /** draftへ引き継ぐ既存ファイル (パス付き) */
        val keep: List<MoodleFile>,
        /** 新規に追加するファイル (null なら削除のみ)。ルート "/" に置く */
        val add: LocalFile?,
        /** 追加によって置き換えられる既存ファイル (同名・同パス)。確認表示用 */
        val replaced: MoodleFile?,
        /** 変更前のサーバー側ファイル集合の指紋 (競合検出用) */
        val baseline: Set<SubmissionFileFingerprint>,
    ) {
        /** 保存後にサーバーで確認できるべき集合 (追加分はローカルサイズ) */
        val expectedFingerprints: Set<SubmissionFileFingerprint>
            get() = keep.map { SubmissionFileFingerprint.of(it) }.toSet() +
                listOfNotNull(add?.let { SubmissionFileFingerprint(SubmissionFileKey.root(it.filename), it.sizeBytes) })
    }

    /** アップロードするローカルファイル。サイズは作成時点で確定させる (一時ファイル削除後も検証に使えるように) */
    data class LocalFile(val file: File, val filename: String, val sizeBytes: Long = file.length())

    /**
     * 追加・削除後の draft itemid を返す。呼び出し側は
     * [MoodleRepository.saveFileSubmission] にこの itemid を渡す。
     * 既存ファイルはサイズ一致とHTML応答でないことを確認してから再アップロードする。
     */
    suspend fun build(plan: Plan, maxBytesPerFile: Long): Long {
        val itemId = repository.unusedDraftItemId()
        for (existing in plan.keep) {
            val tmp = File.createTempFile("assignment_keep_", ".bin", tempDir)
            try {
                repository.downloadSubmissionFile(existing, tmp, maxBytesPerFile)
                repository.uploadSubmissionFile(
                    tmp,
                    existing.filename,
                    itemId,
                    filepath = MoodleClient.normalizeDraftPath(existing.filepath),
                )
            } finally {
                tmp.delete()
            }
        }
        plan.add?.let { repository.uploadSubmissionFile(it.file, it.filename, itemId, filepath = "/") }
        return itemId
    }

    companion object {
        /**
         * 追加・削除後に残す既存ファイル集合を決める。識別は (filepath, filename)。
         * - [remove] は削除
         * - 追加ファイルと同じキー (ルートの同名) の既存ファイルは置き換え (再アップロードしない)
         */
        fun plan(
            existing: List<MoodleFile>,
            add: LocalFile?,
            remove: Set<SubmissionFileKey> = emptySet(),
        ): Plan {
            val addKey = add?.let { SubmissionFileKey.root(it.filename) }
            val replaced = existing.firstOrNull { addKey != null && SubmissionFileKey.of(it) == addKey }
            val keep = existing
                .filter { SubmissionFileKey.of(it) !in remove }
                .filter { addKey == null || SubmissionFileKey.of(it) != addKey }
                .distinctBy { SubmissionFileKey.of(it) }
            return Plan(
                keep = keep,
                add = add,
                replaced = replaced,
                baseline = existing.map { SubmissionFileFingerprint.of(it) }.toSet(),
            )
        }

        /** 件数上限 (0 は無制限) を超える場合の理由。問題なければ null。 */
        fun fileCountViolation(plan: Plan, maxFiles: Int): String? {
            if (maxFiles <= 0) return null
            val total = plan.keep.size + (if (plan.add != null) 1 else 0)
            return if (total > maxFiles) "この課題で提出できるファイルは最大${maxFiles}件です。" else null
        }

        /** サイズ上限 (0 は無制限) を超える場合の理由。問題なければ null。 */
        fun fileSizeViolation(sizeBytes: Long, maxBytes: Long): String? {
            if (maxBytes <= 0L) return null
            return if (sizeBytes > maxBytes) {
                "ファイルサイズが上限 (${maxBytes / 1024 / 1024}MB) を超えています。"
            } else {
                null
            }
        }

        /**
         * 保存後のサーバー集合が期待と一致するか。サイズは既知なら 0 バイトも含めて厳密に比較し、
         * どちらかが不明なら「検証済み」とは断定しない (理由を返す)。
         * サイズ一致は内容の同一性を保証しないが、少なくとも「古いファイルのまま」は検出できる。
         */
        fun verifySaved(expected: Set<SubmissionFileFingerprint>, actual: List<MoodleFile>): String? {
            val actualFingerprints = actual.map { SubmissionFileFingerprint.of(it) }
            val actualByKey = actualFingerprints.associateBy { it.key }
            if (actualByKey.keys != expected.map { it.key }.toSet()) {
                return "サーバー上のファイル一覧が想定と異なります。"
            }
            for (exp in expected) {
                val act = actualByKey.getValue(exp.key)
                val expSize = exp.sizeBytes ?: return "${exp.key.filename} の保存前サイズが不明なため検証できません。"
                val actSize = act.sizeBytes ?: return "サーバーが ${exp.key.filename} のサイズを返さないため検証できません。"
                if (expSize != actSize) {
                    return "サーバー上の ${exp.key.filename} のサイズ ($actSize) が保存した内容 ($expSize) と一致しません。"
                }
            }
            return null
        }
    }
}
