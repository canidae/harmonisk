package net.exent.harmonisk.midi

import android.media.midi.MidiReceiver
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest

class MidiInputReceiver(
    private val midiDeviceManager: MidiDeviceManager
) {

    fun midiEventFlow(): Flow<MidiNoteEvent> = channelFlow {
        midiDeviceManager.selectedInputDevice.collectLatest { deviceInfo ->
            if (deviceInfo == null) return@collectLatest

            val device = midiDeviceManager.openDevice(deviceInfo) ?: return@collectLatest
            val port = device.openOutputPort(0)
            if (port == null) {
                device.close()
                return@collectLatest
            }

            val receiver = object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    if (count >= 3) {
                        parseMidiMessage(msg, offset)?.let { trySend(it) }
                    }
                }
            }
            port.connect(receiver)

            try {
                awaitCancellation()
            } finally {
                port.close()
                device.close()
            }
        }
    }

    private fun parseMidiMessage(msg: ByteArray, offset: Int): MidiNoteEvent? {
        val status = msg[offset].toInt() and 0xFF
        val type = status and 0xF0
        val note = msg[offset + 1].toInt() and 0xFF
        val velocity = msg[offset + 2].toInt() and 0xFF

        return when {
            type == 0x90 && velocity > 0 -> MidiNoteEvent.NoteOn(note)
            type == 0x80 || (type == 0x90 && velocity == 0) -> MidiNoteEvent.NoteOff(note)
            else -> null
        }
    }
}
