package net.exent.harmonisk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRepositoryTest {

    @Test
    fun `default profile keeps legacy keys`() {
        assertEquals("instrument_name", ProfileRepository.scopedKey(ProfileRepository.DEFAULT_PROFILE_ID, "instrument_name"))
        assertEquals("instrument_name", ProfileRepository.unscopedKey(ProfileRepository.DEFAULT_PROFILE_ID, "instrument_name"))
    }

    @Test
    fun `other profiles prefix keys`() {
        val scoped = ProfileRepository.scopedKey("profile-1", "instrument_name")
        assertEquals("@profile:profile-1:instrument_name", scoped)
        assertEquals("instrument_name", ProfileRepository.unscopedKey("profile-1", scoped))
    }

    @Test
    fun `default profile ignores other profiles' keys`() {
        assertNull(ProfileRepository.unscopedKey(ProfileRepository.DEFAULT_PROFILE_ID, "@profile:profile-1:instrument_name"))
    }

    @Test
    fun `other profiles ignore legacy and default keys`() {
        assertNull(ProfileRepository.unscopedKey("profile-1", "instrument_name"))
        assertNull(ProfileRepository.unscopedKey("profile-1", "@profile:profile-2:instrument_name"))
    }
}
