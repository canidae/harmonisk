package com.perfectpitch.midi

import com.perfectpitch.ui.freeplay.PlayedNote
import java.io.ByteArrayOutputStream

class MidiWriter {

    companion object {
        private const val TICKS_PER_QUARTER = 480
    }

    fun write(
        notes: List<PlayedNote>,
        bpm: Float,
        beatsPerBar: Int
    ): ByteArray {
        val bpmSafe = bpm.coerceAtLeast(1f)
        val usPerBeat = (60_000_000.0 / bpmSafe).toLong()

        fun msToTick(ms: Long): Long =
            (ms.toDouble() * bpmSafe * TICKS_PER_QUARTER / 60_000.0).toLong()

        val voiceGroups = notes
            .filter { it.endTimeMs != null }
            .groupBy { it.voiceIndex }
            .toSortedMap()

        val tracks = mutableListOf<ByteArray>()

        // Track 0: tempo + time signature meta
        val track0 = ByteArrayOutputStream()
        track0.write(byteArrayOf(0x00, 0xFF.toByte(), 0x51, 0x03))
        track0.write(byteArrayOf(
            ((usPerBeat shr 16) and 0xFF).toByte(),
            ((usPerBeat shr 8) and 0xFF).toByte(),
            (usPerBeat and 0xFF).toByte()
        ))
        track0.write(byteArrayOf(
            0x00, 0xFF.toByte(), 0x58, 0x04,
            beatsPerBar.coerceIn(1, 127).toByte(),
            0x02.toByte(), // denominator = 2^2 = 4
            0x18, 0x08
        ))
        track0.write(byteArrayOf(0x00, 0xFF.toByte(), 0x2F, 0x00))
        tracks.add(track0.toByteArray())

        // One track per voice
        for ((_, voiceNotes) in voiceGroups) {
            data class MidiEvt(val tick: Long, val isNoteOn: Boolean, val noteNum: Int, val velocity: Int)

            val events = mutableListOf<MidiEvt>()
            for (note in voiceNotes) {
                val startTick = msToTick(note.startTimeMs)
                val endTick = msToTick(note.endTimeMs!!)
                val noteNum = note.midiNumber.coerceIn(0, 127)
                events.add(MidiEvt(startTick, true, noteNum, 80))
                events.add(MidiEvt(endTick, false, noteNum, 0))
            }
            events.sortWith(compareBy({ it.tick }, { if (it.isNoteOn) 1 else 0 }))

            val trackData = ByteArrayOutputStream()
            var currentTick = 0L
            for (evt in events) {
                val delta = (evt.tick - currentTick).coerceAtLeast(0L)
                currentTick = evt.tick
                trackData.write(writeVarLen(delta))
                val statusByte = if (evt.isNoteOn) 0x90 else 0x80
                trackData.write(byteArrayOf(
                    statusByte.toByte(),
                    evt.noteNum.toByte(),
                    evt.velocity.toByte()
                ))
            }
            trackData.write(byteArrayOf(0x00, 0xFF.toByte(), 0x2F, 0x00))
            tracks.add(trackData.toByteArray())
        }

        val output = ByteArrayOutputStream()
        output.write("MThd".toByteArray(Charsets.US_ASCII))
        output.write(writeInt32(6))
        output.write(writeInt16(1)) // Format 1
        output.write(writeInt16(tracks.size))
        output.write(writeInt16(TICKS_PER_QUARTER))
        for (track in tracks) {
            output.write("MTrk".toByteArray(Charsets.US_ASCII))
            output.write(writeInt32(track.size))
            output.write(track)
        }

        return output.toByteArray()
    }

    private fun writeVarLen(value: Long): ByteArray {
        if (value <= 0) return byteArrayOf(0x00)
        val result = mutableListOf<Byte>()
        var v = value
        result.add(0, (v and 0x7F).toByte())
        v = v shr 7
        while (v > 0) {
            result.add(0, ((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        return result.toByteArray()
    }

    private fun writeInt32(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    private fun writeInt16(value: Int): ByteArray = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}
