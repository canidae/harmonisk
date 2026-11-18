package net.exent.harmonisk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @Test
    fun trimsSamples() {
        val source = File.createTempFile("frequency-trace", ".wav")
        val target = File.createTempFile("frequency-trimmed", ".wav")
        try {
            val writer = WavFileWriter(source)
            assertTrue(writer.start())
            writer.write(floatArrayOf(0f, 0.25f, 0.5f, -1f))
            assertNotNull(writer.finish())

            assertNotNull(WavFileTrimmer.trim(source, target, 1, 3))

            val bytes = target.readBytes()
            assertEquals(44 + 4, bytes.size)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(4, buffer.getInt(40))
            assertEquals(8191, buffer.getShort(44).toInt())
            assertEquals(16383, buffer.getShort(46).toInt())
        } finally {
            source.delete()
            target.delete()
        }
    }

    @Test
    fun writesHeaderAndSamples() {
        val file = File.createTempFile("frequency-trace", ".wav")
        try {
            val writer = WavFileWriter(file)
            assertTrue(writer.start())
            writer.write(floatArrayOf(0f, 0.5f, -1f))
            assertNotNull(writer.finish())

            val bytes = file.readBytes()
            assertEquals(44 + 6, bytes.size)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals(bytes.size - 8, buffer.getInt(4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(1, buffer.getShort(20).toInt())
            assertEquals(AudioConstants.SAMPLE_RATE, buffer.getInt(24))
            assertEquals(2, buffer.getShort(32).toInt())
            assertEquals(16, buffer.getShort(34).toInt())
            assertEquals("data", String(bytes, 36, 4))
            assertEquals(6, buffer.getInt(40))
            assertEquals(0, buffer.getShort(44).toInt())
            assertEquals(16383, buffer.getShort(46).toInt())
            assertEquals(-32767, buffer.getShort(48).toInt())
        } finally {
            file.delete()
        }
    }
}
