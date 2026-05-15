package com.perfectpitch.audio

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Build
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Wraps Android's built-in MIDI synthesizer to produce piano-like sounds.
 *
 * Uses [MidiManager] to locate the built-in virtual MIDI synthesizer,
 * opens it, and sends Note-On / Note-Off messages through its input port.
 * Channel 0 maps to General MIDI Acoustic Grand Piano by default.
 *
 * Usage:
 * ```
 * val synth = MelodySynthesizer()
 * synth.open(context)          // suspends until the device is ready
 * synth.noteOn(channel = 0, note = 60, velocity = 100)
 * delay(500)
 * synth.noteOff(channel = 0, note = 60)
 * synth.close()
 * ```
 */
class MelodySynthesizer {

    private var midiDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null

    /** `true` when the synthesizer device and input port are open. */
    val isReady: Boolean
        get() = midiDevice != null && inputPort != null

    /**
     * Locate the built-in MIDI synthesizer and open its first input port.
     *
     * @param context Any Android [Context] (used to obtain [MidiManager]).
     * @throws IllegalStateException if no synthesizer device is found.
     * @throws IllegalStateException if the device has no input ports.
     * @throws RuntimeException      if the device fails to open.
     */
    suspend fun open(context: Context) {
        if (isReady) return

        val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
            ?: throw IllegalStateException("MidiManager service not available")

        // The built-in synthesizer is a TYPE_VIRTUAL device that accepts
        // MIDI input (i.e. has at least one input port) to produce audio.
        val devices: List<MidiDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            midiManager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        } else {
            @Suppress("DEPRECATION")
            midiManager.devices.toList()
        }

        val synthInfo = devices.firstOrNull { info ->
            info.type == MidiDeviceInfo.TYPE_VIRTUAL && info.inputPortCount > 0
        } ?: throw IllegalStateException(
            "No built-in MIDI synthesizer found on this device"
        )

        val device = suspendCoroutine { continuation ->
            midiManager.openDevice(
                synthInfo,
                { device ->
                    if (device != null) {
                        continuation.resume(device)
                    } else {
                        continuation.resumeWithException(
                            RuntimeException("Failed to open MIDI synthesizer device")
                        )
                    }
                },
                null // deliver callback on the default handler thread
            )
        }

        val port = device.openInputPort(0)
        if (port == null) {
            device.close()
            throw IllegalStateException("Failed to open input port 0 on MIDI synthesizer")
        }

        midiDevice = device
        inputPort = port
        Log.d(TAG, "MIDI synthesizer opened successfully")
    }

    /**
     * Send a MIDI Note-On message.
     *
     * @param channel  MIDI channel (0–15). Default 0 = General MIDI piano.
     * @param note     MIDI note number (0–127). Middle C = 60.
     * @param velocity Key velocity (0–127). 0 is equivalent to note-off.
     */
    fun noteOn(channel: Int, note: Int, velocity: Int) {
        requireReady()
        val msg = byteArrayOf(
            (0x90 or (channel and 0x0F)).toByte(),
            (note and 0x7F).toByte(),
            (velocity and 0x7F).toByte()
        )
        sendMessage(msg)
    }

    /**
     * Send a MIDI Note-Off message.
     *
     * @param channel MIDI channel (0–15).
     * @param note    MIDI note number (0–127).
     */
    fun noteOff(channel: Int, note: Int) {
        requireReady()
        val msg = byteArrayOf(
            (0x80 or (channel and 0x0F)).toByte(),
            (note and 0x7F).toByte(),
            0.toByte()
        )
        sendMessage(msg)
    }

    /**
     * Send Note-Off for every MIDI note (0–127) on the given [channel].
     * Useful as a panic/reset to silence all ringing notes.
     */
    fun allNotesOff(channel: Int = 0) {
        requireReady()
        for (note in 0..127) {
            noteOff(channel, note)
        }
    }

    /**
     * Close the MIDI input port and device, releasing all resources.
     * Safe to call even if not currently open.
     */
    fun close() {
        try {
            inputPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing MIDI input port", e)
        } finally {
            inputPort = null
        }

        try {
            midiDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing MIDI device", e)
        } finally {
            midiDevice = null
        }

        Log.d(TAG, "MIDI synthesizer closed")
    }

    // ── Private helpers ────────────────────────────────────────────────

    private fun requireReady() {
        check(isReady) { "MelodySynthesizer is not open. Call open() first." }
    }

    private fun sendMessage(msg: ByteArray) {
        try {
            inputPort?.send(msg, 0, msg.size, System.nanoTime())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send MIDI message", e)
        }
    }

    private companion object {
        const val TAG = "MelodySynthesizer"
    }
}
