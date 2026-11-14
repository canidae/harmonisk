package net.exent.harmonisk.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private companion object {
        private const val TAG = "PlaybackRecorder"
        private const val SAMPLE_RATE = AudioConstants.SAMPLE_RATE
        private const val AAC_BIT_RATE = 96_000
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_DRAIN_ATTEMPTS = 500
        private const val MIN_RECORDING_MS = 1_000L
    }

    private val directory = File(context.filesDir, "recordings")
    private val audioFile = File(directory, "last.m4a")
    private val legacyAudioFile = File(directory, "last.wav")
    private val metaFile = File(directory, "last.json")
    private val takeAudioFile = File(directory, "take.m4a")
    private val legacyTakeAudioFile = File(directory, "take.wav")
    private val takeMetaFile = File(directory, "take.json")
    private val lock = Any()

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var pcmBuffer = ByteBuffer
        .allocate(AudioConstants.BUFFER_SIZE_SAMPLES * 2)
        .order(ByteOrder.LITTLE_ENDIAN)

    @Volatile
    private var totalSamples = 0L
    private var recordingMelodyId: String? = null
    private var recordingLevel = 1
    private var recordingFailed = false

    @Volatile
    var lastTake: RecordedTake? = readTake()
        private set

    private var player: MediaPlayer? = null

    private val sink: (FloatArray) -> Unit = { samples -> writeSamples(samples) }

    fun startRecording(melodyId: String, level: Int) {
        discardPending()
        stopPlayback()
        directory.mkdirs()
        recordingMelodyId = melodyId
        recordingLevel = level
        recordingFailed = false
        totalSamples = 0L
        synchronized(lock) {
            takeAudioFile.delete()
            legacyTakeAudioFile.delete()
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    SAMPLE_RATE,
                    1
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                    setInteger(
                        MediaFormat.KEY_MAX_INPUT_SIZE,
                        AudioConstants.BUFFER_SIZE_SAMPLES * 2
                    )
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                encoder = codec
                muxer = MediaMuxer(
                    takeAudioFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxerStarted = false
                trackIndex = -1
            } catch (e: Exception) {
                Log.e(TAG, "Could not start AAC encoder", e)
                runCatching { codec?.release() }
                releaseEncoder()
                recordingMelodyId = null
                return
            }
        }
        capture.addSampleListener(sink)
    }

    fun isRecording(): Boolean = synchronized(lock) { encoder != null }

    fun elapsedMs(): Long = totalSamples * 1000L / SAMPLE_RATE

    fun stopRecording(
        save: Boolean,
        timeline: List<TakeTimelinePoint>,
        noteOffsetsMs: Map<Int, Long>
    ): RecordedTake? {
        capture.removeSampleListener(sink)
        val melodyId = recordingMelodyId
        val level = recordingLevel
        recordingMelodyId = null
        val encoded = synchronized(lock) {
            if (encoder != null) {
                runCatching { drainEncoder(true) }
                    .onFailure {
                        recordingFailed = true
                        Log.e(TAG, "Could not finish AAC encoder", it)
                    }
                val started = muxerStarted
                releaseEncoder()
                started
            } else {
                false
            }
        }
        val samples = totalSamples
        val durationMs = samples * 1000L / SAMPLE_RATE
        if (!save || melodyId == null || recordingFailed || !encoded ||
            durationMs < MIN_RECORDING_MS
        ) {
            takeAudioFile.delete()
            takeMetaFile.delete()
            return null
        }
        val take = RecordedTake(
            melodyId = melodyId,
            level = level,
            audioFile = audioFile,
            durationMs = durationMs,
            timeline = timeline,
            noteOffsetsMs = noteOffsetsMs
        )
        runCatching {
            takeMetaFile.writeText(takeToJson(take).toString())
            audioFile.delete()
            metaFile.delete()
            legacyAudioFile.delete()
            check(takeAudioFile.renameTo(audioFile))
            check(takeMetaFile.renameTo(metaFile))
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
        synchronized(lock) {
            releaseEncoder()
            takeAudioFile.delete()
            legacyTakeAudioFile.delete()
            takeMetaFile.delete()
        }
        recordingMelodyId = null
        recordingFailed = false
        totalSamples = 0L
    }

    private fun writeSamples(samples: FloatArray) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            val codec = encoder ?: return
            try {
                val bytes = samples.size * 2
                if (pcmBuffer.capacity() < bytes) {
                    pcmBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
                }
                pcmBuffer.clear()
                for (sample in samples) {
                    pcmBuffer.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                }
                pcmBuffer.flip()
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                    if (input != null) {
                        input.clear()
                        input.put(pcmBuffer)
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            pcmBuffer.limit(),
                            totalSamples * 1_000_000L / SAMPLE_RATE,
                            0
                        )
                        totalSamples += samples.size
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
                drainEncoder(false)
            } catch (e: Exception) {
                Log.e(TAG, "AAC encoding failed", e)
                recordingFailed = true
                releaseEncoder()
                recordingMelodyId = null
            }
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        if (endOfStream && !queueEndOfStream(codec)) {
            recordingFailed = true
            return
        }
        val info = MediaCodec.BufferInfo()
        var attempts = 0
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(
                info,
                if (endOfStream) CODEC_TIMEOUT_US else 0L
            )
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++attempts > MAX_DRAIN_ATTEMPTS) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        val target = muxer ?: return
                        trackIndex = target.addTrack(codec.outputFormat)
                        target.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer?.writeSampleData(trackIndex, buffer, info)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun queueEndOfStream(codec: MediaCodec): Boolean {
        repeat(MAX_DRAIN_ATTEMPTS) {
            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    totalSamples * 1_000_000L / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return true
            }
        }
        return false
    }

    private fun releaseEncoder() {
        encoder?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        encoder = null
        muxer?.let { target ->
            if (muxerStarted) runCatching { target.stop() }
            runCatching { target.release() }
        }
        muxer = null
        muxerStarted = false
        trackIndex = -1
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
        if (!metaFile.exists()) return null
        val file = when {
            audioFile.exists() -> audioFile
            legacyAudioFile.exists() -> legacyAudioFile
            else -> return null
        }
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
                audioFile = file,
                durationMs = json.getLong("durationMs"),
                timeline = timeline,
                noteOffsetsMs = offsets
            )
        }.getOrNull()
    }
}
