package com.perfectpitch.ui.freeplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectpitch.audio.PitchProcessor
import com.perfectpitch.data.SettingsRepository
import com.perfectpitch.music.PitchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class FreePlayViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FreePlayState())
    val state: StateFlow<FreePlayState> = _state.asStateFlow()

    private var listeningJob: Job? = null
    private var lastNoteName: String? = null
    private var totalCentsOff = 0f
    private var noteCount = 0

    companion object {
        const val MAX_HISTORY = 20
    }

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setReferenceA4(settings.concertPitch)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setYinThreshold(settings.yinThreshold)
            }
        }
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
        _state.update { it.copy(isListening = false) }
    }

    private fun processResult(result: PitchResult) {
        _state.update { it.copy(pitchResult = result) }

        if (result.isValid) {
            val currentNote = "${result.noteName}${result.octave}"
            if (currentNote != lastNoteName) {
                lastNoteName = currentNote
                addNoteToHistory(result)
            }
        } else {
            lastNoteName = null
        }
    }

    private fun addNoteToHistory(result: PitchResult) {
        noteCount++
        totalCentsOff += abs(result.centsOff)

        val entry = NoteHistoryEntry(
            noteName = result.noteName,
            octave = result.octave,
            centsOff = result.centsOff,
            timestamp = System.currentTimeMillis()
        )

        val perfectCount = _state.value.sessionStats.perfectNotes +
            if (abs(result.centsOff) <= 10f) 1 else 0
        val goodCount = _state.value.sessionStats.goodNotes +
            if (abs(result.centsOff) in 10f..25f) 1 else 0

        _state.update { current ->
            val updatedHistory = (listOf(entry) + current.noteHistory).take(MAX_HISTORY)
            current.copy(
                noteHistory = updatedHistory,
                sessionStats = SessionStats(
                    totalNotes = noteCount,
                    perfectNotes = perfectCount,
                    goodNotes = goodCount,
                    averageCentsOff = totalCentsOff / noteCount
                )
            )
        }
    }

    fun resetSession() {
        noteCount = 0
        totalCentsOff = 0f
        lastNoteName = null
        _state.update { it.copy(noteHistory = emptyList(), sessionStats = SessionStats()) }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
