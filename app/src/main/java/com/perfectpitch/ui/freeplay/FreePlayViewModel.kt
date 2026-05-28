package com.perfectpitch.ui.freeplay

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import com.perfectpitch.ui.components.PitchFrame
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
    private val melodySynthesizer: MelodySynthesizer
) : ViewModel() {

    companion object {
        const val MIN_NOTE_DURATION_MS = 80L
        const val MIN_VISIBLE_KEYS = 5f
        const val MAX_VISIBLE_KEYS = 48f
        const val MIN_FALL_SPEED = 30f
        const val MAX_FALL_SPEED = 600f
        const val RANGE_PADDING = 2
        const val ABSOLUTE_MIN_MIDI = 21f  // A0
        const val ABSOLUTE_MAX_MIDI = 108f // A8
        const val MIN_AUTO_FIT_RANGE = 12f
        const val NEW_NOTE_DEBOUNCE_FRAMES = 3
        private const val TAG = "FreePlayVM"
    }

    // These two must be declared first so _state can be initialized with sessionBaseMs.
    private var virtualNowMs: Long = System.currentTimeMillis()
    // All bar/loop calculations use (virtualNowMs - sessionBaseMs) rather than the
    // raw Unix timestamp, keeping bar numbers small and 1-based.
    private var sessionBaseMs: Long = virtualNowMs

    private val _state = MutableStateFlow(FreePlayState(sessionStartMs = sessionBaseMs))
    val state: StateFlow<FreePlayState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var midiListeningJob: Job? = null
    private var replayJob: Job? = null
    private var synthOpenJob: Job? = null

    // Replay state flags
    private var replayControlsTime = false
    private var feedbackWarningShown = false
    private var seekToMs: Long? = null
    val warnings = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

    // Mic input tracking
    private var currentNoteMidi: Int? = null
    private var currentNoteStartMs: Long = 0L
    private var centsSamples = mutableListOf<Float>()
    private var candidateNoteMidi: Int? = null
    private var candidateNoteCount: Int = 0
    private var lastRealTimeMs: Long = virtualNowMs

    // MIDI input tracking (polyphonic: key = MIDI note number, value = active PlayedNote)
    private val activeMidiNotes = mutableMapOf<Int, PlayedNote>()

    // Edit state
    private var editingNoteIdForUndo: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
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
                    it.copy(
                        midiAvailable = available,
                        midiInputEnabled = inputEnabled,
                        midiOutputEnabled = outputEnabled
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
                val outputDevice = devices.firstOrNull { it.inputPortCount > 0 }
                _state.update {
                    it.copy(
                        midiInputDeviceName = inputDevice?.properties?.getString(MidiDeviceInfo.PROPERTY_NAME),
                        midiOutputDeviceName = outputDevice?.properties?.getString(MidiDeviceInfo.PROPERTY_NAME)
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

    fun setMidiOutputEnabled(useMidi: Boolean) {
        midiDeviceManager.setOutputOverride(useMidi)
    }

    val continuousPitchLine: StateFlow<Boolean> = settingsRepository.settings
        .map { it.continuousPitchLine }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun getEffectiveTime(realTimeMs: Long): Long {
        val delta = (realTimeMs - lastRealTimeMs).coerceAtLeast(0L)
        lastRealTimeMs = realTimeMs
        if (replayControlsTime) return virtualNowMs
        // Time advances when an active mic/MIDI note is detected
        val shouldAdvance = currentNoteMidi != null || activeMidiNotes.isNotEmpty()
        if (shouldAdvance) {
            virtualNowMs += delta
        }
        return virtualNowMs
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
            val finalized = note.copy(endTimeMs = virtualNowMs)
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
        candidateNoteMidi = null
        candidateNoteCount = 0
        _state.update { it.copy(isListening = false, activeNote = null) }
    }

    fun toggleRecording() {
        _state.update { it.copy(isRecording = !it.isRecording) }
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
        val currentBar = ((virtualNowMs - sessionBaseMs) / barMs).toInt()
        _state.update { it.copy(loopStartBar = currentBar) }
    }

    fun setLoopEnd() {
        val barMs = barDurationMs()
        val currentBar = ((virtualNowMs - sessionBaseMs) / barMs).toInt() + 1
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
        editingNoteIdForUndo = null
        if (mode == NoteEditMode.EDIT) stopReplay()
    }

    fun selectNote(id: String?) {
        _state.update { it.copy(selectedNoteId = id) }
    }

    fun deleteNote(id: String) {
        pushUndo()
        _state.update { current ->
            current.copy(
                playedNotes = current.playedNotes.filter { it.id != id },
                selectedNoteId = null
            )
        }
    }

    fun moveNote(id: String, deltaMidi: Int, deltaMs: Long) {
        if (editingNoteIdForUndo != id) {
            pushUndo()
            editingNoteIdForUndo = id
        }
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
        if (editingNoteIdForUndo != id) {
            pushUndo()
            editingNoteIdForUndo = id
        }
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val endMs = note.endTimeMs ?: return@update current
            val newStart = (note.startTimeMs + deltaMs).coerceIn(0L, endMs - 1L)
            val newNote = note.copy(startTimeMs = newStart)
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) newNote else it })
        }
    }

    fun extendNoteEnd(id: String, deltaMs: Long) {
        if (editingNoteIdForUndo != id) {
            pushUndo()
            editingNoteIdForUndo = id
        }
        _state.update { current ->
            val note = current.playedNotes.find { it.id == id } ?: return@update current
            val newEnd = ((note.endTimeMs ?: 0L) + deltaMs).coerceAtLeast(note.startTimeMs + 1L)
            val newNote = note.copy(endTimeMs = newEnd)
            current.copy(playedNotes = current.playedNotes.map { if (it.id == id) newNote else it })
        }
    }

    fun commitNoteEdit(id: String) {
        editingNoteIdForUndo = null
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

    fun undo() {
        _state.update { current ->
            if (current.undoStack.isEmpty()) return@update current
            val snapshot = current.undoStack.last()
            current.copy(
                playedNotes = snapshot,
                undoStack = current.undoStack.dropLast(1),
                selectedNoteId = null
            )
        }
    }

    private fun pushUndo() {
        _state.update { current ->
            val newStack = (current.undoStack + listOf(current.playedNotes)).takeLast(20)
            current.copy(undoStack = newStack)
        }
    }

    // endregion

    // region Import / Export

    fun exportToMidi(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = _state.value
                val base = sessionBaseMs
                // Normalize note timestamps to be session-relative (0-based) so tick values
                // fit in standard 4-byte variable-length MIDI encoding.
                val relativeNotes = s.playedNotes.map { note ->
                    note.copy(
                        startTimeMs = note.startTimeMs - base,
                        endTimeMs = note.endTimeMs?.minus(base)
                    )
                }
                val bytes = MidiWriter().write(relativeNotes, s.bpm, s.beatsPerBar)
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

                // Imported notes have 0-based timestamps; set sessionBase = 0 so bar
                // numbers stay small and loop positions work correctly.
                val newBase = 0L
                sessionBaseMs = newBase
                val maxMs = notes.maxOfOrNull { it.endTimeMs ?: it.startTimeMs } ?: 0L
                val barMs = importedBeatsPerBar * (60000.0 / importedBpm)
                virtualNowMs = maxMs + barMs.toLong()
                lastRealTimeMs = System.currentTimeMillis()

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
                        sessionStartMs = newBase,
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
                        midiOutputEnabled = current.midiOutputEnabled
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
        return ((timeMs.toDouble() / interval).toLong()) * interval
    }

    fun scrollToTop() {
        _state.update { it.copy(timeOffsetMs = 0) }
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
        val beatMs = (60_000.0 / _state.value.bpm).toLong()
        val minNowMs = sessionBaseMs - 2 * beatMs
        val maxTimeOffset = (virtualNowMs - minNowMs).coerceAtLeast(0L)
        if (deltaMs > 0L && _state.value.isReplaying) {
            seekToMs = (virtualNowMs - _state.value.timeOffsetMs - deltaMs).coerceAtLeast(minNowMs)
        } else {
            _state.update { current ->
                current.copy(timeOffsetMs = (current.timeOffsetMs + deltaMs).coerceIn(0L, maxTimeOffset))
            }
            if (deltaMs > 0L && !_state.value.isReplaying && _state.value.playedNotes.any { it.endTimeMs != null }) {
                replayNotes()
            }
        }
    }

    private fun processResult(result: PitchResult) {
        if (result.isValid) {
            val midi = result.midiNoteNumber
            val traceFrame = PitchFrame(timeMs = virtualNowMs, midiNote = midi, centsOff = result.centsOff, frequency = result.frequency)
            val isNewNote = currentNoteMidi == null || midi != currentNoteMidi

            if (isNewNote) {
                if (midi == candidateNoteMidi) {
                    candidateNoteCount++
                } else {
                    candidateNoteMidi = midi
                    candidateNoteCount = 1
                }
                if (candidateNoteCount < NEW_NOTE_DEBOUNCE_FRAMES) {
                    _state.update { it.copy(pitchTrace = trimTrace(it.pitchTrace + traceFrame)) }
                    return
                }
                candidateNoteMidi = null
                candidateNoteCount = 0
                finalizeCurrentNote()
                currentNoteMidi = midi
                centsSamples.clear()
                centsSamples.add(result.centsOff)
                val noteStart = virtualNowMs
                val snappedStart = if (_state.value.snapEnabled) snapToGrid(noteStart) else noteStart
                currentNoteStartMs = snappedStart
                expandRange(midi)
                if (_state.value.isRecording && virtualNowMs >= sessionBaseMs) {
                    val note = PlayedNote(
                        noteName = result.noteName,
                        octave = result.octave,
                        midiNumber = midi,
                        startTimeMs = snappedStart,
                        averageCentsOff = result.centsOff,
                        centsHistory = listOf(result.centsOff),
                        voiceIndex = _state.value.activeVoiceIndex
                    )
                    _state.update {
                        it.copy(activeNote = note, pitchResult = result, pitchTrace = trimTrace(it.pitchTrace + traceFrame.copy(timeMs = snappedStart)))
                    }
                } else {
                    _state.update { it.copy(pitchResult = result, pitchTrace = trimTrace(it.pitchTrace + traceFrame)) }
                }
            } else {
                candidateNoteMidi = null
                candidateNoteCount = 0
                centsSamples.add(result.centsOff)
                val avgCents = centsSamples.average().toFloat()
                _state.update { current ->
                    current.copy(
                        activeNote = current.activeNote?.copy(averageCentsOff = avgCents, centsHistory = current.activeNote.centsHistory + result.centsOff),
                        pitchResult = result,
                        pitchTrace = trimTrace(current.pitchTrace + traceFrame)
                    )
                }
            }
        } else {
            if (currentNoteMidi != null) {
                finalizeCurrentNote()
                _state.update { it.copy(activeNote = null, pitchResult = result) }
            }
            candidateNoteMidi = null
            candidateNoteCount = 0
        }
    }

    private fun trimTrace(trace: List<PitchFrame>): List<PitchFrame> {
        val cutoffMs = virtualNowMs - 120_000L
        return if (trace.size > 100 && trace.first().timeMs < cutoffMs) {
            trace.dropWhile { it.timeMs < cutoffMs }
        } else trace
    }

    private fun finalizeCurrentNote() {
        val midi = currentNoteMidi ?: return
        val now = virtualNowMs
        val duration = now - currentNoteStartMs
        if (duration >= MIN_NOTE_DURATION_MS && centsSamples.isNotEmpty()) {
            val activeNote = _state.value.activeNote
            if (activeNote != null) {
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

                if (isRecording && virtualNowMs >= sessionBaseMs) {
                    val note = PlayedNote(
                        noteName = noteName,
                        octave = octave,
                        midiNumber = midiNote,
                        startTimeMs = virtualNowMs,
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
                        startTimeMs = virtualNowMs,
                        voiceIndex = _state.value.activeVoiceIndex
                    )
                    activeMidiNotes[midiNote] = note
                    _state.update { it.copy(activeNote = note) }
                }
            }

            is MidiNoteEvent.NoteOff -> {
                val midiNote = event.midiNote
                val activeNote = activeMidiNotes.remove(midiNote) ?: return
                val endMs = virtualNowMs
                val duration = endMs - activeNote.startTimeMs

                if (!isRecording) {
                    _state.update { it.copy(activeNote = activeMidiNotes.values.lastOrNull()) }
                    return
                }

                if (duration < MIN_NOTE_DURATION_MS) {
                    // Too short — discard
                    _state.update { current ->
                        current.copy(
                            playedNotes = current.playedNotes.filter { it.id != activeNote.id },
                            activeNote = activeMidiNotes.values.lastOrNull()
                        )
                    }
                } else {
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
    }

    /**
     * Start advancing time and playing back notes through the synthesizer/MIDI output.
     * Time advances from the current visual position (honoring [FreePlayState.timeOffsetMs]).
     * Notes already past the playhead are skipped; in-progress notes fire NoteOn immediately.
     * Overdub recording (new notes via mic/MIDI) remains active during playback.
     */
    fun replayNotes() {
        replayJob?.cancel()

        // Commit visual scroll offset so playback starts from where the user is looking
        val offset = _state.value.timeOffsetMs
        if (offset > 0L) {
            virtualNowMs = (virtualNowMs - offset).coerceAtLeast(sessionBaseMs)
            _state.update { it.copy(timeOffsetMs = 0) }
        }

        val allNotes = _state.value.playedNotes
            .filter { it.endTimeMs != null }
            .sortedBy { it.startTimeMs }

        if (allNotes.isEmpty()) {
            return
        }

        val firstNoteMs = allNotes.first().startTimeMs

        // If all notes are in the past (or we're past the last note), rewind to start
        val lastNoteEndMs = allNotes.maxOf { it.endTimeMs!! }
        if (virtualNowMs >= lastNoteEndMs) {
            virtualNowMs = firstNoteMs
        }

        replayControlsTime = true
        _state.update { it.copy(isReplaying = true) }

        // One-time feedback warning: mic input + built-in speaker
        if (!_state.value.midiInputEnabled && !feedbackWarningShown) {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val hasBuiltinSpeaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (hasBuiltinSpeaker) {
                feedbackWarningShown = true
                warnings.tryEmit("Using mic input with speaker output may cause feedback — use a headset or MIDI device for best results")
            }
        }

        replayJob = viewModelScope.launch {
            val job = coroutineContext[kotlinx.coroutines.Job]!!

            val player: NotePlayer = if (_state.value.midiOutputEnabled && midiOutputSender.isReady) {
                midiOutputSender
            } else {
                melodySynthesizer
            }

            val s = _state.value
            val barMs = (s.beatsPerBar * (60000.0 / s.bpm)).toLong()
            // Determine loop boundary: end-loop if set, else lastNoteEndMs + 4 bars
            val loopEndMs = if (s.loopEndBar != null) {
                sessionBaseMs + s.loopEndBar * barMs
            } else {
                lastNoteEndMs + 4 * barMs
            }
            val loopStartMs = if (s.loopStartBar != null) {
                sessionBaseMs + s.loopStartBar * barMs
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

            var events = buildEvents(virtualNowMs)
            var eventIndex = 0
            var lastWallMs = System.currentTimeMillis()

            while (job.isActive) {
                // Handle seek request from scroll
                seekToMs?.let { target ->
                    seekToMs = null
                    player.allNotesOff()
                    virtualNowMs = target.coerceAtLeast(firstNoteMs)
                    events = buildEvents(virtualNowMs)
                    eventIndex = 0
                    lastWallMs = System.currentTimeMillis()
                }

                val wallNow = System.currentTimeMillis()
                virtualNowMs += (wallNow - lastWallMs).coerceAtLeast(0L)
                lastWallMs = wallNow

                // Fire due events
                while (eventIndex < events.size && events[eventIndex].timeMs <= virtualNowMs) {
                    val ev = events[eventIndex++]
                    if (ev.isOn) player.noteOn(0, ev.midiNote, 80)
                    else player.noteOff(0, ev.midiNote)
                }

                // Loop-at-end: reached loopEndMs or ran past last note by 4 bars
                if (virtualNowMs >= loopEndMs) {
                    player.allNotesOff()
                    virtualNowMs = loopStartMs
                    events = buildEvents(virtualNowMs)
                    eventIndex = 0
                    lastWallMs = System.currentTimeMillis()
                }

                delay(16L)
            }

            player.allNotesOff()
            replayControlsTime = false
            lastRealTimeMs = System.currentTimeMillis()
            _state.update { it.copy(isReplaying = false) }
        }
    }

    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        replayControlsTime = false
        lastRealTimeMs = System.currentTimeMillis()
        midiOutputSender.allNotesOff()
        if (melodySynthesizer.isReady) melodySynthesizer.allNotesOff()
        _state.update { it.copy(isReplaying = false) }
    }

    fun toggleReplay() {
        if (_state.value.isReplaying) stopReplay()
        else if (_state.value.playedNotes.isNotEmpty()) replayNotes()
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
