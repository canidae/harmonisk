package net.exent.harmonisk.data

import kotlinx.serialization.json.Json
import net.exent.harmonisk.data.AudioDelayRepository.DelayProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class DelayProfileTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a delay stored before the input delay was removed keeps its value`() {
        val profile = json.decodeFromString<DelayProfile>("""{"outputDelayMs":225,"inputDelayMs":125}""")
        assertEquals(225, profile.audioDelayMs)
    }

    @Test
    fun `a profile without a stored delay uses the default`() {
        assertEquals(AudioDelayRepository.DEFAULT_AUDIO_DELAY_MS, json.decodeFromString<DelayProfile>("{}").audioDelayMs)
    }
}
