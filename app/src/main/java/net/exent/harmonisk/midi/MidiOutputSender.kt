package net.exent.harmonisk.midi

import android.media.midi.MidiInputPort
import net.exent.harmonisk.audio.NotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Sends MIDI note-on/off messages to the connected device's input port.
 * Implements [NotePlayer] so it can substitute for the internal synthesizer.
 */
class MidiOutputSender(
    private val midiDeviceManager: MidiDeviceManager
) : NotePlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var outputPort: MidiInputPort? = null

    override val isReady: Boolean get() = outputPort != null

    init {
        scope.launch {
            midiDeviceManager.selectedOutputDevice.collectLatest { deviceInfo ->
                val old = outputPort
                outputPort = null
                old?.close()

                if (deviceInfo == null) return@collectLatest

                val device = midiDeviceManager.openDevice(deviceInfo) ?: return@collectLatest
                val port = device.openInputPort(0)
                if (port == null) {
                    device.close()
                    return@collectLatest
                }
                outputPort = port

                try {
                    awaitCancellation()
                } finally {
                    outputPort = null
                    port.close()
                    device.close()
                }
            }
        }
    }

    override fun noteOn(channel: Int, note: Int, velocity: Int) = withPort { port ->
        send(port, 0x90 or (channel and 0x0F), note and 0x7F, velocity and 0x7F)
    }

    override fun noteOff(channel: Int, note: Int) = withPort { port ->
        send(port, 0x80 or (channel and 0x0F), note and 0x7F, 0)
    }

    override fun allNotesOff() = withPort { port ->
        for (ch in 0..15) send(port, 0xB0 or ch, 123, 0)
    }

    private fun send(port: MidiInputPort, vararg bytes: Int) {
        val buf = ByteArray(bytes.size) { bytes[it].toByte() }
        try {
            port.send(buf, 0, buf.size)
        } catch (_: Exception) {
        }
    }

    private inline fun withPort(block: (MidiInputPort) -> Unit) {
        val port = outputPort ?: return
        block(port)
    }
}
