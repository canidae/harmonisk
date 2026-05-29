package com.perfectpitch.ui.freeplay

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.MelodySynthesizer
import com.perfectpitch.audio.NotePlayer
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.midi.MidiDeviceManager
import com.perfectpitch.midi.MidiImporter
import com.perfectpitch.midi.MidiInputReceiver
import com.perfectpitch.midi.MidiNoteEvent
import com.perfectpitch.midi.MidiOutputSender
import com.perfectpitch.midi.MidiWriter
import com.perfectpitch.music.NoteUtils
import com.perfectpitch.music.PitchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class FreePlayViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository,
    private val midiDeviceManager: MidiDeviceManager,
    private val midiInputReceiver: MidiInputReceiver,
    private val midiOutputSender: MidiOutputSender,
    private val melodySynthesizer: MelodySynthesizer,
    private val noteUtils: NoteUtils
) : ViewModel() {

    companion object {
        const val MIN_VISIBLE_KEYS = 5f
        const val MAX_VISIBLE_KEYS = 48f
        const val MIN_FALL_SPEED = 30f
        const val MAX_FALL_SPEED = 600f
        const val RANGE_PADDING = 2
        const val ABSOLUTE_MIN_MIDI = 21f  // A0
        const val ABSOLUTE_MAX_MIDI = 108f // A8
        const val MIN_AUTO_FIT_RANGE = 12f
        private const val TAG = "FreePlayVM"
    }

    private var lastAdvanceWallMs: Long = System.currentTimeMillis()
    private var referenceA4: Float = NoteUtils.DEFAULT_A4_FREQ

    private val _state = MutableStateFlow(FreePlayState())
    val state: StateFlow<FreePlayState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var midiListeningJob: Job? = null
    private var replayJob: Job? = null
    private var synthOpenJob: Job? = null

    // Replay/recording state flags
    private var replayActive = false
    private var recordingActive = false

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

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                referenceA4 = settings.concertPitch
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setYinThreshold(settings.yinThreshold)
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
                        snapEnabled = settings.freeplaySnapEnabled
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
                val wasListening = _state.value.isListening
                _state.update {
                    val newOutputDevice = when {
                        outputEnabled -> OutputDevice.MIDI
                        it.outputDevice == OutputDevice.NONE -> OutputDevice.NONE
                        else -> OutputDevice.SYNTH
                    }
                    it.copy(
                        midiAvailable = available,
                        midiInputEnabled = inputEnabled,
                        outputDevice = newOutputDevice
                    )
                }
                // Re-start listening with the correct source if already active
                if (wasListening) {
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
    }

    /** Open the synthesizer for replay fallback. Call from the Composable where context is available. */
    fun initSynthesizer(context: Context) {
        if (melodySynthesizer.isReady || synthOpenJob?.isActive == true) return
        synthOpenJob = viewModelScope.launch {
            try { melodySynthesizer.open(context) } catch (_: Exception) {}
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
        if (_state.value.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (_state.value.outputDevice == OutputDevice.SYNTH) {
            outputDeviceBeforeRecord = OutputDevice.SYNTH
            setOutputDevice(OutputDevice.NONE)
        }
        recordingActive = true
        lastAdvanceWallMs = System.currentTimeMillis()
        _state.update { it.copy(isRecording = true) }
    }

    private fun stopRecording() {
        recordingActive = false
        finalizeCurrentNote()
        abortHoning()
        _state.update { it.copy(isRecording = false) }
        outputDeviceBeforeRecord?.let { previous ->
            setOutputDevice(previous)
            outputDeviceBeforeRecord = null
        }
    }

    fun getEffectiveTime(realTimeMs: Long): Long {
        val delta = (realTimeMs - lastAdvanceWallMs).coerceAtLeast(0L)
        lastAdvanceWallMs = realTimeMs
        if (!replayActive && recordingActive) {
            _state.update { it.copy(songPositionMs = it.songPositionMs + delta) }
        }
        return _state.value.songPositionMs
    }

    fun startListening() {
        if (listeningJob?.isActive == true || midiListeningJob?.isActive == true) return
        _state.update { it.copy(isListening = true) }
        startListeningInternal()
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
        _state.update { it.copy(isListening = false, activeNote = null) }
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
        val clamped = bpm.coerceIn(20f, 300f)
        _state.update { it.copy(bpm = clamped) }
        viewModelScope.launch { settingsRepository.updateFreeplayBpm(clamped) }
    }

    fun setMinNoteDivision(division: Int) {
        val valid = listOf(4, 8, 16, 32, 64)
        val clamped = valid.minByOrNull { kotlin.math.abs(it - division) } ?: 16
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

    fun setLoopStart() {
        val barMs = barDurationMs()
        val currentBar = (_state.value.songPositionMs / barMs).toInt()
        _state.update { it.copy(loopStartBar = currentBar) }
    }

    fun setLoopEnd() {
        val barMs = barDurationMs()
        val currentBar = (_state.value.songPositionMs / barMs).toInt() + 1
        _state.update { it.copy(loopEndBar = currentBar) }
    }

    fun toggleLoop() {
        val s = _state.value
        if (s.loopStartBar == null || s.loopEndBar == null) return
        _state.update { it.copy(isLooping = !it.isLooping) }
    }

    // endregion

    // region Edit mode

    fun setEditMode(mode: NoteEditMode) {
        _state.update { it.copy(editMode = mode, selectedNoteId = null) }
        if (mode == NoteEditMode.EDIT) stopPlaying()
    }

    fun selectNote(id: String?) {
        _state.update { it.copy(selectedNoteId = id) }
    }

    fun deleteNote(id: String) {
        _state.update { current ->
            current.copy(
                playedNotes = current.playedNotes.filter { it.id != id },
                selectedNoteId = null
            )
        }
    }

    fun moveNote(id: String, deltaMidi: Int, deltaMs: Long) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val newMidi = (note.midiNumber + deltaMidi).coerceIn(21, 108)
            val noteIdx = ((newMidi % 12) + 12) % 12
            val newOctave = (newMidi / 12) - 1
            val newNoteName = NoteUtils.NOTE_NAMES[noteIdx]
            val newNote = note.copy(
                noteName = newNoteName,
                octave = newOctave,
                midiNumber = newMidi,
                startTimeMs = (note.startTimeMs + deltaMs).coerceAtLeast(0L),
                endTimeMs = note.endTimeMs?.let { (it + deltaMs).coerceAtLeast(note.startTimeMs + 1L) }
            )
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) newNote else it })
        }
    }

    fun extendNoteStart(id: String, deltaMs: Long) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val endMs = note.endTimeMs ?: return@update current
            val newStart = (note.startTimeMs + deltaMs).coerceIn(0L, endMs - 1L)
            val newNote = note.copy(startTimeMs = newStart)
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) newNote else it })
        }
    }

    fun extendNoteEnd(id: String, deltaMs: Long) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val newEnd = ((note.endTimeMs ?: 0L) + deltaMs).coerceAtLeast(note.startTimeMs + 1L)
            val newNote = note.copy(endTimeMs = newEnd)
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) newNote else it })
        }
    }

    fun commitNoteEdit(id: String) {
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val endMs = note.endTimeMs ?: return@update current
            val voice = note.voiceIndex
            val filtered = current.playedNotes.filter { other ->
                other.id == id || other.voiceIndex != voice ||
                other.endTimeMs == null || other.endTimeMs <= note.startTimeMs || other.startTimeMs >= endMs
            }
            current.copy(playedNotes = filtered)
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
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
            }
        }
    }

    fun importFromMidi(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val multiTrack = MidiImporter.parse(inputStream)
                val (notes, bpmBeats) = MidiImporter.convertToPlayedNotes(multiTrack)
                val (importedBpm, importedBeatsPerBar) = bpmBeats
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
                    .coerceAtLeast(ABSOLUTE_MIN_MIDI)
                val viewMax = (viewMin + range.coerceAtLeast(MIN_AUTO_FIT_RANGE))
                    .coerceAtMost(ABSOLUTE_MAX_MIDI)

                _state.update { current ->
                    FreePlayState(
                        isListening = current.isListening,
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
                        midiAvailable = current.midiAvailable,
                        midiInputEnabled = current.midiInputEnabled,
                        midiOutputDeviceName = current.midiOutputDeviceName,
                        outputDevice = current.outputDevice
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
            }
        }
    }

    // endregion

    private fun gridIntervalMs(): Long {
        val s = _state.value
        return ((60_000.0 / s.bpm) * (4.0 / s.minNoteDivision)).toLong().coerceAtLeast(1L)
    }

    private fun snapToGrid(timeMs: Long): Long {
        val interval = gridIntervalMs()
        val snapped = (timeMs.toDouble() / interval).toLong() * interval
        return snapped.coerceAtLeast(0L)
    }


    fun onHorizontalZoom(scaleFactor: Float, focusFraction: Float) {
        _state.update { current ->
            val range = current.viewMaxMidi - current.viewMinMidi
            val newRange = (range / scaleFactor).coerceIn(MIN_VISIBLE_KEYS, MAX_VISIBLE_KEYS)
            val focusMidi = current.viewMinMidi + range * focusFraction
            var newMin = focusMidi - newRange * focusFraction
            var newMax = focusMidi + newRange * (1f - focusFraction)
            if (newMin < ABSOLUTE_MIN_MIDI) {
                newMin = ABSOLUTE_MIN_MIDI
                newMax = (newMin + newRange).coerceAtMost(ABSOLUTE_MAX_MIDI)
            }
            if (newMax > ABSOLUTE_MAX_MIDI) {
                newMax = ABSOLUTE_MAX_MIDI
                newMin = (newMax - newRange).coerceAtLeast(ABSOLUTE_MIN_MIDI)
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
            if (newMin < ABSOLUTE_MIN_MIDI) {
                newMin = ABSOLUTE_MIN_MIDI
                newMax = newMin + range
            }
            if (newMax > ABSOLUTE_MAX_MIDI) {
                newMax = ABSOLUTE_MAX_MIDI
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

    private fun processResult(result: PitchResult) {
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
                        averageCentsOff = result.centsOff,
                        centsHistory = listOf(result.centsOff),
                        voiceIndex = _state.value.activeVoiceIndex
                    )
                    _state.update { it.copy(activeNote = note, pitchResult = result) }
                } else {
                    _state.update { it.copy(pitchResult = result) }
                }
            } else {
                centsSamples.add(result.centsOff)
                val avgCents = centsSamples.average().toFloat()
                _state.update { current ->
                    current.copy(
                        activeNote = current.activeNote?.copy(
                            averageCentsOff = avgCents,
                            centsHistory = current.activeNote.centsHistory + result.centsOff
                        ),
                        pitchResult = result
                    )
                }
            }
        } else {
            if (currentNoteMidi != null) {
                finalizeCurrentNote()
                _state.update { it.copy(activeNote = null, pitchResult = result) }
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
                    val avgCents = centsSamples.average().toFloat()
                    _state.update { current ->
                        current.copy(
                            activeNote = current.activeNote?.copy(
                                averageCentsOff = avgCents,
                                centsHistory = current.activeNote.centsHistory + result.centsOff
                            ),
                            pitchResult = result
                        )
                    }
                    if (elapsed >= gridIntervalMs()) {
                        commitHoning(result)
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
                    val avgCents = centsSamples.average().toFloat()
                    _state.update { current ->
                        current.copy(
                            activeNote = current.activeNote?.copy(
                                averageCentsOff = avgCents,
                                centsHistory = current.activeNote.centsHistory + result.centsOff
                            ),
                            pitchResult = result
                        )
                    }
                }
            }
        } else {
            if (honingActive) {
                // Silence before honing window completed: discard partial data
                abortHoning()
                _state.update { it.copy(activeNote = null, pitchResult = result) }
            } else if (currentNoteMidi != null) {
                finalizeCurrentNote()
                _state.update { it.copy(activeNote = null, pitchResult = result) }
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
                averageCentsOff = result.centsOff,
                centsHistory = listOf(result.centsOff),
                voiceIndex = _state.value.activeVoiceIndex
            )
            _state.update { it.copy(activeNote = note, pitchResult = result) }
        } else {
            _state.update { it.copy(pitchResult = result) }
        }
    }

    private fun commitHoning(result: PitchResult) {
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
                averageCentsOff = centsSamples.average().toFloat(),
                centsHistory = centsSamples.toList(),
                voiceIndex = _state.value.activeVoiceIndex
            )
            _state.update { it.copy(activeNote = note, pitchResult = result) }
        } else {
            _state.update { it.copy(pitchResult = result) }
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
            val finalNote = activeNote.copy(endTimeMs = snappedEnd, averageCentsOff = centsSamples.average().toFloat())

            // Re-record: delete notes in same voice that overlap the new note
            val voice = finalNote.voiceIndex
            val newStart = finalNote.startTimeMs
            val newEnd = snappedEnd
            _state.update { current ->
                val filtered = (current.playedNotes + finalNote).filter { other ->
                    other.id == finalNote.id || other.voiceIndex != voice ||
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
        val isRecording = _state.value.isRecording
        when (event) {
            is MidiNoteEvent.NoteOn -> {
                val midiNote = event.midiNote
                // Ignore duplicate note-on for an already-held note
                if (activeMidiNotes.containsKey(midiNote)) return

                val noteIndex = ((midiNote % 12) + 12) % 12
                val octave = (midiNote / 12) - 1
                val noteName = NoteUtils.NOTE_NAMES[noteIndex]
                expandRange(midiNote)

                if (isRecording && _state.value.songPositionMs >= 0) {
                    val note = PlayedNote(
                        noteName = noteName,
                        octave = octave,
                        midiNumber = midiNote,
                        startTimeMs = _state.value.songPositionMs,
                        voiceIndex = _state.value.activeVoiceIndex
                    )
                    activeMidiNotes[midiNote] = note
                    _state.update { current ->
                        current.copy(
                            playedNotes = current.playedNotes + note,
                            activeNote = note
                        )
                    }
                } else {
                    // Still track for activeNote display but don't write to score
                    val note = PlayedNote(
                        noteName = noteName,
                        octave = octave,
                        midiNumber = midiNote,
                        startTimeMs = _state.value.songPositionMs,
                        voiceIndex = _state.value.activeVoiceIndex
                    )
                    activeMidiNotes[midiNote] = note
                    _state.update { it.copy(activeNote = note) }
                }
            }

            is MidiNoteEvent.NoteOff -> {
                val midiNote = event.midiNote
                val activeNote = activeMidiNotes.remove(midiNote) ?: return
                val endMs = _state.value.songPositionMs

                if (!isRecording || activeNote.startTimeMs < 0) {
                    _state.update { it.copy(activeNote = activeMidiNotes.values.lastOrNull()) }
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
        }
    }

    fun replayNotes() {
        replayJob?.cancel()

        val allNotes = _state.value.playedNotes
            .filter { it.endTimeMs != null }
            .sortedBy { it.startTimeMs }

        val firstNoteMs = allNotes.firstOrNull()?.startTimeMs ?: 0L
        val lastNoteEndMs = allNotes.maxOfOrNull { it.endTimeMs!! } ?: 0L

        if (allNotes.isNotEmpty() && _state.value.songPositionMs >= lastNoteEndMs) {
            _state.update { it.copy(songPositionMs = firstNoteMs) }
        }

        replayActive = true
        _state.update { it.copy(isPlaying = true) }

        replayJob = viewModelScope.launch {
            val job = coroutineContext[kotlinx.coroutines.Job]!!

            val player: NotePlayer? = when (_state.value.outputDevice) {
                OutputDevice.MIDI -> if (midiOutputSender.isReady) midiOutputSender else melodySynthesizer
                OutputDevice.SYNTH -> melodySynthesizer
                OutputDevice.NONE -> null
            }

            val s = _state.value
            val barMs = (s.beatsPerBar * (60000.0 / s.bpm)).toLong()
            val loopEndMs = if (s.loopEndBar != null) {
                s.loopEndBar * barMs
            } else {
                lastNoteEndMs + 4 * barMs
            }
            val loopStartMs = if (s.loopStartBar != null) {
                s.loopStartBar * barMs
            } else {
                firstNoteMs
            }

            data class Event(val timeMs: Long, val midiNote: Int, val isOn: Boolean)

            fun buildEvents(startTime: Long): List<Event> {
                val evts = mutableListOf<Event>()
                for (note in allNotes) {
                    val endMs = note.endTimeMs!!
                    if (endMs <= startTime) continue
                    val onTime = if (note.startTimeMs <= startTime) startTime else note.startTimeMs
                    evts.add(Event(onTime, note.midiNumber, true))
                    evts.add(Event(endMs, note.midiNumber, false))
                }
                evts.sortBy { it.timeMs }
                return evts
            }

            var replayPositionMs = _state.value.songPositionMs
            var events = buildEvents(replayPositionMs)
            var eventIndex = 0
            var lastWallMs = System.currentTimeMillis()

            while (job.isActive) {
                // Detect external seek (from onVerticalScroll)
                val statePosition = _state.value.songPositionMs
                if (kotlin.math.abs(statePosition - replayPositionMs) > 100) {
                    player?.allNotesOff()
                    replayPositionMs = statePosition.coerceAtLeast(firstNoteMs)
                    events = buildEvents(replayPositionMs)
                    eventIndex = 0
                    lastWallMs = System.currentTimeMillis()
                }

                val wallNow = System.currentTimeMillis()
                replayPositionMs += (wallNow - lastWallMs).coerceAtLeast(0L)
                lastWallMs = wallNow
                _state.update { it.copy(songPositionMs = replayPositionMs) }

                // Fire due events
                while (eventIndex < events.size && events[eventIndex].timeMs <= replayPositionMs) {
                    val ev = events[eventIndex++]
                    if (ev.isOn) player?.noteOn(0, ev.midiNote, 80)
                    else player?.noteOff(0, ev.midiNote)
                }

                // Loop-at-end
                if (replayPositionMs >= loopEndMs) {
                    player?.allNotesOff()
                    replayPositionMs = loopStartMs
                    _state.update { it.copy(songPositionMs = replayPositionMs) }
                    events = buildEvents(replayPositionMs)
                    eventIndex = 0
                    lastWallMs = System.currentTimeMillis()
                }

                delay(16L)
            }

            player?.allNotesOff()
            replayActive = false
            lastAdvanceWallMs = System.currentTimeMillis()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun stopPlaying() {
        replayJob?.cancel()
        replayJob = null
        replayActive = false
        lastAdvanceWallMs = System.currentTimeMillis()
        midiOutputSender.allNotesOff()
        if (melodySynthesizer.isReady) melodySynthesizer.allNotesOff()
        _state.update { it.copy(isPlaying = false) }
    }

    fun togglePlaying() {
        if (_state.value.isPlaying) stopPlaying()
        else replayNotes()
    }


    private fun expandRange(midi: Int) {
        _state.update { current ->
            val newViewMin = min(current.viewMinMidi, midi.toFloat() - RANGE_PADDING).coerceAtLeast(ABSOLUTE_MIN_MIDI)
            val newViewMax = max(current.viewMaxMidi, midi.toFloat() + RANGE_PADDING).coerceAtMost(ABSOLUTE_MAX_MIDI)
            current.copy(viewMinMidi = newViewMin, viewMaxMidi = newViewMax)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
        replayJob?.cancel()
    }
}
