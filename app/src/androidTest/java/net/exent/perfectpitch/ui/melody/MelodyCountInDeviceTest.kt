package net.exent.perfectpitch.ui.melody

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.exent.perfectpitch.audio.AudioDeviceMonitor
import net.exent.perfectpitch.audio.MelodyPlayer
import net.exent.perfectpitch.audio.NotePlayer
import net.exent.perfectpitch.audio.SoundFontSynthesizer
import net.exent.perfectpitch.data.AudioDelayRepository
import net.exent.perfectpitch.data.DifficultyRepository
import net.exent.perfectpitch.data.MelodyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two bars counted in before a song must play all of their beats, the first one included.
 *
 * The app schedules its own playback early by the round-trip delay it measured for the device, so
 * that its notes are heard on the beat. The count-in's first beat sits exactly where the game clock
 * starts, so on a device with a real delay it is the one beat the early scheduling can run past
 * before the clock has begun. This test gives the device such a delay, as calibration would.
 */
@RunWith(AndroidJUnit4::class)
class MelodyCountInDeviceTest {

    private companion object {
        private const val TAG = "MelodyCountIn"
        private const val SHORTEST_MELODY = "jeg_kan_spille_fiolin"

        /** Long enough to hear the whole count-in of the shortest melody and a little of the song. */
        private const val LISTEN_MS = 7_000L

        /** How late the first beat of the count-in may be and still be the first beat. */
        private const val FIRST_BEAT_DEADLINE_MS = 400L

        /** How far off its beat a count-in click may land. */
        private const val BEAT_TOLERANCE_MS = 120.0

        /** A measured round-trip delay, as a calibrated device reports. */
        private const val DEVICE_TOTAL_DELAY_MS = 200
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private class RecordingNotePlayer : NotePlayer {
        private val onsets = mutableListOf<Long>()

        override val isReady: Boolean get() = true

        override fun noteOn(channel: Int, note: Int, velocity: Int) {
            if (channel == SoundFontSynthesizer.DRUM_CHANNEL) {
                synchronized(onsets) { onsets.add(SystemClock.elapsedRealtime()) }
            }
        }

        override fun noteOff(channel: Int, note: Int) = Unit

        override fun allNotesOff() = Unit

        fun drumOnsetsSince(startWallMs: Long): List<Long> =
            synchronized(onsets) { onsets.map { it - startWallMs } }
    }

    @Test
    fun theCountInPlaysTheFirstBeatOfItsFirstBar() {
        val synthesizer = SoundFontSynthesizer(context)
        synthesizer.open()
        val player = MelodyPlayer(synthesizer)
        val drums = RecordingNotePlayer()
        val deviceMonitor = AudioDeviceMonitor(context)
        val delays = AudioDelayRepository(context, deviceMonitor)
        val deviceKey = deviceMonitor.currentDevice.value.deviceKey
        val previous = runBlocking { delays.allProfiles.first()[deviceKey] }
        runBlocking { delays.setTotalDelay(deviceKey, DEVICE_TOTAL_DELAY_MS) }
        try {
            val viewModel = melodyViewModel(context, synthesizer, player)
            // The view model's MIDI observer puts the synthesizer back in charge of the player on
            // start; having listened, the test takes it back for itself.
            Thread.sleep(500)
            player.setNotePlayer(drums)

            val melody = MelodyRepository(context, DifficultyRepository(context))
                .getBundledMelodies().first { it.id.contains(SHORTEST_MELODY) }
            assertTrue("the bundled melody must load", viewModel.loadMelody(melody))
            viewModel.configure(1, AccompanimentMode.NONE)
            if (!viewModel.gameState.value.metronomeEnabled) viewModel.toggleMetronome()
            Thread.sleep(200)
            // Subscribe as the play screen does, so the game's own delay reading is live.
            runBlocking {
                withTimeout(5_000) { viewModel.activeTotalDelay.first { it == DEVICE_TOTAL_DELAY_MS } }
            }

            val track = viewModel.gameState.value.melodyTrack!!
            val beatsInCountIn = 2 * track.timeSignatureAt(0).numerator
            val beatMs = 60_000.0 / track.bpmAt(0)

            val startWallMs = SystemClock.elapsedRealtime()
            // Pressed from the main thread, as the play button does, so the game clock's first tick
            // runs where the app's does.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.startPlaying() }
            Thread.sleep(LISTEN_MS)
            viewModel.stop()

            val onsets = drums.drumOnsetsSince(startWallMs)
            Log.i(
                TAG,
                "totalDelay=${DEVICE_TOTAL_DELAY_MS}ms  count-in=$beatsInCountIn beats  " +
                        "drum onsets at $onsets ms"
            )

            assertTrue(
                "the count-in must start on its first beat, not $onsets ms into the song",
                onsets.first() < FIRST_BEAT_DEADLINE_MS
            )
            assertTrue(
                "the count-in must play every beat of its two bars, got $onsets ms",
                onsets.size >= beatsInCountIn
            )
            onsets.take(beatsInCountIn).forEachIndexed { beat, onset ->
                assertEquals(
                    "count-in beat ${beat + 1} must land on its beat, got $onsets ms",
                    beat * beatMs, onset.toDouble(), BEAT_TOLERANCE_MS
                )
            }
        } finally {
            runBlocking {
                if (previous != null) {
                    delays.setTotalDelay(deviceKey, previous.totalDelayMs)
                    delays.setInputDelay(deviceKey, previous.inputDelayMs)
                } else {
                    delays.setTotalDelay(
                        deviceKey,
                        AudioDelayRepository.defaultTotalDelay(deviceMonitor.currentDevice.value.type)
                    )
                }
            }
            synthesizer.close()
        }
    }
}
