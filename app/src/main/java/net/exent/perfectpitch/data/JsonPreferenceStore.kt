package net.exent.perfectpitch.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

inline fun <reified K, reified V> JsonPreferenceStore(
    dataStore: DataStore<Preferences>,
    keyName: String,
    json: Json = Json { ignoreUnknownKeys = true }
): JsonPreferenceStore<K, V> = JsonPreferenceStore(
    dataStore = dataStore,
    keyName = keyName,
    keySerializer = serializer(),
    json = json
)

class JsonPreferenceStore<K, V>(
    private val dataStore: DataStore<Preferences>,
    keyName: String,
    private val keySerializer: KSerializer<Map<K, V>>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val preferencesKey = stringPreferencesKey(keyName)

    val flow: Flow<Map<K, V>> = dataStore.data.map { prefs ->
        parse(prefs[preferencesKey])
    }

    suspend fun update(key: K, update: (V?) -> V) {
        dataStore.edit { prefs ->
            val current = parse(prefs[preferencesKey]).toMutableMap()
            current[key] = update(current[key])
            prefs[preferencesKey] = json.encodeToString(keySerializer, current)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(preferencesKey)
        }
    }

    suspend fun set(key: K, value: V) = update(key) { value }

    private fun parse(jsonStr: String?): Map<K, V> {
        if (jsonStr.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(keySerializer, jsonStr)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

