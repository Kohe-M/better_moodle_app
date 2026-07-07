package dev.rits.bettermoodle.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * ログイントークンや軽量キャッシュの永続化。
 * (本格運用では EncryptedSharedPreferences 等への移行を検討)
 */
class SessionStore(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("ws_token")
        val PRIVATE_TOKEN = stringPreferencesKey("private_token")
        val FULL_NAME = stringPreferencesKey("full_name")
        val SYLLABUS_CACHE = stringPreferencesKey("syllabus_url_cache") // JSON {code: url}
        val NOTIFIED_EVENT_IDS = stringSetPreferencesKey("notified_event_ids")
    }

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[Keys.TOKEN] }
    val fullNameFlow: Flow<String?> = context.dataStore.data.map { it[Keys.FULL_NAME] }

    suspend fun token(): String? = tokenFlow.first()

    suspend fun saveLogin(token: String, privateToken: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = token
            if (privateToken != null) prefs[Keys.PRIVATE_TOKEN] = privateToken
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
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.PRIVATE_TOKEN)
        }
    }

    suspend fun syllabusCache(): Map<String, String> {
        val raw = context.dataStore.data.first()[Keys.SYLLABUS_CACHE] ?: return emptyMap()
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    suspend fun saveSyllabusUrl(code: String, url: String) {
        val updated = syllabusCache() + (code to url)
        context.dataStore.edit {
            it[Keys.SYLLABUS_CACHE] = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(), updated,
            )
        }
    }

    suspend fun notifiedEventIds(): Set<String> =
        context.dataStore.data.first()[Keys.NOTIFIED_EVENT_IDS] ?: emptySet()

    suspend fun addNotifiedEventIds(ids: Set<String>) {
        context.dataStore.edit {
            it[Keys.NOTIFIED_EVENT_IDS] = (it[Keys.NOTIFIED_EVENT_IDS] ?: emptySet()) + ids
        }
    }
}
