package com.perfectpitch.midi

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

/**
 * Tracks connected MIDI devices and exposes enable/disable state for input and output.
 *
 * Auto-behaviour: MIDI is used when a compatible device is connected.
 * Override: null = auto, true = always on, false = always off (mic fallback).
 */
class MidiDeviceManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())

    /** All currently connected MIDI devices. */
    val devices: StateFlow<List<MidiDeviceInfo>> = _devices.asStateFlow()

    /** First connected device that has output ports (app can receive MIDI from it). */
    val selectedInputDevice: StateFlow<MidiDeviceInfo?> = _devices
        .map { list -> list.firstOrNull { it.outputPortCount > 0 } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** First connected device that has input ports (app can send MIDI to it). */
    val selectedOutputDevice: StateFlow<MidiDeviceInfo?> = _devices
        .map { list -> list.firstOrNull { it.inputPortCount > 0 } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isMidiInputAvailable: StateFlow<Boolean> = selectedInputDevice
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isMidiOutputAvailable: StateFlow<Boolean> = selectedOutputDevice
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // Per-feature overrides: null = auto (follow device availability)
    private val _inputOverride = MutableStateFlow<Boolean?>(null)
    private val _outputOverride = MutableStateFlow<Boolean?>(null)

    val inputOverride: StateFlow<Boolean?> = _inputOverride.asStateFlow()
    val outputOverride: StateFlow<Boolean?> = _outputOverride.asStateFlow()

    /** True when MIDI input should be used (auto or forced on). */
    val effectiveMidiInputEnabled: StateFlow<Boolean> = combine(
        isMidiInputAvailable, _inputOverride
    ) { available, override -> override ?: available }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** True when MIDI output should be used (auto or forced on). */
    val effectiveMidiOutputEnabled: StateFlow<Boolean> = combine(
        isMidiOutputAvailable, _outputOverride
    ) { available, override -> override ?: available }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            _devices.value = (_devices.value + device).distinctBy { it.id }
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            _devices.value = _devices.value.filter { it.id != device.id }
        }
    }

    init {
        midiManager?.let { manager ->
            _devices.value = manager.devices?.toList() ?: emptyList()
            manager.registerDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        }
    }

    /**
     * Override MIDI input source. Pass null to restore auto behaviour (use MIDI when available).
     * Pass true to force MIDI even if no device is connected (will effectively be no-op until connected).
     * Pass false to force microphone even when a device is connected.
     */
    fun setInputOverride(enabled: Boolean?) {
        _inputOverride.value = enabled
    }

    /**
     * Override MIDI output. Pass null to restore auto behaviour.
     * Pass false to use internal synthesizer even when a MIDI device is connected.
     */
    fun setOutputOverride(enabled: Boolean?) {
        _outputOverride.value = enabled
    }

    fun close() {
        midiManager?.unregisterDeviceCallback(deviceCallback)
    }
}
