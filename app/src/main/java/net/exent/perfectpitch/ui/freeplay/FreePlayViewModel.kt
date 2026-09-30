package net.exent.perfectpitch.ui.freeplay

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.perfectpitch.audio.SoundFontSynthesizer
import net.exent.perfectpitch.audio.NotePlayer
import net.exent.perfectpitch.audio.PitchProcessor
import net.exent.perfectpitch.data.AudioDelayRepository
import net.exent.perfectpitch.data.SettingsRepository
import net.exent.perfectpitch.midi.MidiDeviceManager
import net.exent.perfectpitch.midi.MidiImporter
import net.exent.perfectpitch.midi.MidiInputReceiver
import net.exent.perfectpitch.midi.MidiNoteEvent
import net.exent.perfectpitch.midi.MidiOutputSender
import net.exent.perfectpitch.midi.MidiWriter
import net.exent.perfectpitch.music.NoteUtils
import net.exent.perfectpitch.music.PitchResult
import net.exent.perfectpitch.R
import net.exent.perfectpitch.ui.MAX_BPM
import net.exent.perfectpitch.ui.MAX_FALL_SPEED
import net.exent.perfectpitch.ui.MAX_MIDI
import net.exent.perfectpitch.ui.MIN_BPM
import net.exent.perfectpitch.ui.MIN_FALL_SPEED
import net.exent.perfectpitch.ui.MIN_MIDI
import net.exent.perfectpitch.ui.RANGE_PADDING
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@HiltViewModel
class FreePlayViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository,
    private val midiDeviceManager: MidiDeviceManager,
    private val midiInputReceiver: MidiInputReceiver,
    private val midiOutputSender: MidiOutputSender,
    private val soundFontSynthesizer: SoundFontSynthesizer,
    private val noteUtils: NoteUtils,
    private val audioDelayRepository: AudioDelayRepository
) : ViewModel() {

    companion object {
        const val MIN_VISIBLE_KEYS = 5f
        const val MAX_VISIBLE_KEYS = 48f
        const val MIN_AUTO_FIT_RANGE = 12f
        private const val TAG = "FreePlayVM"
    }

    private var lastAdvanceWallMs: Long = 0L
    private var referenceA4: Float = NoteUtils.DEFAULT_A4_FREQ

    private val _state = MutableStateFlow(FreePlayState())
    val state: StateFlow<FreePlayState> = _state.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    val activeInputDelay: StateFlow<Int> = audioDelayRepository.activeInputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeOutputDelay: StateFlow<Int> = audioDelayRepository.activeOutputDelay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var listeningJob: Job? = null
    private var midiListeningJob: Job? = null
    private var replayJob: Job? = null
    private var synthOpenJob: Job? = null
    private var previewJob: Job? = null
    private var previewMidiNote: Int = -1

    // Output device saved before auto-switch on record start (null = no auto-switch done)
    private var outputDeviceBeforeRecord: OutputDevice? = null


    // Mic input tracking — committed (currently held) note
    private var currentNoteMidi: Int? = null
    private var currentNoteStartMs: Long = 0L
    private var centsSamples = mutableListOf<Float>()

    // Snap ON: honing window accumulates frequencies to average before committing
    private var honingActive = false
    private var honingStartSongMs: Long = 0L
    private var honingFreqs = mutableListOf<Float>()

    // MIDI input tracking (polyphonic: key = MIDI note number, value = active PlayedNote)
    private val activeMidiNotes = mutableMapOf<Int, PlayedNote>()

    private var inputDelayMs: Int = 0
    private var outputDelayMs: Int = 0
    private var replayGeneration = 0
    private var isPausedByGesture = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                referenceA4 = settings.concertPitch
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                _state.update {
                    it.copy(
                        fallSpeedPxPerSec = settings.freeplayFallSpeed,
                        beatsPerBar = settings.freeplayBeatsPerBar,
                        bpm = settings.freeplayBpm,
                        minNoteDivision = settings.freeplayMinNoteDivision,
                        snapEnabled = settings.freeplaySnapEnabled,
                        metronomeEnabled = settings.freeplayMetronomeEnabled
                    )
                }
            }
        }
        // Observe MIDI device availability and switch input source automatically
        viewModelScope.launch {
            combine(
                midiDeviceManager.effectiveMidiInputEnabled,
                midiDeviceManager.effectiveMidiOutputEnabled,
                midiDeviceManager.isMidiInputAvailable
            ) { inputEnabled, outputEnabled, available ->
                Triple(inputEnabled, outputEnabled, available)
            }.collect { (inputEnabled, outputEnabled, available) ->
                val wasRecording = _state.value.isRecording
                _state.update {
                    val newOutputDevice = when {
                        outputEnabled -> OutputDevice.MIDI
                        it.outputDevice == OutputDevice.NONE -> OutputDevice.NONE
                        else -> OutputDevice.SYNTH
                    }
                    it.copy(
                        midiInputEnabled = inputEnabled,
                        outputDevice = newOutputDevice
                    )
                }
                // Re-start audio pipeline with the correct source if currently recording
                if (wasRecording) {
                    stopListeningInternal()
                    startListeningInternal()
                }
            }
        }
        // Track connected MIDI device names for UI display
        viewModelScope.launch {
            midiDeviceManager.devices.collect { devices ->
                val inputDevice = devices.firstOrNull { it.outputPortCount > 0 }
                val midiOutputDevice = devices.firstOrNull { it.inputPortCount > 0 }
                _state.update {
                    it.copy(
                        midiInputDeviceName = inputDevice?.properties?.getString(MidiDeviceInfo.PROPERTY_NAME),
                        midiOutputDeviceName = midiOutputDevice?.properties?.getString(MidiDeviceInfo.PROPERTY_NAME)
                    )
                }
            }
        }
        viewModelScope.launch {
            audioDelayRepository.activeInputDelay.collect { delayMs ->
                inputDelayMs = delayMs
            }
        }
        viewModelScope.launch {
            audioDelayRepository.activeOutputDelay.collect { delayMs ->
                outputDelayMs = delayMs
            }
        }
    }

    /** Open the synthesizer for replay fallback. */
    fun initSynthesizer() {
        if (soundFontSynthesizer.isReady || synthOpenJob?.isActive == true) return
        synthOpenJob = viewModelScope.launch {
            try { soundFontSynthesizer.open() } catch (_: Exception) {}
        }
    }

    fun setMidiInputEnabled(useMidi: Boolean) {
        midiDeviceManager.setInputOverride(useMidi)
    }

    fun setOutputDevice(device: OutputDevice) {
        midiDeviceManager.setOutputOverride(device == OutputDevice.MIDI)
        _state.update { it.copy(outputDevice = device) }
    }

    fun toggleRecording() {
        if (_state.value.isRecording) { stopRecording(); return }
        if (_state.value.isPlaying) stopPlaying()
        startRecording()
    }

    private fun startRecording() {
        if (_state.value.outputDevice == OutputDevice.SYNTH) {
            outputDeviceBeforeRecord = OutputDevice.SYNTH
            setOutputDevice(OutputDevice.NONE)
        }
        val (loopStart, loopEnd) = effectiveLoopBounds()
        val pos = _state.value.songPositionMs
        if (pos < loopStart || pos >= loopEnd) {
            _state.update { it.copy(songPositionMs = loopStart) }
        }
        lastAdvanceWallMs = System.currentTimeMillis()
        startListeningInternal()
        _state.update { it.copy(isRecording = true) }
        // Use the same event-driven engine as playback for time advancement + metronome
        replayNotes()
    }

    private fun stopRecording() {
        stopEngine()
        stopListeningInternal()
        finalizeCurrentNote()
        abortHoning()
        _state.update { it.copy(isRecording = false) }
        outputDeviceBeforeRecord?.let { previous ->
            setOutputDevice(previous)
            outputDeviceBeforeRecord = null
        }
    }

    fun getEffectiveTime(realTimeMs: Long): Long {
        if (isPausedByGesture) {
            lastAdvanceWallMs = realTimeMs
            return _state.value.songPositionMs
        }
        return _state.value.songPositionMs
    }

    private fun startListeningInternal() {
        if (_state.value.midiInputEnabled) {
            midiListeningJob = viewModelScope.launch {
                midiInputReceiver.midiEventFlow().collect { event ->
                    processMidiEvent(event)
                }
            }
        } else {
            listeningJob = viewModelScope.launch {
                pitchProcessor.pitchFlow().collect { result ->
                    processResult(result)
                }
            }
        }
    }

    private fun stopListeningInternal() {
        listeningJob?.cancel()
        listeningJob = null
        midiListeningJob?.cancel()
        midiListeningJob = null
        // Finalize any open MIDI notes
        activeMidiNotes.values.forEach { note ->
            val finalized = note.copy(endTimeMs = _state.value.songPositionMs)
            _state.update { current ->
                current.copy(
                    playedNotes = current.playedNotes.map { if (it.id == note.id) finalized else it }
                )
            }
        }
        activeMidiNotes.clear()
    }

    fun stopListening() {
        stopListeningInternal()
        finalizeCurrentNote()
        abortHoning()
        _state.update { it.copy(activeNote = null) }
    }

    fun setBeatsPerBar(beats: Int) {
        val clamped = beats.coerceIn(1, 16)
        _state.update { it.copy(beatsPerBar = clamped) }
        viewModelScope.launch { settingsRepository.updateFreeplayBeatsPerBar(clamped) }
    }

    fun setBarDivision(division: Int) {
        val valid = listOf(2, 4, 8, 16, 32)
        if (division in valid) _state.update { it.copy(barDivision = division) }
    }

    fun setBpm(bpm: Float) {
        val clamped = bpm.coerceIn(MIN_BPM, MAX_BPM)
        _state.update { it.copy(bpm = clamped) }
        viewModelScope.launch { settingsRepository.updateFreeplayBpm(clamped) }
    }

    fun setMinNoteDivision(division: Int) {
        val valid = listOf(4, 8, 16, 32)
        val clamped = valid.minByOrNull { abs(it - division) } ?: 16
        _state.update { it.copy(minNoteDivision = clamped) }
        viewModelScope.launch { settingsRepository.updateFreeplayMinNoteDivision(clamped) }
    }

    fun toggleSnap() {
        val newValue = !_state.value.snapEnabled
        _state.update { it.copy(snapEnabled = newValue) }
        viewModelScope.launch { settingsRepository.updateFreeplaySnapEnabled(newValue) }
    }

    // region Voice management

    fun setActiveVoice(index: Int) {
        val s = _state.value
        if (index in 0 until s.voiceCount) {
            _state.update { it.copy(activeVoiceIndex = index) }
        }
    }

    fun addVoice() {
        _state.update { current ->
            if (current.voiceCount >= 8) return@update current
            val newVoiceIdx = current.voiceCount
            current.copy(voiceCount = newVoiceIdx + 1, activeVoiceIndex = newVoiceIdx)
        }
    }

    // endregion

    // region Loop

    private fun barDurationMs(): Double = _state.value.beatsPerBar * (60000.0 / _state.value.bpm)

    /** Returns (loopStartMs, loopEndMs) based on locked bars or sensible defaults. */
    private fun effectiveLoopBounds(): Pair<Long, Long> {
        val s = _state.value
        val bMs = barDurationMs().toLong()
        val startMs = if (s.loopStartBar != null) (s.loopStartBar - 1) * bMs else 0L
        val endMs = if (s.loopEndBar != null) {
            (s.loopEndBar - 1) * bMs
        } else {
            val lastNoteEnd = s.playedNotes.mapNotNull { it.endTimeMs }.maxOrNull() ?: 0L
            val defaultEndBar = (ceil(lastNoteEnd.toDouble() / bMs).toInt() + 3).coerceAtLeast(3)
            (defaultEndBar - 1) * bMs
        }
        return Pair(startMs, endMs)
    }

    fun setLoopStart() {
        val barMs = barDurationMs()
        val s = _state.value
        val nowBar = (s.songPositionMs / barMs).roundToInt() + 1
        val locked = s.loopStartBar
        // Unlock if tapping the already-locked bar
        if (locked != null && locked == nowBar) { _state.update { it.copy(loopStartBar = null) }; return }
        // If end is not explicitly set, check that start doesn't exceed default end
        if (s.loopEndBar == null) {
            val lastNoteEnd = s.playedNotes.mapNotNull { it.endTimeMs }.maxOrNull() ?: 0L
            val defaultEndBar = (ceil(lastNoteEnd.toDouble() / barMs).toInt() + 3).coerceAtLeast(3)
            if (nowBar > defaultEndBar) {
                _snackbarMessage.tryEmit(appContext.getString(R.string.freeplay_start_bar_exceeds_end))
                return
            }
        }
        _state.update { it.copy(loopStartBar = nowBar) }
    }

    fun setLoopEnd() {
        val barMs = barDurationMs()
        val s = _state.value
        val nowBar = (s.songPositionMs / barMs).roundToInt() + 1
        val locked = s.loopEndBar
        // Unlock if tapping the already-locked bar
        if (locked != null && locked == nowBar) { _state.update { it.copy(loopEndBar = null) }; return }
        val effectiveStart = s.loopStartBar ?: 1
        if (nowBar == effectiveStart) { _snackbarMessage.tryEmit(appContext.getString(R.string.freeplay_end_bar_mismatch)); return }
        if (s.loopStartBar != null && nowBar < s.loopStartBar) {
            _state.update { it.copy(loopStartBar = nowBar, loopEndBar = s.loopStartBar) }; return
        }
        _state.update { it.copy(loopEndBar = nowBar) }
    }

    // endregion

    // region Note editing

    fun selectNote(id: String?) {
        _state.update { it.copy(selectedNoteId = id) }
        if (id != null && _state.value.isPlaying) stopPlaying()
    }

    fun deleteNote(id: String) {
        _state.update { current ->
            current.copy(
                playedNotes = current.playedNotes.filter { it.id != id },
                selectedNoteId = null
            )
        }
    }

    fun splitNote(id: String) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val endMs = note.endTimeMs ?: return@update current
            val duration = endMs - note.startTimeMs
            if (duration < 2L) return@update current
            val midMs = note.startTimeMs + duration / 2L
            val first  = note.copy(endTimeMs = midMs)
            val second = note.copy(
                id = java.util.UUID.randomUUID().toString(),
                startTimeMs = midMs
            )
            current.copy(
                playedNotes = current.playedNotes.flatMap { if (it.id == id) listOf(first, second) else listOf(it) },
                selectedNoteId = null
            )
        }
    }

    data class NoteInfo(val startTimeMs: Long, val endTimeMs: Long?, val midiNumber: Int)
    fun getNoteInfo(id: String): NoteInfo? = _state.value.playedNotes.find { it.id == id }
        ?.let { NoteInfo(it.startTimeMs, it.endTimeMs, it.midiNumber) }

    fun previewNoteTone(midiNote: Int) {
        if (!soundFontSynthesizer.isReady) return
        previewJob?.cancel()
        if (previewMidiNote != -1) soundFontSynthesizer.noteOff(0, previewMidiNote)
        previewMidiNote = midiNote
        previewJob = viewModelScope.launch {
            soundFontSynthesizer.noteOn(0, midiNote, 80)
            delay(300)
            soundFontSynthesizer.noteOff(0, midiNote)
            previewMidiNote = -1
        }
    }

    /** Move note body: rawStartMs is the ABSOLUTE target start (pre-snap), newMidi is the absolute target MIDI note. */
    fun moveNote(id: String, newMidi: Int, rawStartMs: Long) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val clampedMidi = newMidi.coerceIn(21, 108)
            val resolution = noteUtils.resolveMidiNote(clampedMidi)
            val duration = note.endTimeMs?.minus(note.startTimeMs)
            val newStart = snapToGridIfEnabled(rawStartMs, current.snapEnabled).coerceAtLeast(0L)
            val newEnd = duration?.let { newStart + it }
            val newNote = note.copy(
                noteName = resolution.noteName,
                octave = resolution.octave,
                midiNumber = clampedMidi,
                startTimeMs = newStart,
                endTimeMs = newEnd
            )
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) newNote else it })
        }
    }

    /** Set start handle: rawStartMs is the ABSOLUTE target start (pre-snap). */
    fun extendNoteStart(id: String, rawStartMs: Long) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val endMs = note.endTimeMs ?: return@update current
            val newStart = snapToGridIfEnabled(rawStartMs, current.snapEnabled).coerceIn(0L, endMs - 1L)
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) note.copy(startTimeMs = newStart) else it })
        }
    }

    /** Set end handle: rawEndMs is the ABSOLUTE target end (pre-snap). */
    fun extendNoteEnd(id: String, rawEndMs: Long) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val newEnd = snapToGridIfEnabled(rawEndMs, current.snapEnabled).coerceAtLeast(note.startTimeMs + 1L)
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) note.copy(endTimeMs = newEnd) else it })
        }
    }

    /** Snap final position and remove overlapping notes in the same voice. */
    fun commitNoteEdit(id: String) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val endMs = note.endTimeMs ?: return@update current
            val snappedStart = snapToGridIfEnabled(note.startTimeMs, current.snapEnabled)
            val snappedEnd   = snapToGridIfEnabled(endMs, current.snapEnabled)
                .coerceAtLeast(snappedStart + 1L)
            val snapped = note.copy(startTimeMs = snappedStart, endTimeMs = snappedEnd)
            val voice = snapped.voiceIndex
            val filtered = current.playedNotes.filter { other ->
                other.id == id || other.voiceIndex != voice ||
                other.midiNumber != snapped.midiNumber ||
                other.endTimeMs == null || other.endTimeMs <= snappedStart || other.startTimeMs >= snappedEnd
            }
            current.copy(playedNotes = filtered.map { if (it.id == id) snapped else it })
        }
    }

    // endregion

    // region Import / Export

    fun exportToMidi(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = _state.value
                // Note timestamps are already song-relative (0-based)
                val bytes = MidiWriter().write(s.playedNotes, s.bpm, s.beatsPerBar)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } catch (e: IOException) {
                Log.e(TAG, "Export failed", e)
            }
        }
    }

    fun importFromMidi(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val multiTrack = MidiImporter.parse(inputStream)
                val result = MidiImporter.convertToPlayedNotes(multiTrack)
                val notes = result.notes
                val importedBpm = result.bpm
                val importedBeatsPerBar = result.beatsPerBar
                val voiceCount = ((notes.maxOfOrNull { it.voiceIndex } ?: 0) + 1).coerceIn(1, 8)

                finalizeCurrentNote()

                // Imported notes have 0-based timestamps
                val maxMs = notes.maxOfOrNull { it.endTimeMs ?: it.startTimeMs } ?: 0L
                val barMs = importedBeatsPerBar * (60000.0 / importedBpm)
                lastAdvanceWallMs = System.currentTimeMillis()

                // Auto-fit view range to imported note range
                val minMidi = (notes.minOfOrNull { it.midiNumber }?.toFloat() ?: 60f) - RANGE_PADDING
                val maxMidi = (notes.maxOfOrNull { it.midiNumber }?.toFloat() ?: 72f) + RANGE_PADDING
                val range = maxMidi - minMidi
                val viewMin = (if (range < MIN_AUTO_FIT_RANGE) minMidi - (MIN_AUTO_FIT_RANGE - range) / 2f else minMidi)
                    .coerceAtLeast(MIN_MIDI)
                val viewMax = (viewMin + range.coerceAtLeast(MIN_AUTO_FIT_RANGE))
                    .coerceAtMost(MAX_MIDI)

                _state.update { current ->
                    FreePlayState(
                        songPositionMs = maxMs + barMs.toLong(),
                        playedNotes = notes,
                        bpm = importedBpm,
                        beatsPerBar = importedBeatsPerBar,
                        voiceCount = voiceCount,
                        fallSpeedPxPerSec = current.fallSpeedPxPerSec,
                        snapEnabled = current.snapEnabled,
                        minNoteDivision = current.minNoteDivision,
                        viewMinMidi = viewMin,
                        viewMaxMidi = viewMax,
                        midiInputEnabled = current.midiInputEnabled,
                        midiOutputDeviceName = current.midiOutputDeviceName,
                        outputDevice = current.outputDevice
                    )
                }
            } catch (e: IOException) {
                Log.e(TAG, "Import failed", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Import failed", e)
            }
        }
    }

    // endregion

    private fun gridIntervalMs(): Long {
        val s = _state.value
        return ((60_000.0 / s.bpm) * (4.0 / s.minNoteDivision)).toLong().coerceAtLeast(1L)
    }

    private fun min48thNoteMs(): Long {
        val bpm = _state.value.bpm
        return if (bpm > 0f) (5000.0 / bpm).toLong().coerceAtLeast(1L) else 1L
    }

    private fun snapToGrid(timeMs: Long): Long {
        val interval = gridIntervalMs()
        // Nearest-neighbour snap (rounds to closest grid point)
        val snapped = Math.round(timeMs.toDouble() / interval) * interval
        return snapped.coerceAtLeast(0L)
    }

    private fun snapToGridIfEnabled(timeMs: Long, enabled: Boolean): Long =
        if (enabled) snapToGrid(timeMs) else timeMs.coerceAtLeast(0L)

    private fun addMetronomeEvents(
        evts: MutableList<ReplayEvent>,
        startTime: Long,
        loopEndMs: Long,
        bpm: Float,
        beatsPerBar: Int
    ) {
        val interval = (60000.0 / bpm).toLong()
        if (interval <= 0) return
        data class Click(val note: Int, val dur: Long)
        val regular = Click(85, 40L)
        val accent = Click(86, 60L)
        fun click(beat: Long) = if (beatsPerBar > 0 && (beat % beatsPerBar) == 0L) accent else regular

        var beatTime = startTime - (startTime % interval)
        if (beatTime < startTime) beatTime += interval
        while (beatTime < loopEndMs) {
            val c = click(beatTime / interval)
            evts.add(ReplayEvent(beatTime, c.note, true, 9))
            evts.add(ReplayEvent(beatTime + c.dur, c.note, false, 9))
            beatTime += interval
        }
    }

    fun onHorizontalZoom(scaleFactor: Float, focusFraction: Float) {
        _state.update { current ->
            val range = current.viewMaxMidi - current.viewMinMidi
            val newRange = (range / scaleFactor).coerceIn(MIN_VISIBLE_KEYS, MAX_VISIBLE_KEYS)
            val focusMidi = current.viewMinMidi + range * focusFraction
            var newMin = focusMidi - newRange * focusFraction
            var newMax = focusMidi + newRange * (1f - focusFraction)
            if (newMin < MIN_MIDI) {
                newMin = MIN_MIDI
                newMax = (newMin + newRange).coerceAtMost(MAX_MIDI)
            }
            if (newMax > MAX_MIDI) {
                newMax = MAX_MIDI
                newMin = (newMax - newRange).coerceAtLeast(MIN_MIDI)
            }
            current.copy(viewMinMidi = newMin, viewMaxMidi = newMax)
        }
    }

    fun onVerticalZoom(scaleFactor: Float) {
        _state.update { current ->
            val newSpeed = (current.fallSpeedPxPerSec * scaleFactor).coerceIn(MIN_FALL_SPEED, MAX_FALL_SPEED)
            current.copy(fallSpeedPxPerSec = newSpeed)
        }
        viewModelScope.launch { settingsRepository.updateFreeplayFallSpeed(_state.value.fallSpeedPxPerSec) }
    }

    fun onHorizontalScroll(deltaMidi: Float) {
        _state.update { current ->
            val range = current.viewMaxMidi - current.viewMinMidi
            var newMin = current.viewMinMidi - deltaMidi
            var newMax = current.viewMaxMidi - deltaMidi
            if (newMin < MIN_MIDI) {
                newMin = MIN_MIDI
                newMax = newMin + range
            }
            if (newMax > MAX_MIDI) {
                newMax = MAX_MIDI
                newMin = newMax - range
            }
            current.copy(viewMinMidi = newMin, viewMaxMidi = newMax)
        }
    }

    fun setPianoVisibility(v: PianoVisibility) {
        _state.update { it.copy(pianoVisibility = v) }
    }

    fun onVerticalScroll(deltaMs: Long) {
        val minPosition = -(2 * barDurationMs()).toLong()
        _state.update { current ->
            current.copy(songPositionMs = (current.songPositionMs - deltaMs).coerceAtLeast(minPosition))
        }
    }

    fun pausePlaybackRecording() {
        if (!_state.value.isPlaying && !_state.value.isRecording) return
        if (isPausedByGesture) return
        isPausedByGesture = true
        midiOutputSender.allNotesOff()
        if (soundFontSynthesizer.isReady) soundFontSynthesizer.allNotesOff()
    }

    fun resumePlaybackRecording() {
        if (!isPausedByGesture) return
        lastAdvanceWallMs = System.currentTimeMillis()
        isPausedByGesture = false
    }

    private fun processResult(result: PitchResult) {
        if (isPausedByGesture) return
        if (_state.value.snapEnabled) {
            processResultSnap(result)
        } else {
            processResultNoSnap(result)
        }
    }

    /** Snap OFF: register note on the very first frame the semitone changes. No debounce, no minimum. */
    private fun processResultNoSnap(result: PitchResult) {
        if (result.isValid) {
            val midi = result.midiNoteNumber
            if (currentNoteMidi == null || midi != currentNoteMidi) {
                finalizeCurrentNote()
                currentNoteMidi = midi
                currentNoteStartMs = _state.value.songPositionMs
                centsSamples.clear()
                centsSamples.add(result.centsOff)
                expandRange(midi)
                if (_state.value.isRecording) {
                    val note = PlayedNote(
                        noteName = result.noteName,
                        octave = result.octave,
                        midiNumber = midi,
                        startTimeMs = currentNoteStartMs,
                        centsHistory = listOf(result.centsOff),
                        id = UUID.randomUUID().toString(),
                        voiceIndex = _state.value.activeVoiceIndex
                    )
                    _state.update { it.copy(activeNote = note) }
                }
            } else {
                centsSamples.add(result.centsOff)
                _state.update { current ->
                    current.copy(
                        activeNote = current.activeNote?.copy(
                            centsHistory = current.activeNote.centsHistory + result.centsOff
                        )
                    )
                }
            }
        } else {
            if (currentNoteMidi != null) {
                finalizeCurrentNote()
                _state.update { it.copy(activeNote = null) }
            }
        }
    }

    /**
     * Snap ON: collect frequency samples for one grid interval (min note duration), then commit the
     * note whose average frequency best matches the collected data. Shows a tentative note visually
     * during the honing window.
     */
    private fun processResultSnap(result: PitchResult) {
        if (result.isValid) {
            val midi = result.midiNoteNumber

            if (honingActive) {
                val elapsed = _state.value.songPositionMs - honingStartSongMs
                if (midi != currentNoteMidi) {
                    // Pitch changed mid-window: restart honing for the new pitch
                    finalizeCurrentNote()
                    abortHoning()
                    startHoning(result)
                } else {
                    // Continue accumulating
                    honingFreqs.add(result.frequency)
                    centsSamples.add(result.centsOff)
                    // Update tentative display
                    _state.update { current ->
                        current.copy(
                            activeNote = current.activeNote?.copy(
                                centsHistory = current.activeNote.centsHistory + result.centsOff
                            )
                        )
                    }
                    if (elapsed >= gridIntervalMs()) {
                        commitHoning()
                    }
                }
            } else {
                if (currentNoteMidi == null || midi != currentNoteMidi) {
                    // New pitch: finalize any open committed note, start honing
                    finalizeCurrentNote()
                    startHoning(result)
                } else {
                    // Same pitch, note already committed — just extend it
                    centsSamples.add(result.centsOff)
                    _state.update { current ->
                        current.copy(
                            activeNote = current.activeNote?.copy(
                                centsHistory = current.activeNote.centsHistory + result.centsOff
                            )
                        )
                    }
                }
            }
        } else {
            if (honingActive) {
                val elapsed = _state.value.songPositionMs - honingStartSongMs
                // Accept note if played for at least half the min duration (leeway for near-misses)
                if (elapsed >= gridIntervalMs() / 2 && honingFreqs.isNotEmpty()) {
                    commitHoning()
                    finalizeCurrentNote()
                } else {
                    abortHoning()
                    _state.update { it.copy(activeNote = null) }
                }
            } else if (currentNoteMidi != null) {
                finalizeCurrentNote()
                _state.update { it.copy(activeNote = null) }
            }
        }
    }

    private fun startHoning(result: PitchResult) {
        val midi = result.midiNoteNumber
        honingActive = true
        honingStartSongMs = _state.value.songPositionMs
        honingFreqs.clear()
        honingFreqs.add(result.frequency)
        centsSamples.clear()
        centsSamples.add(result.centsOff)
        currentNoteMidi = midi
        currentNoteStartMs = snapToGrid(honingStartSongMs)
        expandRange(midi)
        if (_state.value.isRecording) {
            val note = PlayedNote(
                        noteName = result.noteName,
                        octave = result.octave,
                        midiNumber = midi,
                        startTimeMs = currentNoteStartMs,
                        centsHistory = listOf(result.centsOff),
                        id = UUID.randomUUID().toString(),
                        voiceIndex = _state.value.activeVoiceIndex
                    )
                    _state.update { it.copy(activeNote = note) }
                }
            }

    private fun commitHoning() {
        honingActive = false
        if (honingFreqs.isEmpty()) return
        val avgFreq = honingFreqs.average().toFloat()
        val noteInfo = noteUtils.frequencyToNote(avgFreq, referenceA4)
        val confirmedMidi = noteInfo.midiNumber
        currentNoteMidi = confirmedMidi
        currentNoteStartMs = snapToGrid(honingStartSongMs)
        expandRange(confirmedMidi)
        if (_state.value.isRecording) {
            val note = PlayedNote(
                noteName = noteInfo.name,
                octave = noteInfo.octave,
                midiNumber = confirmedMidi,
                startTimeMs = currentNoteStartMs,
                centsHistory = centsSamples.toList(),
                id = UUID.randomUUID().toString(),
                voiceIndex = _state.value.activeVoiceIndex
            )
            _state.update { it.copy(activeNote = note) }
        }
        honingFreqs.clear()
    }

    private fun abortHoning() {
        honingActive = false
        honingFreqs.clear()
        currentNoteMidi = null
        centsSamples.clear()
    }

    private fun finalizeCurrentNote() {
        val midi = currentNoteMidi ?: return
        val now = _state.value.songPositionMs
        val activeNote = _state.value.activeNote
        if (activeNote != null && centsSamples.isNotEmpty()) {
            val snappedEnd = if (_state.value.snapEnabled) {
                val interval = gridIntervalMs()
                val rawSnapped = snapToGrid(now)
                if (rawSnapped <= activeNote.startTimeMs) activeNote.startTimeMs + interval else rawSnapped
            } else now
            val finalNote = activeNote.copy(endTimeMs = snappedEnd)

            // Noise filter: discard notes shorter than a 48th note
            if (snappedEnd - activeNote.startTimeMs < min48thNoteMs()) {
                _state.update { it.copy(activeNote = null) }
                currentNoteMidi = null
                centsSamples.clear()
                return
            }

            // Remove overlapping same-pitch notes in the same voice (legato retrigger).
            // Different-pitch overlaps are kept for polyphony.
            val voice = finalNote.voiceIndex
            val newStart = finalNote.startTimeMs
            val newEnd = snappedEnd
            _state.update { current ->
                val filtered = (current.playedNotes + finalNote).filter { other ->
                    other.id == finalNote.id || other.voiceIndex != voice ||
                    other.midiNumber != finalNote.midiNumber ||
                    other.endTimeMs == null || other.endTimeMs <= newStart || other.startTimeMs >= newEnd
                }
                current.copy(playedNotes = filtered, activeNote = null)
            }
        } else {
            _state.update { it.copy(activeNote = null) }
        }
        currentNoteMidi = null
        centsSamples.clear()
    }

    /**
     * Handles a MIDI note event from a connected device.
     * NoteOn starts a [PlayedNote] (immediately added to playedNotes unfinalized).
     * NoteOff finalizes it.
     */
    private fun processMidiEvent(event: MidiNoteEvent) {
        if (isPausedByGesture) return
        when (event) {
            is MidiNoteEvent.NoteOn -> handleMidiNoteOn(event.noteNumber)
            is MidiNoteEvent.NoteOff -> handleMidiNoteOff(event.noteNumber)
        }
    }

    private fun handleMidiNoteOn(midiNote: Int) {
        if (activeMidiNotes.containsKey(midiNote)) return
        val isRecording = _state.value.isRecording
        val resolution = noteUtils.resolveMidiNote(midiNote)
        expandRange(midiNote)
        val songPositionMs = _state.value.songPositionMs

        val note = PlayedNote(
            noteName = resolution.noteName,
            octave = resolution.octave,
            midiNumber = midiNote,
            startTimeMs = songPositionMs,
            id = UUID.randomUUID().toString(),
            voiceIndex = _state.value.activeVoiceIndex
        )
        activeMidiNotes[midiNote] = note

        if (isRecording && songPositionMs >= 0) {
            _state.update { current ->
                current.copy(playedNotes = current.playedNotes + note, activeNote = note)
            }
        } else {
            _state.update { it.copy(activeNote = note) }
        }
    }

    private fun handleMidiNoteOff(midiNote: Int) {
        val activeNote = activeMidiNotes.remove(midiNote) ?: return
        val endMs = _state.value.songPositionMs
        val isRecording = _state.value.isRecording

        if (!isRecording || activeNote.startTimeMs < 0) {
            _state.update { it.copy(activeNote = activeMidiNotes.values.lastOrNull()) }
            return
        }

        if (endMs - activeNote.startTimeMs < min48thNoteMs()) {
            _state.update { current ->
                current.copy(
                    playedNotes = current.playedNotes.filter { it.id != activeNote.id },
                    activeNote = activeMidiNotes.values.lastOrNull()
                )
            }
            return
        }

        val finalized = activeNote.copy(endTimeMs = endMs)
        _state.update { current ->
            current.copy(
                playedNotes = current.playedNotes.map {
                    if (it.id == activeNote.id) finalized else it
                },
                activeNote = activeMidiNotes.values.lastOrNull()
            )
        }
    }

    fun replayNotes() {
        replayJob?.cancel()
        replayGeneration++
        val gen = replayGeneration

        // Ensure the synthesizer is open — it may have been closed by Settings calibration
        if (!soundFontSynthesizer.isReady) {
            try { soundFontSynthesizer.open() } catch (_: Exception) {}
        }

        val isRecordMode = _state.value.isRecording

        // Snap to loop start if current position is outside the loop area
        val (initialStart, initialEnd) = effectiveLoopBounds()
        val currentPos = _state.value.songPositionMs
        if (currentPos < initialStart || currentPos >= initialEnd) {
            _state.update { it.copy(songPositionMs = initialStart) }
        }

        _state.update { it.copy(isPlaying = !isRecordMode) }

        replayJob = viewModelScope.launch {
            val job = coroutineContext[kotlinx.coroutines.Job] ?: return@launch

            val player: NotePlayer? = when (_state.value.outputDevice) {
                OutputDevice.MIDI -> if (midiOutputSender.isReady) midiOutputSender else soundFontSynthesizer
                OutputDevice.SYNTH -> soundFontSynthesizer
                OutputDevice.NONE -> null
            }

            var lastBuiltBpm = _state.value.bpm
            var lastBuiltBeatsPerBar = _state.value.beatsPerBar
            var lastBuiltLoopEndMs = 0L

            fun buildEvents(startTime: Long): List<ReplayEvent> {
                val (_, loopEndMs) = effectiveLoopBounds()
                lastBuiltLoopEndMs = loopEndMs
                val evts = ReplayEvents.notes(_state.value.playedNotes, startTime, loopEndMs).toMutableList()
                if (_state.value.metronomeEnabled) {
                    addMetronomeEvents(evts, startTime, loopEndMs, lastBuiltBpm, lastBuiltBeatsPerBar)
                }
                evts.sortBy { it.timeMs }
                return evts
            }

            val visualStartOffsetMs = -inputDelayMs.toLong()
            var audioPositionMs = _state.value.songPositionMs
            var allowVisualBeforeLoopStart = true
            var seekAnchorMs = Long.MIN_VALUE
            var events = buildEvents(audioPositionMs)
            var eventIndex = 0
            var lastWallMs = System.currentTimeMillis()

            while (job.isActive) {
                if (isPausedByGesture) {
                    lastWallMs = System.currentTimeMillis()
                    delay(16L)
                    continue
                }

                val (loopStartMs, loopEndMs) = effectiveLoopBounds()
                val rawVisualMs = audioPositionMs - visualStartOffsetMs

                // Seek detection — only before first loop completes
                val stateVisualPosition = _state.value.songPositionMs
                val expectedVisual = rawVisualMs.coerceIn(maxOf(seekAnchorMs, loopStartMs), loopEndMs)
                val currentBpm = _state.value.bpm
                val currentBeatsPerBar = _state.value.beatsPerBar
                val bpmChanged = currentBpm != lastBuiltBpm || currentBeatsPerBar != lastBuiltBeatsPerBar
                if (bpmChanged) {
                    lastBuiltBpm = currentBpm
                    lastBuiltBeatsPerBar = currentBeatsPerBar
                    events = buildEvents(audioPositionMs)
                    eventIndex = 0
                }
                if (loopEndMs != lastBuiltLoopEndMs) {
                    events = buildEvents(audioPositionMs)
                    eventIndex = 0
                }
                if (allowVisualBeforeLoopStart && abs(stateVisualPosition - expectedVisual) > 100) {
                    player?.allNotesOff()
                    audioPositionMs = stateVisualPosition
                    seekAnchorMs = stateVisualPosition
                    allowVisualBeforeLoopStart = true
                    lastBuiltBpm = currentBpm
                    lastBuiltBeatsPerBar = currentBeatsPerBar
                    events = buildEvents(audioPositionMs)
                    eventIndex = 0
                    lastWallMs = System.currentTimeMillis()
                }

                val wallNow = System.currentTimeMillis()
                val delta = (wallNow - lastWallMs).coerceAtLeast(0L)
                lastWallMs = wallNow

                audioPositionMs += delta

                // Fire due events the output delay early so notes are heard
                // when the bar crosses the now line.
                val outputOffsetMs = outputDelayMs.toLong()
                while (eventIndex < events.size && events[eventIndex].timeMs - outputOffsetMs <= audioPositionMs) {
                    val ev = events[eventIndex++]
                    if (ev.trackOrigin == 9) {
                        if (ev.isOn) soundFontSynthesizer.noteOn(9, ev.midiNote, 80)
                        else if (soundFontSynthesizer.isReady) soundFontSynthesizer.noteOff(9, ev.midiNote)
                    } else {
                        if (ev.isOn) player?.noteOn(ev.trackOrigin, ev.midiNote, 80)
                        else player?.noteOff(ev.trackOrigin, ev.midiNote)
                    }
                }

                // Compute visual position
                val rawVisualMsPostDelta = audioPositionMs - visualStartOffsetMs
                val visualLowerBound = maxOf(seekAnchorMs, loopStartMs)
                val visualPositionMs =
                    if (!allowVisualBeforeLoopStart && rawVisualMsPostDelta < loopStartMs) {
                        (loopEndMs + rawVisualMsPostDelta - loopStartMs).coerceIn(loopStartMs, loopEndMs)
                    } else {
                        rawVisualMsPostDelta.coerceIn(visualLowerBound, loopEndMs)
                    }
                _state.update { it.copy(songPositionMs = visualPositionMs) }

                // Loop when audio wraps around
                if (audioPositionMs >= loopEndMs) {
                    if (isRecordMode) {
                        finalizeCurrentNote()
                        abortHoning()
                        _state.update { it.copy(activeNote = null) }
                    }
                    player?.allNotesOff()
                    audioPositionMs = loopStartMs
                    allowVisualBeforeLoopStart = false
                    seekAnchorMs = Long.MIN_VALUE
                    events = buildEvents(audioPositionMs)
                    eventIndex = 0
                    lastWallMs = System.currentTimeMillis()
                }

                delay(16L)
            }

            if (replayGeneration == gen) {
                player?.allNotesOff()
                if (soundFontSynthesizer.isReady) soundFontSynthesizer.allNotesOff()
                lastAdvanceWallMs = System.currentTimeMillis()
                _state.update {
                    if (isRecordMode) it.copy(isRecording = false)
                    else it.copy(isPlaying = false)
                }
            }
        }
    }

    private fun stopEngine() {
        replayJob?.cancel()
        replayJob = null
        lastAdvanceWallMs = System.currentTimeMillis()
        midiOutputSender.allNotesOff()
        if (soundFontSynthesizer.isReady) soundFontSynthesizer.allNotesOff()
    }

    fun stopPlaying() {
        stopEngine()
        _state.update { it.copy(isPlaying = false) }
    }

    fun togglePlaying() {
        if (_state.value.isPlaying) { stopPlaying(); return }
        if (_state.value.isRecording) stopRecording()
        replayNotes()
    }

    fun toggleMetronome() {
        val newState = !_state.value.metronomeEnabled
        _state.update { it.copy(metronomeEnabled = newState) }
        viewModelScope.launch { settingsRepository.updateFreeplayMetronomeEnabled(newState) }
    }

    fun stopMetronome() {
        _state.update { it.copy(metronomeEnabled = false) }
    }

    private fun expandRange(midi: Int) {
        _state.update { current ->
            val newViewMin = min(current.viewMinMidi, midi.toFloat() - RANGE_PADDING).coerceAtLeast(MIN_MIDI)
            val newViewMax = max(current.viewMaxMidi, midi.toFloat() + RANGE_PADDING).coerceAtMost(MAX_MIDI)
            current.copy(viewMinMidi = newViewMin, viewMaxMidi = newViewMax)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopEngine()
        stopListening()
        previewJob?.cancel()
        soundFontSynthesizer.close()
    }
}
