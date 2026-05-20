package com.perfectpitch.ui.freeplay

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.midi.MidiImporter
import com.perfectpitch.midi.MidiWriter
import com.perfectpitch.music.NoteUtils
import com.perfectpitch.music.PitchResult
import com.perfectpitch.ui.components.PitchFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class FreePlayViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository
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
    private var currentNoteMidi: Int? = null
    private var currentNoteStartMs: Long = 0L
    private var centsSamples = mutableListOf<Float>()
    private var candidateNoteMidi: Int? = null
    private var candidateNoteCount: Int = 0
    private var lastRealTimeMs: Long = virtualNowMs
    private var lastFinalizedNoteEndMs: Long = 0L

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
                        timeProgressionEnabled = settings.timeProgressionEnabled,
                        fallSpeedPxPerSec = settings.freeplayFallSpeed,
                        beatsPerBar = settings.freeplayBeatsPerBar,
                        bpm = settings.freeplayBpm,
                        minNoteDivision = settings.freeplayMinNoteDivision,
                        snapEnabled = settings.freeplaySnapEnabled
                    )
                }
            }
        }
    }

    val continuousPitchLine: StateFlow<Boolean> = settingsRepository.settings
        .map { it.continuousPitchLine }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun getEffectiveTime(realTimeMs: Long): Long {
        val delta = (realTimeMs - lastRealTimeMs).coerceAtLeast(0L)
        lastRealTimeMs = realTimeMs
        val shouldAdvance = _state.value.timeProgressionEnabled || currentNoteMidi != null
        if (shouldAdvance) {
            virtualNowMs += delta
            val s = _state.value
            if (s.isLooping && s.loopStartBar != null && s.loopEndBar != null) {
                val barMs = s.beatsPerBar * (60000.0 / s.bpm)
                // Loop positions are relative to sessionBaseMs
                val lStartMs = sessionBaseMs + (s.loopStartBar * barMs).toLong()
                val lEndMs   = sessionBaseMs + (s.loopEndBar   * barMs).toLong()
                if (lEndMs > lStartMs && virtualNowMs >= lEndMs) {
                    virtualNowMs = lStartMs + (virtualNowMs - lEndMs)
                }
            }
        }
        return virtualNowMs
    }

    fun startListening() {
        if (listeningJob?.isActive == true) return
        _state.update { it.copy(isListening = true) }
        listeningJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { result ->
                processResult(result)
            }
        }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        finalizeCurrentNote()
        candidateNoteMidi = null
        candidateNoteCount = 0
        _state.update { it.copy(isListening = false, activeNote = null) }
    }

    fun toggleTimeProgression() {
        val newValue = !_state.value.timeProgressionEnabled
        _state.update { it.copy(timeProgressionEnabled = newValue) }
        viewModelScope.launch { settingsRepository.updateTimeProgressionEnabled(newValue) }
    }

    fun setBeatsPerBar(beats: Int) {
        val clamped = beats.coerceIn(1, 16)
        _state.update { it.copy(beatsPerBar = clamped) }
        viewModelScope.launch { settingsRepository.updateFreeplayBeatsPerBar(clamped) }
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

    fun removeCurrentVoice() {
        _state.update { current ->
            if (current.voiceCount <= 1) return@update current
            val voiceToRemove = current.activeVoiceIndex
            val remappedNotes = current.playedNotes
                .filter { it.voiceIndex != voiceToRemove }
                .map { note ->
                    if (note.voiceIndex > voiceToRemove) note.copy(voiceIndex = note.voiceIndex - 1)
                    else note
                }
            val newVoiceCount = current.voiceCount - 1
            val newActiveVoice = current.activeVoiceIndex.coerceAtMost(newVoiceCount - 1)
            current.copy(
                voiceCount = newVoiceCount,
                activeVoiceIndex = newActiveVoice,
                playedNotes = remappedNotes
            )
        }
    }

    // endregion

    // region Loop

    fun setLoopStart() {
        val s = _state.value
        val barMs = s.beatsPerBar * (60000.0 / s.bpm)
        val currentBar = ((virtualNowMs - sessionBaseMs) / barMs).toInt()
        _state.update { it.copy(loopStartBar = currentBar) }
    }

    fun setLoopEnd() {
        val s = _state.value
        val barMs = s.beatsPerBar * (60000.0 / s.bpm)
        val currentBar = ((virtualNowMs - sessionBaseMs) / barMs).toInt() + 1
        _state.update { it.copy(loopEndBar = currentBar) }
    }

    fun toggleLoop() {
        val s = _state.value
        if (s.loopStartBar == null || s.loopEndBar == null) return
        _state.update { it.copy(isLooping = !it.isLooping) }
    }

    fun clearLoop() {
        _state.update { it.copy(loopStartBar = null, loopEndBar = null, isLooping = false) }
    }

    // endregion

    // region Edit mode

    fun setEditMode(mode: NoteEditMode) {
        _state.update { it.copy(editMode = mode, selectedNoteId = null) }
        editingNoteIdForUndo = null
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
                        timeProgressionEnabled = current.timeProgressionEnabled,
                        fallSpeedPxPerSec = current.fallSpeedPxPerSec,
                        snapEnabled = current.snapEnabled,
                        minNoteDivision = current.minNoteDivision,
                        viewMinMidi = viewMin,
                        viewMaxMidi = viewMax
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

    fun autoFit() {
        _state.update { current ->
            val allNotes = current.playedNotes + listOfNotNull(current.activeNote)
            if (allNotes.isEmpty()) return@update current
            val minNote = allNotes.minOf { it.midiNumber }.toFloat() - RANGE_PADDING
            val maxNote = allNotes.maxOf { it.midiNumber }.toFloat() + RANGE_PADDING
            var range = maxNote - minNote
            var newMin: Float
            var newMax: Float
            if (range < MIN_AUTO_FIT_RANGE) {
                val center = (minNote + maxNote) / 2f
                newMin = center - MIN_AUTO_FIT_RANGE / 2f
                newMax = center + MIN_AUTO_FIT_RANGE / 2f
            } else {
                newMin = minNote
                newMax = maxNote
            }
            if (newMin < ABSOLUTE_MIN_MIDI) {
                newMax += (ABSOLUTE_MIN_MIDI - newMin)
                newMin = ABSOLUTE_MIN_MIDI
            }
            if (newMax > ABSOLUTE_MAX_MIDI) {
                newMin -= (newMax - ABSOLUTE_MAX_MIDI)
                newMax = ABSOLUTE_MAX_MIDI
            }
            newMin = newMin.coerceAtLeast(ABSOLUTE_MIN_MIDI)
            newMax = newMax.coerceAtMost(ABSOLUTE_MAX_MIDI)
            current.copy(viewMinMidi = newMin, viewMaxMidi = newMax)
        }
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

    fun onVerticalScroll(deltaMs: Long) {
        _state.update { current ->
            current.copy(timeOffsetMs = (current.timeOffsetMs + deltaMs).coerceAtLeast(0L))
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
                val wasInSilence = currentNoteMidi == null
                finalizeCurrentNote()
                currentNoteMidi = midi
                centsSamples.clear()
                centsSamples.add(result.centsOff)
                val noteStart = if (!_state.value.timeProgressionEnabled && wasInSilence && lastFinalizedNoteEndMs > 0L) {
                    virtualNowMs = lastFinalizedNoteEndMs
                    lastFinalizedNoteEndMs
                } else {
                    virtualNowMs
                }
                val snappedStart = if (_state.value.snapEnabled) snapToGrid(noteStart) else noteStart
                currentNoteStartMs = snappedStart
                expandRange(midi)
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
                lastFinalizedNoteEndMs = snappedEnd

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

    private fun expandRange(midi: Int) {
        _state.update { current ->
            val newDetectedMin = min(current.detectedMinMidi, midi - RANGE_PADDING)
            val newDetectedMax = max(current.detectedMaxMidi, midi + RANGE_PADDING)
            val newViewMin = min(current.viewMinMidi, midi.toFloat() - RANGE_PADDING).coerceAtLeast(ABSOLUTE_MIN_MIDI)
            val newViewMax = max(current.viewMaxMidi, midi.toFloat() + RANGE_PADDING).coerceAtMost(ABSOLUTE_MAX_MIDI)
            current.copy(detectedMinMidi = newDetectedMin, detectedMaxMidi = newDetectedMax, viewMinMidi = newViewMin, viewMaxMidi = newViewMax)
        }
    }

    fun resetSession() {
        finalizeCurrentNote()
        candidateNoteMidi = null
        candidateNoteCount = 0
        editingNoteIdForUndo = null
        val newBase = System.currentTimeMillis()
        sessionBaseMs = newBase
        virtualNowMs = newBase
        lastRealTimeMs = newBase
        _state.update { current ->
            FreePlayState(
                isListening = current.isListening,
                sessionStartMs = newBase,
                timeProgressionEnabled = current.timeProgressionEnabled,
                fallSpeedPxPerSec = current.fallSpeedPxPerSec,
                beatsPerBar = current.beatsPerBar,
                bpm = current.bpm,
                minNoteDivision = current.minNoteDivision,
                snapEnabled = current.snapEnabled
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
