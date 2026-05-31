package com.perfectpitch.midi

import android.content.Context
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import com.perfectpitch.audio.NotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Sends MIDI note-on/off messages to the connected device's input port.
 * Implements [NotePlayer] so it can substitute for the internal synthesizer.
 */
class MidiOutputSender(
    private val context: Context,
    private val midiDeviceManager: MidiDeviceManager
) : NotePlayer {

    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

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

                val device = openDeviceAsync(deviceInfo) ?: return@collectLatest
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
        val buf = ByteArray(3)
        buf[0] = (0x90 or (channel and 0x0F)).toByte()
        buf[1] = (note and 0x7F).toByte()
        buf[2] = (velocity and 0x7F).toByte()
        try { port.send(buf, 0, 3) } catch (_: Exception) {}
    }

    override fun noteOff(channel: Int, note: Int) = withPort { port ->
        val buf = ByteArray(3)
        buf[0] = (0x80 or (channel and 0x0F)).toByte()
        buf[1] = (note and 0x7F).toByte()
        buf[2] = 0
        try { port.send(buf, 0, 3) } catch (_: Exception) {}
    }

    override fun allNotesOff() = withPort { port ->
        val buf = ByteArray(3)
        for (ch in 0..15) {
            buf[0] = (0xB0 or ch).toByte()
            buf[1] = 123  // All Notes Off (CC 123)
            buf[2] = 0
            try { port.send(buf, 0, 3) } catch (_: Exception) {}
        }
    }

    private inline fun withPort(block: (MidiInputPort) -> Unit) {
        val port = outputPort ?: return
        block(port)
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
}
