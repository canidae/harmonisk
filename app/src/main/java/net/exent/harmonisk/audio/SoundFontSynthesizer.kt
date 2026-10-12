package net.exent.harmonisk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log
import net.exent.harmonisk.BuildConfig
import net.exent.harmonisk.music.Instrument
import java.io.File
import java.io.IOException

class SoundFontSynthesizer(
    private val context: Context
) : NotePlayer {

    companion object {
        private const val TAG = "SoundFontSynthesizer"
        private const val SF2_ASSET = "GeneralUser-GS.sf2"
        private const val BUFFER_FRAMES = 512
        private const val DEFAULT_OUTPUT_SAMPLE_RATE = 44100
        const val DRUM_CHANNEL = 9

        init {
            System.loadLibrary("soundfont")
        }

        @JvmStatic
        private external fun nativeLoad(path: String, sampleRate: Int): Long
        @JvmStatic
        private external fun nativeUnload(handle: Long)
        @JvmStatic
        private external fun nativeActiveVoiceCount(handle: Long): Int
        @JvmStatic
        private external fun nativeSetChannelPreset(
            handle: Long, channel: Int, bank: Int, preset: Int
        ): Boolean
        @JvmStatic
        private external fun nativeSetChannelDrum(
            handle: Long, channel: Int
        ): Boolean
        @JvmStatic
        private external fun nativeChannelNoteOn(
            handle: Long, channel: Int, key: Int, velocity: Float
        )
        @JvmStatic
        private external fun nativeChannelNoteOff(
            handle: Long, channel: Int, key: Int
        )
        @JvmStatic
        private external fun nativeAllNotesOff(handle: Long)
        @JvmStatic
        private external fun nativeRender(
            handle: Long, buffer: ShortArray, samples: Int
        )
    }

    @Volatile
    private var nativeHandle: Long = 0L

    @Volatile
    var debugActiveVoices: Int = 0
        private set

    @Volatile
    private var channelsInitialized = false

    private var currentBank: Int = Instrument.VIOLIN.midiBank
    private var currentProgram: Int = Instrument.VIOLIN.midiProgram

    /**
     * The mixer both the synth and the track must agree on. Rendering at the device's own rate is
     * what lets the track onto the fast mixer; a mismatch sends it through the resampling path and
     * costs a couple of hundred milliseconds.
     */
    private val outputSampleRate: Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it >= 8000 }
            ?: DEFAULT_OUTPUT_SAMPLE_RATE

    private val renderThread = AudioRenderThread(
        threadName = "SoundFontRenderThread",
        sampleRate = outputSampleRate,
        contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        bufferFrames = BUFFER_FRAMES,
        render = ::renderBuffer
    )

    override val isReady: Boolean get() = nativeHandle != 0L && renderThread.running

    fun open() {
        if (nativeHandle != 0L) return
        val sf2File = extractSf2()
        if (sf2File == null) {
            Log.e(TAG, "Failed to extract SoundFont from assets")
            return
        }
        Log.d(TAG, "Loading SoundFont from: ${sf2File.absolutePath} (${sf2File.length()} bytes)")
        val handle = nativeLoad(sf2File.absolutePath, outputSampleRate)
        if (handle == 0L) {
            Log.e(TAG, "Failed to load SoundFont")
            return
        }
        nativeHandle = handle
        renderThread.open()
        if (!renderThread.running) {
            Log.e(TAG, "Failed to open the audio output")
            nativeUnload(handle)
            nativeHandle = 0L
            return
        }
        Log.i(TAG, "SoundFont synthesizer opened at $outputSampleRate Hz, isReady=$isReady")
    }

    private fun extractSf2(): File? {
        val cacheFile = File(context.filesDir, SF2_ASSET)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            Log.d(TAG, "SF2 cached at ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            return cacheFile
        }
        return try {
            Log.d(TAG, "Extracting SF2 from assets to ${cacheFile.absolutePath}")
            context.assets.open(SF2_ASSET).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "SF2 extracted (${cacheFile.length()} bytes)")
            cacheFile
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract SF2 to cache", e)
            null
        }
    }

    private fun ensureChannels(handle: Long) {
        if (channelsInitialized) return
        channelsInitialized = true
        Log.d(TAG, "Initializing 16 MIDI channels (ch 9=drum, rest=current instrument)")
        for (ch in 0 until 16) {
            val ok = if (ch == DRUM_CHANNEL) {
                nativeSetChannelDrum(handle, ch)
            } else {
                nativeSetChannelPreset(handle, ch, currentBank, currentProgram)
            }
            if (!ok) Log.w(TAG, "Channel $ch init failed")
        }
    }

    override fun setInstrument(bank: Int, program: Int) {
        currentBank = bank
        currentProgram = program
        val h = nativeHandle
        if (h == 0L) return
        for (ch in 0 until 16) {
            if (ch == DRUM_CHANNEL) continue
            nativeSetChannelPreset(h, ch, bank, program)
        }
    }

    override fun noteOn(channel: Int, note: Int, velocity: Int) {
        val h = nativeHandle
        if (h == 0L) return
        val vel = (velocity.coerceIn(0, 127)) / 127f
        if (vel <= 0f) return
        ensureChannels(h)
        nativeChannelNoteOn(h, channel, note, vel)
    }

    override fun noteOff(channel: Int, note: Int) {
        val h = nativeHandle
        if (h == 0L) return
        nativeChannelNoteOff(h, channel, note)
    }

    override fun allNotesOff() {
        val h = nativeHandle
        if (h == 0L) return
        nativeAllNotesOff(h)
    }

    fun close() {
        renderThread.close()
        val h = nativeHandle
        if (h != 0L) {
            nativeAllNotesOff(h)
            nativeUnload(h)
            nativeHandle = 0L
        }
        channelsInitialized = false
        Log.i(TAG, "SoundFont synthesizer closed")
    }

    private fun renderBuffer(buffer: ShortArray) {
        val h = nativeHandle
        if (h == 0L) {
            buffer.fill(0)
            return
        }
        nativeRender(h, buffer, buffer.size)
    }
}
