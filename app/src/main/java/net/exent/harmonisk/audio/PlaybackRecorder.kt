package net.exent.harmonisk.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TakeTimelinePoint(val audioMs: Long, val gameSeconds: Double)

data class RecordedTake(
    val melodyId: String,
    val level: Int,
    val audioFile: File,
    val durationMs: Long,
    val timeline: List<TakeTimelinePoint>,
    val noteOffsetsMs: Map<Int, Long>
) {
    fun gameTimeAt(audioMs: Long): Double {
        if (timeline.isEmpty()) return 0.0
        val first = timeline.first()
        if (audioMs <= first.audioMs) return first.gameSeconds
        val last = timeline.last()
        if (audioMs >= last.audioMs) return last.gameSeconds
        var low = 0
        var high = timeline.size - 1
        while (low + 1 < high) {
            val mid = (low + high) / 2
            if (timeline[mid].audioMs <= audioMs) low = mid else high = mid
        }
        val a = timeline[low]
        val b = timeline[high]
        val span = (b.audioMs - a.audioMs).coerceAtLeast(1L)
        val fraction = (audioMs - a.audioMs).toDouble() / span
        return a.gameSeconds + (b.gameSeconds - a.gameSeconds) * fraction
    }
}

class PlaybackRecorder(
    context: Context,
    private val capture: AudioCaptureService
) {
    companion object {
        private const val HEADER_SIZE = 44
        private const val BYTES_PER_SAMPLE = 2
    }

    private val directory = File(context.filesDir, "recordings")
    private val audioFile = File(directory, "last.wav")
    private val metaFile = File(directory, "last.json")
    private val takeAudioFile = File(directory, "take.wav")
    private val takeMetaFile = File(directory, "take.json")
    private val lock = Any()

    private var raf: RandomAccessFile? = null

    @Volatile
    private var dataBytes = 0L
    private var recordingMelodyId: String? = null
    private var recordingLevel = 1

    @Volatile
    var lastTake: RecordedTake? = readTake()
        private set

    private var player: MediaPlayer? = null

    private val sink: (FloatArray) -> Unit = { samples -> writeSamples(samples) }

    fun startRecording(melodyId: String, level: Int) {
        discardPending()
        stopPlayback()
        directory.mkdirs()
        dataBytes = 0L
        recordingMelodyId = melodyId
        recordingLevel = level
        val file = RandomAccessFile(takeAudioFile, "rw")
        file.setLength(0)
        file.write(ByteArray(HEADER_SIZE))
        raf = file
        capture.addSampleListener(sink)
    }

    fun isRecording(): Boolean = raf != null

    fun elapsedMs(): Long = (dataBytes / BYTES_PER_SAMPLE) * 1000L / AudioConstants.SAMPLE_RATE

    fun stopRecording(
        save: Boolean,
        timeline: List<TakeTimelinePoint>,
        noteOffsetsMs: Map<Int, Long>
    ): RecordedTake? {
        capture.removeSampleListener(sink)
        val melodyId = recordingMelodyId
        val level = recordingLevel
        val file = raf
        raf = null
        recordingMelodyId = null
        if (file == null) return null
        synchronized(lock) {
            runCatching {
                writeHeader(file, dataBytes)
                file.close()
            }
        }
        if (!save || melodyId == null || dataBytes < AudioConstants.SAMPLE_RATE * BYTES_PER_SAMPLE) {
            takeAudioFile.delete()
            takeMetaFile.delete()
            return null
        }
        val durationMs = elapsedMs()
        val take = RecordedTake(
            melodyId = melodyId,
            level = level,
            audioFile = audioFile,
            durationMs = durationMs,
            timeline = timeline,
            noteOffsetsMs = noteOffsetsMs
        )
        val json = takeToJson(take)
        runCatching {
            takeMetaFile.writeText(json.toString())
            audioFile.delete()
            metaFile.delete()
            if (!takeAudioFile.renameTo(audioFile)) return null
            takeMetaFile.renameTo(metaFile)
        }.onFailure {
            takeAudioFile.delete()
            takeMetaFile.delete()
            lastTake = null
            return null
        }
        lastTake = take
        return take
    }

    fun takeFor(melodyId: String): RecordedTake? = lastTake?.takeIf { it.melodyId == melodyId }

    fun play(take: RecordedTake): Boolean {
        stopPlayback()
        return runCatching {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(take.audioFile.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            true
        }.getOrElse {
            stopPlayback()
            false
        }
    }

    fun playbackPositionMs(): Long = player?.currentPosition?.toLong() ?: 0L

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun stopPlayback() {
        player?.release()
        player = null
    }

    private fun discardPending() {
        capture.removeSampleListener(sink)
        runCatching { raf?.close() }
        raf = null
        recordingMelodyId = null
        takeAudioFile.delete()
        takeMetaFile.delete()
    }

    private fun writeSamples(samples: FloatArray) {
        if (samples.isEmpty()) return
        val bytes = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            bytes.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }
        synchronized(lock) {
            val file = raf ?: return
            runCatching { file.write(bytes.array()) }
            dataBytes += bytes.capacity()
        }
    }

    private fun writeHeader(file: RandomAccessFile, pcmBytes: Long) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = AudioConstants.SAMPLE_RATE * channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt((36 + pcmBytes).toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(AudioConstants.SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(pcmBytes.toInt())
        file.seek(0)
        file.write(buffer.array())
    }

    private fun takeToJson(take: RecordedTake): JSONObject = JSONObject()
        .put("melodyId", take.melodyId)
        .put("level", take.level)
        .put("durationMs", take.durationMs)
        .put(
            "timeline",
            JSONArray().apply {
                take.timeline.forEach {
                    put(it.audioMs)
                    put(it.gameSeconds)
                }
            }
        )
        .put(
            "noteOffsets",
            JSONObject().apply {
                take.noteOffsetsMs.forEach { (index, offset) -> put(index.toString(), offset) }
            }
        )

    private fun readTake(): RecordedTake? {
        if (!audioFile.exists() || !metaFile.exists()) return null
        return runCatching {
            val json = JSONObject(metaFile.readText())
            val timelineValues = json.getJSONArray("timeline")
            val timeline = buildList {
                var i = 0
                while (i + 1 < timelineValues.length()) {
                    add(TakeTimelinePoint(timelineValues.getLong(i), timelineValues.getDouble(i + 1)))
                    i += 2
                }
            }
            val offsetsJson = json.getJSONObject("noteOffsets")
            val offsets = buildMap {
                offsetsJson.keys().forEach { key -> put(key.toInt(), offsetsJson.getLong(key)) }
            }
            RecordedTake(
                melodyId = json.getString("melodyId"),
                level = json.getInt("level"),
                audioFile = audioFile,
                durationMs = json.getLong("durationMs"),
                timeline = timeline,
                noteOffsetsMs = offsets
            )
        }.getOrNull()
    }
}
