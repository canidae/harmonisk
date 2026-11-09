package net.exent.harmonisk.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MidiDeviceManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val _devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())

    val devices: StateFlow<List<MidiDeviceInfo>> = _devices.asStateFlow()

    val selectedInputDevice: StateFlow<MidiDeviceInfo?> = _devices
        .map { list -> list.firstOrNull { it.outputPortCount > 0 } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val selectedOutputDevice: StateFlow<MidiDeviceInfo?> = _devices
        .map { list -> list.firstOrNull { it.inputPortCount > 0 } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isMidiInputAvailable: StateFlow<Boolean> = selectedInputDevice
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isMidiOutputAvailable: StateFlow<Boolean> = selectedOutputDevice
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _inputOverride = MutableStateFlow<Boolean?>(null)
    private val _outputOverride = MutableStateFlow<Boolean?>(null)

    val inputOverride: StateFlow<Boolean?> = _inputOverride.asStateFlow()
    val outputOverride: StateFlow<Boolean?> = _outputOverride.asStateFlow()

    val effectiveMidiInputEnabled: StateFlow<Boolean> = combine(
        isMidiInputAvailable, _inputOverride
    ) { available, override -> override ?: available }
        .stateIn(scope, SharingStarted.Eagerly, false)

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
            @Suppress("DEPRECATION")
            _devices.value = manager.devices?.toList() ?: emptyList()
            @Suppress("DEPRECATION")
            manager.registerDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        }
    }

    fun setInputOverride(enabled: Boolean?) {
        _inputOverride.value = enabled
    }

    fun setOutputOverride(enabled: Boolean?) {
        _outputOverride.value = enabled
    }

    suspend fun openDevice(deviceInfo: MidiDeviceInfo): MidiDevice? {
        val manager = midiManager ?: return null
        return suspendCancellableCoroutine { cont ->
            manager.openDevice(deviceInfo, { device ->
                if (cont.isActive) cont.resume(device)
            }, Handler(Looper.getMainLooper()))
        }
    }

}
