package com.perfectpitch.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.perfectpitch.midi.MidiParser
import com.perfectpitch.music.NoteUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class PitchDetectionAccuracyTest {

    companion object {
        private const val TAG = "PitchAccTest"
        private const val CREPE_INPUT_SAMPLES = 1024
        private const val CREPE_SAMPLE_RATE = 16000
        private const val N_BINS = 360
        private val BIN_CENTS = FloatArray(N_BINS) { i ->
            (i.toFloat() / (N_BINS - 1) * 7180f) + 1997.3794f
        }
        private val BIN_FREQS = FloatArray(N_BINS) { i ->
            (10.0 * 2.0.pow(BIN_CENTS[i] / 1200.0)).toFloat()
        }
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val ANALYSIS_WINDOW_SIZE = 1024
        private const val HOP_SIZE = 256
        private const val SILENCE_DB = -50f

        private val QUARTER_FILES = listOf("quarter_cello", "quarter_guitar", "quarter_oboe", "quarter_piano")
        private val SIXTEENTH_FILES = listOf("16th_cello", "16th_guitar", "16th_oboe", "16th_piano")
        private val THIRTYSECOND_FILES = listOf("32nd_cello", "32nd_guitar", "32nd_oboe", "32nd_piano")
    }

    private lateinit var interpreter: Interpreter
    private lateinit var noteUtils: NoteUtils
    private lateinit var midiParser: MidiParser
    private lateinit var testCtx: android.content.Context

    @Before
    fun setUp() {
        noteUtils = NoteUtils()
        midiParser = MidiParser()
        testCtx = InstrumentationRegistry.getInstrumentation().context

        val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelBytes = targetCtx.assets.open("crepe_small_f16.tflite").use { it.readBytes() }
        val modelBuf = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuf.put(modelBytes)
        modelBuf.rewind()
        interpreter = Interpreter(modelBuf, Interpreter.Options().apply { numThreads = 2 })
    }

    @Test
    fun testQuarterNotes() {
        val results = QUARTER_FILES.map { evaluateFile(it) }
        printGroupReport("Quarter notes", QUARTER_FILES.joinToString(", "), results)
        assertTrue("Quarter notes: no results", results.isNotEmpty() && results.all { it.results.isNotEmpty() })
    }

    @Test
    fun testSixteenthNotes() {
        val results = SIXTEENTH_FILES.map { evaluateFile(it) }
        printGroupReport("16th notes", SIXTEENTH_FILES.joinToString(", "), results)
        assertTrue("16th notes: no results", results.isNotEmpty() && results.all { it.results.isNotEmpty() })
    }

    @Test
    fun testThirtySecondNotes() {
        val results = THIRTYSECOND_FILES.map { evaluateFile(it) }
        printGroupReport("32nd notes", THIRTYSECOND_FILES.joinToString(", "), results)
        assertTrue("32nd notes: no results", results.isNotEmpty() && results.all { it.results.isNotEmpty() })
    }

    private fun evaluateFile(name: String): FileResult {
        val midiBytes = testCtx.assets.open("midi/$name.mid").use { it.readBytes() }
        val wavBytes = testCtx.assets.open("midi/$name.wav").use { it.readBytes() }

        val track = midiParser.parse(midiBytes.inputStream(), name)
        val expectedNotes = track.notes.sortedBy { it.startTick }
        val audio = wavBytesToFloats(wavBytes)
        val detections = processAudio(audio)
        val groupedNotes = groupDetections(detections)
        val accuracy = evaluateAccuracy(expectedNotes, track, detections)
        val duration = audio.size / 16000.0

        printFileReport(name, accuracy, expectedNotes.size, detections.size,
            groupedNotes.size, duration)

        return FileResult(name, accuracy, detections.size, groupedNotes.size, duration)
    }

    private fun printGroupReport(groupName: String, files: String, results: List<FileResult>) {
        val allResults = results.flatMap { it.results }
        if (allResults.isEmpty()) {
            Log.i(TAG, "=== Group: $groupName (no results) ===")
            return
        }
        val totalExpected = allResults.size
        val correct = allResults.count { it.diff == 0 }
        val offBy1 = allResults.count { it.diff == 1 || it.diff == -1 }
        val offByMore = allResults.count { abs(it.diff) > 1 && it.diff != Int.MAX_VALUE }
        val missed = allResults.count { it.diff == Int.MAX_VALUE }
        val avgConf = allResults.filter { it.detectionCount > 0 }
            .sumOf { it.avgConfidence.toDouble() } / allResults.count { it.detectionCount > 0 }

        Log.i(TAG, "")
        Log.i(TAG, "========== GROUP: $groupName ==========")
        Log.i(TAG, "Files: $files")
        Log.i(TAG, "Total expected notes: $totalExpected")
        Log.i(TAG, "Average confidence: ${"%.3f".format(avgConf)}")
        Log.i(TAG, "Correct pitch:   $correct (${"%.1f".format(correct * 100.0 / totalExpected)}%)")
        Log.i(TAG, "Off by 1 semitone: $offBy1 (${"%.1f".format(offBy1 * 100.0 / totalExpected)}%)")
        Log.i(TAG, "Off by >1 semitone: $offByMore (${"%.1f".format(offByMore * 100.0 / totalExpected)}%)")
        Log.i(TAG, "Missed:          $missed (${"%.1f".format(missed * 100.0 / totalExpected)}%)")
        Log.i(TAG, "")
        Log.i(TAG, "Per-file summary:")
        for (r in results) {
            val c = r.results.count { it.diff == 0 }
            val pct = c * 100.0 / r.results.size
            Log.i(TAG, "  ${r.name}: $c/${r.results.size} correct (${"%.1f".format(pct)}%)")
        }
        Log.i(TAG, "")
        Log.i(TAG, "Per-octave accuracy (combined):")
        val octaveStats = allResults.groupBy { it.expectedMidiNote / 12 - 1 }
        for ((oct, notes) in octaveStats.entries.sortedBy { it.key }) {
            val ok = notes.count { it.diff == 0 }
            val pct = ok * 100.0 / notes.size
            val label = "C$oct (${notes.minOf { it.expectedMidiNote }}-${notes.maxOf { it.expectedMidiNote }})"
            Log.i(TAG, "  $label: $ok/${notes.size} correct (${"%.1f".format(pct)}%)")
        }
        Log.i(TAG, "========================================")
        Log.i(TAG, "")

        assertTrue("$groupName: expected at least some correct detections",
            correct > 0)
    }

    private fun wavBytesToFloats(data: ByteArray): FloatArray {
        var p = 0

        require(data[p++] == 'R'.code.toByte() && data[p++] == 'I'.code.toByte() &&
                data[p++] == 'F'.code.toByte() && data[p++] == 'F'.code.toByte()) { "Not a RIFF file" }
        p += 4
        require(data[p++] == 'W'.code.toByte() && data[p++] == 'A'.code.toByte() &&
                data[p++] == 'V'.code.toByte() && data[p++] == 'E'.code.toByte()) { "Not a WAVE file" }

        var numChannels = 0
        var bitsPerSample = 0

        while (p + 8 <= data.size) {
            val chunkId = data.sliceArray(p until p + 4).decodeToString()
            val chunkSize = readLeInt(data, p + 4)
            p += 8

            when (chunkId) {
                "fmt " -> {
                    require(readLeShort(data, p) == 1) { "Only PCM supported" }
                    numChannels = readLeShort(data, p + 2)
                    bitsPerSample = readLeShort(data, p + 14)
                    p += chunkSize
                }
                "data" -> {
                    val bps = bitsPerSample / 8
                    val ch = numChannels
                    val out = FloatArray(chunkSize / bps / ch)
                    var idx = 0
                    val end = p + chunkSize
                    while (p + bps * ch <= end && idx < out.size) {
                        var sum = 0L
                        for (c in 0 until ch) {
                            sum += when (bps) {
                                1 -> ((data[p].toInt() and 0xFF) - 128).toLong()
                                2 -> {
                                    val lo = data[p].toInt() and 0xFF
                                    val hi = data[p + 1].toInt() and 0xFF
                                    (lo or (hi shl 8)).toShort().toLong()
                                }
                                3 -> {
                                    val b0 = data[p].toInt() and 0xFF
                                    val b1 = data[p + 1].toInt() and 0xFF
                                    val b2 = data[p + 2].toInt() and 0xFF
                                    if (b2 and 0x80 != 0) (b0 or (b1 shl 8) or (b2 shl 24) or -0x1000000).toLong()
                                    else (b0 or (b1 shl 8) or (b2 shl 16)).toLong()
                                }
                                else -> 0L
                            }
                            p += bps
                        }
                        out[idx++] = (sum.toDouble() / (ch * 32768.0)).toFloat()
                    }
                    return out
                }
                else -> p += chunkSize
            }
        }
        error("No data chunk found in WAV file")
    }

    private fun readLeShort(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun processAudio(audio: FloatArray): List<Detection> {
        val detections = mutableListOf<Detection>()
        var lastFreqHint = 0f
        var lastPrimaryMidi = -1

        for (start in 0..(audio.size - ANALYSIS_WINDOW_SIZE) step HOP_SIZE) {
            val window = audio.sliceArray(start until start + ANALYSIS_WINDOW_SIZE)
            val time = start.toDouble() / 16000.0

            val rmsDb = linearToDb(rms(window))
            if (rmsDb < SILENCE_DB) {
                lastFreqHint = 0f
                lastPrimaryMidi = -1
                continue
            }

            val (primary, detected) = detectPitchWithHint(window, lastFreqHint)
            if (detected.frequency > 0f) {
                val primaryMidi = noteUtils.frequencyToNote(primary).midiNumber
                lastFreqHint = if (lastPrimaryMidi > 0 && abs(primaryMidi - lastPrimaryMidi) > 1) {
                    0f
                } else {
                    primary
                }
                lastPrimaryMidi = primaryMidi

                val info = noteUtils.frequencyToNote(detected.frequency)
                if (info.midiNumber > 0) {
                    detections.add(Detection(time, detected.frequency, detected.confidence,
                        info.midiNumber, "${info.name}$info.octave"))
                }
            }
        }
        return detections
    }

    private fun detectPitchWithHint(buffer: FloatArray, hintFreq: Float): Pair<Float, PitchDetectionResult> {
        normalise(buffer)

        val buf = ByteBuffer.allocateDirect(CREPE_INPUT_SAMPLES * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        for (s in buffer) buf.putFloat(s)
        buf.rewind()

        val output = Array(1) { FloatArray(N_BINS) }
        interpreter.run(buf, output)
        val act = output[0]

        val peak = act.indices.maxByOrNull { act[it] } ?: 0
        val conf = act[peak]
        if (conf < CONFIDENCE_THRESHOLD) return 0f to PitchDetectionResult.NONE

        val primary = localWeightedFreq(act, peak)
        val freq = if (hintFreq > 0f) disambiguateOctave(act, primary, hintFreq) else primary
        val result = if (freq <= 0f) PitchDetectionResult.NONE else PitchDetectionResult(freq, conf)
        return primary to result
    }

    private fun detectPitch(buffer: FloatArray): PitchDetectionResult {
        return detectPitchWithHint(buffer, 0f).second
    }

    private fun disambiguateOctave(activation: FloatArray, primaryFreq: Float, hintFreq: Float): Float {
        val peakBin = activation.indices.maxByOrNull { activation[it] } ?: return primaryFreq
        val candidates = mutableListOf(primaryFreq)
        val threshold = activation[peakBin] * 0.5f
        var localPeak = -1
        var localMax = 0f
        for (i in activation.indices) {
            val v = activation[i]
            if (v >= threshold && abs(i - peakBin) > 3) {
                if (v > localMax) { localMax = v; localPeak = i }
            } else if (localPeak >= 0 && (i - localPeak > 5 || i == activation.size - 1)) {
                candidates.add(localWeightedFreq(activation, localPeak))
                localPeak = -1; localMax = 0f
            }
        }
        if (candidates.size == 1) return primaryFreq
        return candidates.minByOrNull { f ->
            if (f <= 0f) Float.MAX_VALUE else abs(ln(f.toDouble() / hintFreq)).toFloat()
        } ?: primaryFreq
    }

    private fun normalise(buf: FloatArray) {
        var mean = 0.0
        for (s in buf) mean += s.toDouble()
        mean /= buf.size
        var var_ = 0.0
        for (s in buf) { val d = s.toDouble() - mean; var_ += d * d }
        var_ /= buf.size
        val inv = if (var_ > 1e-10) (1.0 / sqrt(var_)).toFloat() else 1f
        for (i in buf.indices) buf[i] = ((buf[i] - mean) * inv).toFloat()
    }

    private fun localWeightedFreq(act: FloatArray, center: Int): Float {
        val start = maxOf(0, center - 4)
        val end = minOf(N_BINS, center + 5)
        var ws = 0.0
        var tw = 0.0
        for (i in start until end) {
            val w = act[i].toDouble()
            ws += BIN_FREQS[i] * w
            tw += w
        }
        return if (tw < 1e-10) BIN_FREQS[center] else (ws / tw).toFloat()
    }

    private fun rms(buf: FloatArray): Float {
        if (buf.isEmpty()) return 0f
        var sum = 0f
        for (s in buf) sum += s * s
        return sqrt(sum / buf.size)
    }

    private fun linearToDb(amp: Float): Float =
        if (amp <= 0f) -100f else 20f * log10(amp)

    private fun groupDetections(detections: List<Detection>): List<NoteDetection> {
        if (detections.isEmpty()) return emptyList()
        val notes = mutableListOf<NoteDetection>()
        var current = detections.first().midiNote
        var start = detections.first().timeSec
        var count = 0
        var confSum = 0f
        for (d in detections) {
            if (d.midiNote == current) {
                count++; confSum += d.confidence
            } else {
                notes.add(NoteDetection(current, start, count, confSum / count))
                current = d.midiNote; start = d.timeSec; count = 1; confSum = d.confidence
            }
        }
        notes.add(NoteDetection(current, start, count, confSum / count))
        return notes
    }

    private fun evaluateAccuracy(
        expectedNotes: List<com.perfectpitch.midi.NoteEvent>,
        track: com.perfectpitch.midi.MelodyTrack,
        detections: List<Detection>
    ): List<NoteAccuracy> {
        val results = mutableListOf<NoteAccuracy>()
        for (note in expectedNotes) {
            val startSec = track.noteStartSeconds(note)
            val endSec = startSec + track.noteDurationSeconds(note)
            val expectedMidi = note.noteNumber
            val expectedName = noteUtils.midiToNoteName(expectedMidi)

            val within = detections.filter { it.timeSec in startSec..endSec }
            val groupedByNote = within.groupBy { it.midiNote }
            val best = groupedByNote.maxByOrNull { it.value.size }

            val (detectedNote, count) = if (best != null) best.key to best.value.size
                else -1 to 0
            val avgConf = if (best != null)
                best.value.sumOf { it.confidence.toDouble() }.toFloat() / best.value.size
            else 0f

            results.add(NoteAccuracy(
                expectedMidiNote = expectedMidi,
                expectedNoteName = expectedName,
                detectedMidiNote = detectedNote,
                diff = if (detectedNote > 0) detectedNote - expectedMidi else Int.MAX_VALUE,
                avgConfidence = avgConf,
                detectionCount = count
            ))
        }
        return results
    }

    private fun printFileReport(
        name: String, results: List<NoteAccuracy>,
        totalExpectedNotes: Int, totalDetections: Int,
        groupedNoteCount: Int, durationSec: Double
    ) {
        val correct = results.count { it.diff == 0 }
        val offBy1 = results.count { it.diff == 1 || it.diff == -1 }
        val offByMore = results.count { abs(it.diff) > 1 && it.diff != Int.MAX_VALUE }
        val missed = results.count { it.diff == Int.MAX_VALUE }

        val octaveStats = results.groupBy { it.expectedMidiNote / 12 - 1 }
        val avgConf = if (results.isNotEmpty())
            results.filter { it.detectionCount > 0 }.sumOf { it.avgConfidence.toDouble() } /
                results.count { it.detectionCount > 0 }
        else 0.0

        Log.i(TAG, "")
        Log.i(TAG, "--- File: $name ---")
        Log.i(TAG, "Duration: ${"%.1f".format(durationSec)}s | Expected: $totalExpectedNotes | Detections: $totalDetections | Groups: $groupedNoteCount | Avg conf: ${"%.3f".format(avgConf)}")
        Log.i(TAG, "Correct: $correct (${"%.1f".format(correct * 100.0 / totalExpectedNotes)}%) | Off±1: $offBy1 | Off>1: $offByMore | Missed: $missed")
        for ((oct, notes) in octaveStats.entries.sortedBy { it.key }) {
            val ok = notes.count { it.diff == 0 }
            val pct = ok * 100.0 / notes.size
            Log.i(TAG, "  C$oct: $ok/${notes.size} (${"%.1f".format(pct)}%)")
        }
        Log.i(TAG, "------------------")
        Log.i(TAG, "")
    }

    data class FileResult(
        val name: String,
        val results: List<NoteAccuracy>,
        val totalDetections: Int,
        val groupedNoteCount: Int,
        val durationSec: Double
    )

    data class Detection(
        val timeSec: Double,
        val freq: Float,
        val confidence: Float,
        val midiNote: Int,
        val noteName: String
    )

    data class NoteDetection(
        val midiNote: Int,
        val startTimeSec: Double,
        val detectionCount: Int,
        val avgConfidence: Float
    )

    data class NoteAccuracy(
        val expectedMidiNote: Int,
        val expectedNoteName: String,
        val detectedMidiNote: Int,
        val diff: Int,
        val avgConfidence: Float,
        val detectionCount: Int
    )
}
