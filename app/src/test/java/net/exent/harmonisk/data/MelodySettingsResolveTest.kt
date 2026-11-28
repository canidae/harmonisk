package net.exent.harmonisk.data

import net.exent.harmonisk.data.MelodySettingsRepository.Companion.OVERRIDE_EASY_KEY
import net.exent.harmonisk.data.MelodySettingsRepository.Companion.OVERRIDE_METRONOME
import net.exent.harmonisk.data.MelodySettingsRepository.Companion.OVERRIDE_OPEN_STRINGS
import net.exent.harmonisk.data.MelodySettingsRepository.Companion.resolve
import org.junit.Assert.assertEquals
import org.junit.Test

class MelodySettingsResolveTest {

    private val defaults = ProfileDefaults(
        startingLevel = 3,
        metronomeMode = MetronomeMode.LEAD_IN,
        preferOpenStrings = false,
        easyKey = false
    )

    @Test
    fun `song without settings follows profile defaults`() {
        val resolved = resolve(null, defaults)
        assertEquals(3, resolved.level)
        assertEquals(false, resolved.preferOpenStrings)
        assertEquals(false, resolved.autoTranspose)
        assertEquals(MetronomeMode.LEAD_IN.name, resolved.metronomeMode)
        assertEquals(emptyList<String>(), resolved.overrides)
    }

    @Test
    fun `song overrides win over profile defaults`() {
        val stored = MelodySettingsRepository.MelodySettings(
            autoTranspose = true,
            preferOpenStrings = true,
            metronomeMode = MetronomeMode.OFF.name,
            level = 5,
            overrides = listOf(OVERRIDE_OPEN_STRINGS, OVERRIDE_EASY_KEY, OVERRIDE_METRONOME)
        )
        val resolved = resolve(stored, defaults)
        assertEquals(5, resolved.level)
        assertEquals(true, resolved.preferOpenStrings)
        assertEquals(true, resolved.autoTranspose)
        assertEquals(MetronomeMode.OFF.name, resolved.metronomeMode)
    }

    @Test
    fun `unpinned preferences follow profile defaults`() {
        val stored = MelodySettingsRepository.MelodySettings(
            autoTranspose = true,
            preferOpenStrings = true,
            metronomeMode = MetronomeMode.OFF.name,
            level = 4,
            overrides = listOf(OVERRIDE_OPEN_STRINGS)
        )
        val resolved = resolve(stored, defaults)
        assertEquals(4, resolved.level)
        assertEquals(true, resolved.preferOpenStrings)
        assertEquals(false, resolved.autoTranspose)
        assertEquals(MetronomeMode.LEAD_IN.name, resolved.metronomeMode)
        assertEquals(listOf(OVERRIDE_OPEN_STRINGS), resolved.overrides)
    }

    @Test
    fun `legacy settings keep changed preferences and inherit the rest`() {
        val stored = MelodySettingsRepository.MelodySettings(
            autoTranspose = true,
            preferOpenStrings = false,
            metronomeMode = MetronomeMode.OFF.name,
            level = 2,
            overrides = null
        )
        val resolved = resolve(stored, defaults)
        assertEquals(2, resolved.level)
        assertEquals(false, resolved.preferOpenStrings)
        assertEquals(true, resolved.autoTranspose)
        assertEquals(MetronomeMode.LEAD_IN.name, resolved.metronomeMode)
        assertEquals(listOf(OVERRIDE_EASY_KEY), resolved.overrides)
    }

    @Test
    fun `legacy settings matching defaults have no overrides`() {
        val stored = MelodySettingsRepository.MelodySettings(
            autoTranspose = false,
            preferOpenStrings = false,
            level = 1
        )
        val resolved = resolve(stored, defaults)
        assertEquals(1, resolved.level)
        assertEquals(false, resolved.preferOpenStrings)
        assertEquals(false, resolved.autoTranspose)
        assertEquals(emptyList<String>(), resolved.overrides)
    }

    @Test
    fun `cleared overrides follow profile defaults`() {
        val stored = MelodySettingsRepository.MelodySettings(
            autoTranspose = true,
            preferOpenStrings = true,
            metronomeMode = MetronomeMode.ON.name,
            level = 2,
            overrides = emptyList()
        )
        val resolved = resolve(stored, defaults)
        assertEquals(2, resolved.level)
        assertEquals(false, resolved.preferOpenStrings)
        assertEquals(false, resolved.autoTranspose)
        assertEquals(MetronomeMode.LEAD_IN.name, resolved.metronomeMode)
    }
}
