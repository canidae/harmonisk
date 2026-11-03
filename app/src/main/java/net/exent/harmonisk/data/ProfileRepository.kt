package net.exent.harmonisk.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

class ProfileRepository(context: Context) {
    companion object {
        const val MAX_NAME_LENGTH = 10
        const val DEFAULT_PROFILE_ID = "default"
        const val PROFILE_KEY_PREFIX = "@profile:"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_profile_id"

        fun scopedKey(profileId: String, key: String): String =
            if (profileId == DEFAULT_PROFILE_ID) key else "$PROFILE_KEY_PREFIX$profileId:$key"

        fun unscopedKey(profileId: String, key: String): String? =
            if (profileId == DEFAULT_PROFILE_ID) {
                key.takeUnless { it.startsWith(PROFILE_KEY_PREFIX) }
            } else {
                val prefix = "$PROFILE_KEY_PREFIX$profileId:"
                key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            }
    }

    @Serializable
    data class Profile(val id: String, val name: String)

    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Profile.serializer())

    private val _profiles = MutableStateFlow(loadProfiles())
    private val _activeProfile = MutableStateFlow(resolveActive(_profiles.value))

    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()
    val activeProfileId: String? get() = _activeProfile.value?.id

    @Synchronized
    fun createProfile(name: String): Profile? {
        val trimmed = name.trim()
        if (!isNameAvailable(trimmed)) return null

        val profile = Profile(
            id = if (_profiles.value.isEmpty()) DEFAULT_PROFILE_ID else UUID.randomUUID().toString(),
            name = trimmed
        )
        val updated = _profiles.value + profile
        _profiles.value = updated
        prefs.edit { putString(KEY_PROFILES, json.encodeToString(serializer, updated)) }
        setActive(profile)
        return profile
    }

    @Synchronized
    fun selectProfile(id: String): Boolean {
        val profile = _profiles.value.find { it.id == id } ?: return false
        setActive(profile)
        return true
    }

    fun isNameAvailable(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            trimmed.length <= MAX_NAME_LENGTH &&
            _profiles.value.none { it.name.equals(trimmed, ignoreCase = true) }
    }

    private fun setActive(profile: Profile) {
        _activeProfile.value = profile
        prefs.edit { putString(KEY_ACTIVE, profile.id) }
    }

    private fun resolveActive(profiles: List<Profile>): Profile? {
        val storedId = prefs.getString(KEY_ACTIVE, null)
        val active = profiles.find { it.id == storedId } ?: profiles.firstOrNull()
        if (active != null && active.id != storedId) {
            prefs.edit { putString(KEY_ACTIVE, active.id) }
        }
        return active
    }

    private fun loadProfiles(): List<Profile> {
        val stored = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())
    }
}
