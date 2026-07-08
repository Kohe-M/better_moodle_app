package dev.rits.bettermoodle.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
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
class SessionStore(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("ws_token")
        val PRIVATE_TOKEN = stringPreferencesKey("private_token")
        val ENCRYPTED_TOKEN = stringPreferencesKey("encrypted_ws_token_v1")
        val FULL_NAME = stringPreferencesKey("full_name")
        val SYLLABUS_CACHE = stringPreferencesKey("syllabus_url_cache") // JSON {code: url}
        val NOTIFIED_EVENT_IDS = stringSetPreferencesKey("notified_event_ids")
    }

    private val migrationMutex = Mutex()
    @Volatile private var migrationAttempted = false

    val tokenFlow: Flow<String?> = flow {
        migrateLegacyAuthIfNeeded()
        emitAll(context.dataStore.data.map { prefs ->
            decryptToken(prefs[Keys.ENCRYPTED_TOKEN])
        })
    }
    val fullNameFlow: Flow<String?> = context.dataStore.data.map { it[Keys.FULL_NAME] }

    suspend fun token(): String? {
        migrateLegacyAuthIfNeeded()
        return decryptToken(context.dataStore.data.first()[Keys.ENCRYPTED_TOKEN])
    }

    suspend fun saveLogin(token: String, privateToken: String?) {
        val encrypted = encryptToken(token)
        context.dataStore.edit { prefs ->
            prefs[Keys.ENCRYPTED_TOKEN] = encrypted
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.PRIVATE_TOKEN)
        }
    }

    suspend fun saveFullName(name: String) {
        context.dataStore.edit { it[Keys.FULL_NAME] = name }
    }

    suspend fun logout() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun clearAuthTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ENCRYPTED_TOKEN)
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.PRIVATE_TOKEN)
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

    suspend fun syllabusCache(): Map<String, String> {
        val raw = context.dataStore.data.first()[Keys.SYLLABUS_CACHE] ?: return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    suspend fun saveSyllabusUrl(code: String, year: Int, url: String) {
        val updated = syllabusCache() + (syllabusCacheKey(code, year) to url)
        context.dataStore.edit {
            it[Keys.SYLLABUS_CACHE] = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(), updated,
            )
        }
    }

    fun syllabusCacheKey(code: String, year: Int): String = "$year:$code"

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
