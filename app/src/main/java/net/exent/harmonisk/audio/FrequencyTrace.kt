package net.exent.harmonisk.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class FrequencyTraceFrame(
    val timeMs: Long,
    val frequencyHz: Float,
    val confidence: Float
)

class WavFileWriter(private val file: File) {
    companion object {
        private const val HEADER_SIZE = 44
    }

    private val lock = Any()
    private var output: BufferedOutputStream? = null
    private var dataBytes = 0L
    private var failed = false

    fun start(): Boolean = synchronized(lock) {
        file.parentFile?.mkdirs()
        runCatching {
            output = BufferedOutputStream(FileOutputStream(file))
            writeHeader()
        }.onFailure {
            failed = true
            close()
            file.delete()
        }.isSuccess
    }

    fun write(samples: FloatArray) {
        synchronized(lock) {
            val target = output ?: return
            if (failed) return
            runCatching {
                val bytes = ByteArray(samples.size * 2)
                var index = 0
                for (sample in samples) {
                    val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    bytes[index++] = (value and 0xFF).toByte()
                    bytes[index++] = ((value shr 8) and 0xFF).toByte()
                }
                target.write(bytes)
                dataBytes += bytes.size
            }.onFailure { failed = true }
        }
    }

    fun finish(): File? {
        synchronized(lock) {
            close()
            if (failed || !file.exists()) return null
            return runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(4)
                    raf.writeIntLe((HEADER_SIZE - 8 + dataBytes).toInt())
                    raf.seek(40)
                    raf.writeIntLe(dataBytes.toInt())
                }
                file
            }.getOrElse {
                file.delete()
                null
            }
        }
    }

    fun abort() {
        synchronized(lock) {
            close()
            file.delete()
        }
    }

    private fun close() {
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null
    }

    private fun writeHeader() {
        val target = output ?: return
        target.write("RIFF".toByteArray())
        writeIntLe(target, HEADER_SIZE - 8)
        target.write("WAVE".toByteArray())
        target.write("fmt ".toByteArray())
        writeIntLe(target, 16)
        writeShortLe(target, 1)
        writeShortLe(target, 1)
        writeIntLe(target, AudioConstants.SAMPLE_RATE)
        writeIntLe(target, AudioConstants.SAMPLE_RATE * 2)
        writeShortLe(target, 2)
        writeShortLe(target, 16)
        target.write("data".toByteArray())
        writeIntLe(target, 0)
    }
}

object FrequencyTraceExporter {
    suspend fun export(
        context: Context,
        wav: File,
        frames: List<FrequencyTraceFrame>
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, "frequency_visualizer").apply { mkdirs() }
            val csv = File(directory, "pitch.csv")
            csv.bufferedWriter().use { writer ->
                writer.appendLine("time_ms,frequency_hz,confidence")
                for (frame in frames) {
                    writer.append(frame.timeMs.toString()).append(',')
                    writer.append(String.format(Locale.US, "%.3f", frame.frequencyHz)).append(',')
                    writer.append(String.format(Locale.US, "%.4f", frame.confidence)).append('\n')
                }
            }
            val zip = File(directory, "frequency-trace.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { output ->
                output.putNextEntry(ZipEntry("recording.wav"))
                wav.inputStream().use { it.copyTo(output) }
                output.closeEntry()
                output.putNextEntry(ZipEntry("pitch.csv"))
                csv.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
            zip
        }.getOrNull()
    }
}

private fun writeIntLe(output: OutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value shr 8) and 0xFF)
    output.write((value shr 16) and 0xFF)
    output.write((value shr 24) and 0xFF)
}

private fun writeShortLe(output: OutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value shr 8) and 0xFF)
}

private fun RandomAccessFile.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}
