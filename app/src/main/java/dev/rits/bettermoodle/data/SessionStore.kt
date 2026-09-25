package dev.rits.bettermoodle.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * ログイントークンや軽量キャッシュの永続化。
 * (本格運用では EncryptedSharedPreferences 等への移行を検討)
 */
class SessionStore(private val context: Context) : AuthTokenStore, SyllabusUrlCache {

    private object Keys {
        val TOKEN = stringPreferencesKey("ws_token")
        val PRIVATE_TOKEN = stringPreferencesKey("private_token")
        val ENCRYPTED_TOKEN = stringPreferencesKey("encrypted_ws_token_v1")
        val ENCRYPTED_PRIVATE_TOKEN = stringPreferencesKey("encrypted_private_token_v1")
        val FULL_NAME = stringPreferencesKey("full_name")
        val SYLLABUS_CACHE = stringPreferencesKey("syllabus_url_cache") // JSON {code: url}
        val NOTIFIED_EVENT_IDS = stringSetPreferencesKey("notified_event_ids")
        val CONSUMED_DEEP_LINK = stringPreferencesKey("consumed_deep_link")
        val DEADLINE_CURSOR_FROM = longPreferencesKey("deadline_cursor_from")
        val DEADLINE_CURSOR_AFTER = longPreferencesKey("deadline_cursor_after")
    }

    private val migrationMutex = Mutex()
    @Volatile private var migrationAttempted = false

    override val tokenFlow: Flow<String?> = flow {
        migrateLegacyAuthIfNeeded()
        emitAll(context.dataStore.data.map { prefs ->
            decryptToken(prefs[Keys.ENCRYPTED_TOKEN])
        })
    }
    val fullNameFlow: Flow<String?> = context.dataStore.data.map { it[Keys.FULL_NAME] }

    override suspend fun token(): String? {
        migrateLegacyAuthIfNeeded()
        return decryptToken(context.dataStore.data.first()[Keys.ENCRYPTED_TOKEN])
    }

    suspend fun privateToken(): String? {
        migrateLegacyAuthIfNeeded()
        return decryptToken(context.dataStore.data.first()[Keys.ENCRYPTED_PRIVATE_TOKEN])
    }

    override suspend fun saveLogin(token: String, privateToken: String?) {
        val encrypted = encryptToken(token)
        val encryptedPrivateToken = privateToken
            ?.takeIf { it.isNotBlank() }
            ?.let(::encryptToken)
        context.dataStore.edit { prefs ->
            prefs[Keys.ENCRYPTED_TOKEN] = encrypted
            if (encryptedPrivateToken != null) {
                prefs[Keys.ENCRYPTED_PRIVATE_TOKEN] = encryptedPrivateToken
            } else {
                prefs.remove(Keys.ENCRYPTED_PRIVATE_TOKEN)
            }
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.PRIVATE_TOKEN)
        }
    }

    suspend fun saveFullName(name: String) {
        context.dataStore.edit { it[Keys.FULL_NAME] = name }
    }

    override suspend fun logout() {
        context.dataStore.edit { it.clear() }
    }

    override suspend fun clearAuthTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ENCRYPTED_TOKEN)
            prefs.remove(Keys.ENCRYPTED_PRIVATE_TOKEN)
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.PRIVATE_TOKEN)
            // 通知済み集合・取得カーソル・通知宛先はアカウント (セッション) に紐づくので一緒に消す
            prefs.remove(Keys.NOTIFIED_EVENT_IDS)
            prefs.remove(Keys.DEADLINE_CURSOR_FROM)
            prefs.remove(Keys.DEADLINE_CURSOR_AFTER)
            prefs.remove(Keys.CONSUMED_DEEP_LINK)
        }
    }

    private suspend fun migrateLegacyAuthIfNeeded() {
        if (migrationAttempted) return
        migrationMutex.withLock {
            if (migrationAttempted) return
            context.dataStore.edit { prefs ->
                val legacyToken = prefs[Keys.TOKEN]
                if (legacyToken != null && prefs[Keys.ENCRYPTED_TOKEN] == null) {
                    runCatching {
                        prefs[Keys.ENCRYPTED_TOKEN] = encryptToken(legacyToken)
                    }.onFailure {
                        prefs.remove(Keys.ENCRYPTED_TOKEN)
                    }
                }
                prefs.remove(Keys.TOKEN)
                prefs.remove(Keys.PRIVATE_TOKEN)
            }
            migrationAttempted = true
        }
    }

    private fun encryptToken(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    }

    private fun decryptToken(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val parts = value.split(':')
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override suspend fun syllabusCache(): Map<String, String> {
        val raw = context.dataStore.data.first()[Keys.SYLLABUS_CACHE] ?: return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    override suspend fun saveSyllabusUrl(code: String, year: Int, url: String) {
        // read-modify-write を edit 内で行い、同時解決で他コードの更新を失わないようにする
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SYLLABUS_CACHE]?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
                }.getOrDefault(emptyMap())
            }.orEmpty()
            val updated = current + (syllabusCacheKey(code, year) to url)
            prefs[Keys.SYLLABUS_CACHE] = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(), updated,
            )
        }
    }

    override fun syllabusCacheKey(code: String, year: Int): String = "$year:$code"

    /** 消費済みの通知宛先キー (プロセス再生成後に同じ Intent を二重に処理しないため) */
    suspend fun consumedDeepLinkKey(): String? =
        context.dataStore.data.first()[Keys.CONSUMED_DEEP_LINK]

    suspend fun markDeepLinkConsumed(key: String) {
        context.dataStore.edit { it[Keys.CONSUMED_DEEP_LINK] = key }
    }

    /** Worker が取得上限で打ち切ったときの継続位置 */
    suspend fun deadlineCursor(): dev.rits.bettermoodle.work.DeadlineCursor? {
        val prefs = context.dataStore.data.first()
        val from = prefs[Keys.DEADLINE_CURSOR_FROM] ?: return null
        val after = prefs[Keys.DEADLINE_CURSOR_AFTER] ?: return null
        return dev.rits.bettermoodle.work.DeadlineCursor(from, after)
    }

    suspend fun saveDeadlineCursor(cursor: dev.rits.bettermoodle.work.DeadlineCursor?) {
        context.dataStore.edit { prefs ->
            if (cursor == null) {
                prefs.remove(Keys.DEADLINE_CURSOR_FROM)
                prefs.remove(Keys.DEADLINE_CURSOR_AFTER)
            } else {
                prefs[Keys.DEADLINE_CURSOR_FROM] = cursor.fromEpochSec
                prefs[Keys.DEADLINE_CURSOR_AFTER] = cursor.afterEventId
            }
        }
    }

    suspend fun notifiedEventIds(): Set<String> =
        context.dataStore.data.first()[Keys.NOTIFIED_EVENT_IDS] ?: emptySet()

    suspend fun addNotifiedEventIds(ids: Set<String>) {
        context.dataStore.edit {
            it[Keys.NOTIFIED_EVENT_IDS] = (it[Keys.NOTIFIED_EVENT_IDS] ?: emptySet()) + ids
        }
    }

    suspend fun replaceNotifiedEventIds(ids: Set<String>) {
        context.dataStore.edit {
            if (ids.isEmpty()) {
                it.remove(Keys.NOTIFIED_EVENT_IDS)
            } else {
                it[Keys.NOTIFIED_EVENT_IDS] = ids
            }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "better_moodle_ws_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
