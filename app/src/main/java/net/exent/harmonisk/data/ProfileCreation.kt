package net.exent.harmonisk.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.exent.harmonisk.music.Instrument

fun createProfileWithInstrument(
    scope: CoroutineScope,
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    name: String,
    emoji: String,
    instrument: Instrument
): Boolean {
    val created = profileRepository.createProfile(name, emoji, makeActive = false) ?: return false
    scope.launch {
        settingsRepository.updateInstrument(created.id, instrument.name)
        profileRepository.selectProfile(created.id)
    }
    return true
}
