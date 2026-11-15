package net.exent.harmonisk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.playlistStore: DataStore<Preferences> by preferencesDataStore(
    name = "playlists"
)

class PlaylistRepository(context: Context) {
    @Serializable
    data class PlaylistEntry(
        val name: String,
        val melodyIds: List<String> = emptyList()
    )

    data class Playlist(
        val id: String,
        val name: String,
        val melodyIds: List<String>
    )

    private val store = JsonPreferenceStore<String, PlaylistEntry>(
        dataStore = context.playlistStore,
        keyName = "playlists_json"
    )

    val playlists: Flow<List<Playlist>> = store.flow.map { entries ->
        entries.map { (id, entry) -> Playlist(id, entry.name, entry.melodyIds) }
    }

    suspend fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        store.set(id, PlaylistEntry(name.trim()))
        return id
    }

    suspend fun rename(id: String, name: String) {
        val entry = store.flow.first()[id] ?: return
        store.set(id, entry.copy(name = name.trim()))
    }

    suspend fun delete(id: String) = store.remove(id)

    suspend fun setMelodyIds(id: String, melodyIds: List<String>) {
        val entry = store.flow.first()[id] ?: return
        store.set(id, entry.copy(melodyIds = melodyIds))
    }

    suspend fun removeMelodyFromAll(melodyId: String) {
        for ((id, entry) in store.flow.first()) {
            if (melodyId in entry.melodyIds) {
                store.set(id, entry.copy(melodyIds = entry.melodyIds - melodyId))
            }
        }
    }
}
