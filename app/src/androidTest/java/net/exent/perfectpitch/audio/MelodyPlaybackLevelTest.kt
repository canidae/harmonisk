package net.exent.perfectpitch.audio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.exent.perfectpitch.data.DifficultyRepository
import net.exent.perfectpitch.data.MelodyRepository
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.ui.melody.NoteArticulationTracker
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the microphone hears when the app plays a melody through its own soundfont — the audio output
 * Play by Melody has to score when a player turns the accompaniment on.
 *
 * Nothing else is playing: the sound the game has to hear is the app's own, note releases and all.
 * The volume is the test volume rather than the maximum, because a speaker driven to its limit
 * squashes exactly the level differences a pause is made of.
 */
@RunWith(AndroidJUnit4::class)
class MelodyPlaybackLevelTest {

    private companion object {
        private const val TAG = "MelodyPlayback"
        private const val MELODY = "jeg_kan_spille_fiolin"
        private const val LISTEN_SECONDS = 5.0

        /** Frames either side of the first repeated note to print in detail. */
        private const val WINDOW_FRAMES = 10
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val noteUtils = NoteUtils()

    @Before
    fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    /**
     * How long a note takes to stop after the app releases it. If a released note is still sounding
     * when the next one starts, the app's own playing has no pause in it for the game to hear — which
     * is why the repeated note cannot be credited.
     */
    @Test
    fun whatTheAppReleasingANoteSoundsLike() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volume = TunerAudioFixture.testVolume(audioManager)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)

        val synthesizer = SoundFontSynthesizer(context)
        val processor = PitchProcessor(AudioCaptureService(context), CrepePitchDetector(context), noteUtils)
        val held = 0.5
        val notes = mutableListOf<Pair<Long, Float?>>()
        try {
            synthesizer.open()
            assertTrue("the synthesizer must open", synthesizer.isReady)
            synthesizer.setInstrument(0, 40)

            runBlocking {
                val listening = launch(Dispatchers.IO) {
                    processor.pitchFlow().collect { result ->
                        synchronized(notes) {
                            notes.add(SystemClock.elapsedRealtime() to (result.levelDb ?: result.levelDb))
                        }
                    }
                }
                val start = SystemClock.elapsedRealtime()
                synthesizer.noteOn(0, 62, 100)
                var released = false
                while ((SystemClock.elapsedRealtime() - start) < 2000) {
                    if (!released && (SystemClock.elapsedRealtime() - start) / 1000.0 >= held) {
                        synthesizer.noteOff(0, 62)
                        released = true
                    }
                    delay(16)
                }
                listening.cancel()
            }
        } finally {
            synthesizer.allNotesOff()
            synthesizer.close()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        }

        Log.i(TAG, "=== D4 released after ${held}s, volume $volume, ${notes.size} frames ===")
        val releasedAt = notes.firstOrNull { it.first > notes.first().first + (held * 1000).toLong() }?.first
            ?: notes.first().first
        val trace = notes.filterIndexed { index, _ -> index % 3 == 0 }.map { (time, level) ->
            "%.2f=%s".format((time - notes.first().first) / 1000.0, level?.let { "%.1f".format(it) } ?: "-")
        }
        trace.chunked(8).forEach { Log.i(TAG, it.joinToString(" ")) }

        // How far the level has fallen 200 ms, 500 ms and 1 s after the release.
        val levels = mutableMapOf<Int, Pair<Float, Float>>()
        for (afterMs in listOf(100, 200, 500, 1000)) {
            val before = notes.lastOrNull { it.first <= releasedAt }?.second
            val after = notes.firstOrNull { it.first >= releasedAt + afterMs }?.second
            if (before != null && after != null) {
                levels[afterMs] = before to after
                Log.i(TAG, "level ${afterMs}ms after release: ${"%.1f".format(before)} -> ${"%.1f".format(after)}")
            }
        }

        val heldMs = 200
        val goneMs = 500
        val (heldBefore, heldAfter) = levels.getValue(heldMs)
        val (goneBefore, goneAfter) = levels.getValue(goneMs)
        assertTrue(
            "a released note must still be sounding ${heldMs}ms later, or the notation's short gap " +
                    "would be audible: ${"%.1f".format(heldBefore)} -> ${"%.1f".format(heldAfter)}",
            heldBefore - heldAfter < SoundingToneGate.RELEASE_FLOOR_DB
        )
        assertTrue(
            "a released note must be gone ${goneMs}ms later: " +
                    "${"%.1f".format(goneBefore)} -> ${"%.1f".format(goneAfter)}",
            goneBefore - goneAfter > SoundingToneGate.SLOW_DROP_DB
        )
    }

    /**
     * The app's own repeated note carries no pause the microphone could hear, which is why Play by
     * Melody scores the notes it plays itself instead of waiting for one (see MelodyViewModel).
     */
    @Test
    fun theAppsOwnPlayingHasNoPauseAtARepeatedNote() {
        val repository = MelodyRepository(context, DifficultyRepository(context))
        val info = repository.getBundledMelodies().first { it.id.contains(MELODY) }
        val tracks = repository.loadMultiTrack(info)
        assertTrue("the bundled melody must load", tracks.isNotEmpty())

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volume = TunerAudioFixture.testVolume(audioManager)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)

        val synthesizer = SoundFontSynthesizer(context)
        val player = MelodyPlayer(synthesizer)
        val processor = PitchProcessor(AudioCaptureService(context), CrepePitchDetector(context), noteUtils)

        val frames = mutableListOf<Long>()
        val pitches = mutableListOf<Int>()
        val levels = mutableListOf<Float?>()
        try {
            synthesizer.open()
            assertTrue("the synthesizer must open", synthesizer.isReady)

            runBlocking {
                val listening = launch(Dispatchers.IO) {
                    processor.pitchFlow().collect { result ->
                        synchronized(frames) {
                            frames.add(SystemClock.elapsedRealtime())
                            pitches.add(if (result.isValid) result.midiNoteNumber else 0)
                            levels.add(result.levelDb)
                        }
                    }
                }
                val start = SystemClock.elapsedRealtime()
                while ((SystemClock.elapsedRealtime() - start) < (LISTEN_SECONDS * 1000).toLong()) {
                    player.updateGameTime((SystemClock.elapsedRealtime() - start) / 1000.0, tracks)
                    delay(16)
                }
                listening.cancel()
            }
        } finally {
            player.stop()
            synthesizer.close()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        }

        val hopMs = AudioConstants.FRAME_DURATION_MS.toDouble()
        Log.i(TAG, "=== The app playing $MELODY through its own soundfont, volume $volume/${
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } on ${android.os.Build.MODEL} ===")

        val gate = SoundingToneGate()
        val heard = levels.map { gate.isSounding(it?.toDouble()) }
        val notes = tracks.first().notes.take(4).joinToString(" ") {
            "${it.noteNumber}@%.2f-%.2f".format(
                tracks.first().noteStartSeconds(it),
                tracks.first().noteStartSeconds(it) + tracks.first().noteDurationSeconds(it)
            )
        }
        Log.i(TAG, "melody: $notes, ${frames.size} frames heard")

        // Frames are printed per hop of the capture, so the note boundary can be read off directly.
        for (index in frames.indices) {
            val seconds = index * hopMs / 1000.0
            if (seconds > 1.0) break
            Log.i(
                TAG,
                "t=%.2fs pitch=%d valid=%b level=%s heard=%b".format(
                    seconds, pitches[index], pitches[index] != 0,
                    levels[index]?.let { "%.1f".format(it) } ?: "-", heard[index]
                )
            )
        }

        // The boundary between the two opening D4s: what the game is given to tell them apart.
        val boundaryHop = (tracks.first().noteStartSeconds(tracks.first().notes[1]) * 1000 / hopMs).toInt()
        val from = (boundaryHop - WINDOW_FRAMES).coerceAtLeast(0)
        val to = (boundaryHop + WINDOW_FRAMES * 3).coerceAtMost(frames.size)
        val boundary = heard.subList(from, to)
        val longestUnheard = boundary.fold(0 to 0) { (best, run), value ->
            val next = if (value) 0 else run + 1
            maxOf(best, next) to next
        }.first
        Log.i(
            TAG,
            "at the repeated note: longestUnheardRun=${longestUnheard * hopMs}ms of the " +
                    "${NoteArticulationTracker.DEFAULT_MIN_SILENCE_MS.toInt()}ms a repeat needs, levels=" +
                    levels.subList(from, to).joinToString(" ") { it?.let { "%.0f".format(it) } ?: "-" }
        )

        assertTrue("the app's own playing must be heard", heard.any { it })
        assertTrue(
            "the app's own playing must carry no pause at the repeated note — the notation's gap is " +
                    "inaudible behind the released note — but ${longestUnheard * hopMs}ms was heard",
            longestUnheard * hopMs < NoteArticulationTracker.DEFAULT_MIN_SILENCE_MS
        )
    }
}
