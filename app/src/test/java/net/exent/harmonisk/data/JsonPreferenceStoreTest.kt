package net.exent.harmonisk.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonPreferenceStoreTest {

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `removeWhere deletes only matching keys`() = runBlocking {
        val store = JsonPreferenceStore<String, String>(FakeDataStore(), "values")
        store.set("keep", "a")
        store.set("drop:1", "b")
        store.set("drop:2", "c")

        store.removeWhere { it.startsWith("drop:") }

        assertEquals(mapOf("keep" to "a"), store.flow.first())
    }
}
