package net.exent.harmonisk.ui.melody

import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.NoteEvent
import net.exent.harmonisk.midi.TempoEvent
import net.exent.harmonisk.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which melody voices the app sounds while the player is being scored.
 *
 * Playing voices is a developer feature: a player who is being scored must not hear the melody they
 * are asked to play, however the accompaniment setting was left. The song is still there to listen
 * to before the take, as a preview.
 */
class AudibleMelodyTracksTest {

    private fun track(channel: Int) = MelodyTrack(
        name = "voice $channel",
        notes = listOf(NoteEvent(60 + channel, 100, 0L, 480L)),
        tempoChanges = listOf(TempoEvent(0L, 500_000L)),
        timeSignatures = listOf(TimeSignatureEvent(0L, 4, 4)),
        ticksPerQuarterNote = 480,
        channel = channel
    )

    private val voices = listOf(track(0), track(1), track(2))

    private fun audible(mode: AccompanimentMode, devModeEnabled: Boolean, activeVoiceIndex: Int = 0) =
        audibleMelodyTracks(
            melodyTracks = voices,
            activeVoiceIndex = activeVoiceIndex,
            accompanimentMode = mode,
            devModeEnabled = devModeEnabled
        )

    @Test
    fun everyAccompanimentSettingIsSilentOutsideDeveloperMode() {
        for (mode in AccompanimentMode.entries) {
            assertTrue(
                "no voice may sound while a take is scored outside developer mode ($mode)",
                audible(mode, devModeEnabled = false).isEmpty()
            )
        }
    }

    @Test
    fun theVoiceThePlayerIsScoredOnIsSilentOutsideDeveloperMode() {
        assertTrue(
            "the voice the player is asked to play must not be played for them",
            audible(AccompanimentMode.SELECTED_ONLY, devModeEnabled = false).isEmpty()
        )
    }

    @Test
    fun developerModeKeepsEveryVoice() {
        assertEquals(voices, audible(AccompanimentMode.ALL, devModeEnabled = true))
    }

    @Test
    fun developerModeKeepsTheAccompaniment() {
        assertEquals(
            listOf(voices[1], voices[2]),
            audible(AccompanimentMode.EXCEPT, devModeEnabled = true, activeVoiceIndex = 0)
        )
    }

    @Test
    fun developerModeCanPlayOneVoiceAlone() {
        assertEquals(
            listOf(voices[1]),
            audible(AccompanimentMode.SELECTED_ONLY, devModeEnabled = true, activeVoiceIndex = 1)
        )
    }

    @Test
    fun developerModeCanStillAskForSilence() {
        assertTrue(audible(AccompanimentMode.NONE, devModeEnabled = true).isEmpty())
    }

    @Test
    fun aVoiceOutsideTheLoadedMelodyPlaysNothing() {
        assertTrue(audible(AccompanimentMode.SELECTED_ONLY, devModeEnabled = true, activeVoiceIndex = 7).isEmpty())
    }
}
