package dev.rits.bettermoodle.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executor

/** 下書きファイルの暗号化。Android では [KeystoreCrypto]、テストでは恒等変換 */
interface DraftCodec {
    fun encode(plain: String): String

    /** 復号できなければ null */
    fun decode(stored: String): String?
}

/**
 * 課題1件分の下書きを端末内のファイルへ保存する [DraftStateStore] (G02)。
 *
 * SavedStateHandle は画面 (NavBackStackEntry) の寿命に縛られ、セッション失効で画面スタックが作り直されると
 * 下書きが失われる。このストアはアプリの私的領域 (バックアップ対象外) に暗号化して保存するので、
 * 強制ログアウト・再ログイン・タスク終了・端末再起動をまたいで残る。
 *
 * - 読み取りはメモリ上の値 (生成時にファイルから読み込む)
 * - 書き込みはメモリへ即時反映し、ファイルへは [writer] (単一スレッド) で最新の内容を書く。
 *   キー入力ごとの保存でも UI スレッドでディスク I/O をしない
 * - 全キーが消えたらファイルを削除する
 */
class FileDraftStore(
    private val file: File,
    private val codec: DraftCodec,
    private val writer: Executor,
) : DraftStateStore {
    private val values: MutableMap<String, String> = load().toMutableMap()
    private val lock = Any()
    @Volatile private var version = 0L

    override fun get(key: String): Any? = synchronized(lock) { values[key] }

    override fun set(key: String, value: String?) {
        val snapshot: Map<String, String>
        val myVersion: Long
        synchronized(lock) {
            val changed = if (value == null) values.remove(key) != null else values.put(key, value) != value
            if (!changed) return
            snapshot = values.toMap()
            myVersion = ++version
        }
        writer.execute {
            // 後続の書き込みが予約済みなら古いスナップショットは書かない
            if (myVersion != version) return@execute
            persist(snapshot)
        }
    }

    private fun persist(snapshot: Map<String, String>) {
        runCatching {
            if (snapshot.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(codec.encode(json.encodeToString(serializer, snapshot)))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun load(): Map<String, String> = runCatching {
        if (!file.exists()) return emptyMap()
        val plain = codec.decode(file.readText()) ?: return emptyMap()
        json.decodeFromString(serializer, plain)
    }.getOrDefault(emptyMap())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        private const val SUFFIX = ".draft"

        /** 課題の下書きファイル。cmid が分かればそれで、無ければインスタンスIDで識別する */
        fun fileFor(dir: File, moduleId: Long, assignId: Long): File =
            File(dir, if (moduleId > 0L) "cm$moduleId$SUFFIX" else "a$assignId$SUFFIX")

        /** 保存中の下書き (未送信の本文・未確認の書き込み・保留草稿) の件数 */
        fun count(dir: File): Int = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.size ?: 0

        /** 明示的なログアウトで下書きを消す */
        fun deleteAll(dir: File) {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
}

/**
 * 端末保存 ([primary]) を正とし、旧版が SavedStateHandle ([fallback]) に残した値も読めるようにする。
 * 書き込みは両方へ行う (旧形式からの移行後も同じ内容に揃う)。
 */
class LayeredDraftStore(
    private val primary: DraftStateStore,
    private val fallback: DraftStateStore,
) : DraftStateStore {
    override fun get(key: String): Any? = primary[key] ?: fallback[key]

    override fun set(key: String, value: String?) {
        primary[key] = value
        fallback[key] = value
    }
}
