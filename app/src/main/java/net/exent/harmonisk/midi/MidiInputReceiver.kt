package net.exent.harmonisk.midi

import android.content.Context
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Receives MIDI note-on/off events from the connected device's output port.
 *
 * Call [midiEventFlow] to get a [Flow] that automatically reconnects when the
 * selected device changes (via [MidiDeviceManager.selectedInputDevice]).
 */
class MidiInputReceiver(
    private val context: Context,
    private val midiDeviceManager: MidiDeviceManager
) {
    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    /**
     * A cold flow of [MidiNoteEvent]s from the currently connected MIDI input device.
     * Reconnects automatically when the device changes. Emits nothing when no device is connected.
     */
    fun midiEventFlow(): Flow<MidiNoteEvent> = channelFlow {
        midiDeviceManager.selectedInputDevice.collectLatest { deviceInfo ->
            if (deviceInfo == null) return@collectLatest

            val device = openDeviceAsync(deviceInfo) ?: return@collectLatest
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

    private suspend fun openDeviceAsync(
        deviceInfo: android.media.midi.MidiDeviceInfo
    ): android.media.midi.MidiDevice? = suspendCancellableCoroutine { cont ->
        if (midiManager == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        midiManager.openDevice(deviceInfo, { device ->
            if (cont.isActive) cont.resume(device)
        }, Handler(Looper.getMainLooper()))
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
