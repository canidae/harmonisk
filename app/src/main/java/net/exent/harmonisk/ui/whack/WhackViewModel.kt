package net.exent.harmonisk.ui.whack

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.createProfileWithInstrument
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import javax.inject.Inject

data class WhackTarget(val id: Long, val hole: WhackHole, val emoji: WhackEmoji, val shownAt: Long)

data class WhackHammer(val id: Long, val hole: WhackHole, val hit: Boolean)

data class WhackScored(val id: Long, val hole: WhackHole, val emoji: WhackEmoji, val points: Int)

data class WhackLevelUp(val id: Long, val level: Int)

data class WhackState(
    val instrument: Instrument = Instrument.VIOLIN,
    val playing: Boolean = false,
    val finished: Boolean = false,
    val level: Int = 1,
    val hits: Int = 0,
    val score: Int = 0,
    val best: Int = 0,
    val remainingMs: Long = WhackRules.GAME_MS,
    val durationMs: Long = WhackRules.GAME_MS,
    val target: WhackTarget? = null,
    val hammer: WhackHammer? = null,
    val scored: WhackScored? = null,
    val levelUp: WhackLevelUp? = null
)

@HiltViewModel
class WhackViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val whackRepository: WhackRepository
) : ViewModel() {

    companion object {
        private const val STABLE_FRAMES = 2
        private const val SILENT_FRAMES = 3
    }

    private val _state = MutableStateFlow(WhackState())
    val state: StateFlow<WhackState> = _state.asStateFlow()

    val profiles: StateFlow<List<ProfileRepository.Profile>> = profileRepository.profiles
    val activeProfile: StateFlow<ProfileRepository.Profile?> = profileRepository.activeProfile

    private data class Judged(val targetId: Long, val judgement: WhackJudgement)

    private val judgements = Channel<Judged>(Channel.BUFFERED)

    private var holes: List<WhackHole> = whackHoles(Instrument.VIOLIN)
    private var gameJob: Job? = null
    private var timerJob: Job? = null
    private var listenJob: Job? = null
    private var armedMidi = -1
    private var runMidi = -1
    private var runFrames = 0
    private var silentFrames = 0
    private var nextId = 1L
    private var previousEmoji: WhackEmoji? = null
    private var instrumentOverride: Instrument? = null
    private var best = 0
    private var persistBest = true
    private var wrongsMutedUntil = 0L

    fun setInstrumentOverride(instrument: Instrument?) {
        instrumentOverride = instrument
        if (instrument != null) applyInstrument(instrument)
    }

    private fun applyInstrument(instrument: Instrument) {
        holes = whackHoles(instrument)
        pitchProcessor.setInstrument(instrument)
        _state.update { it.copy(instrument = instrument) }
    }

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val instrument = instrumentOverride ?: Instrument.fromName(settings.instrumentName)
                applyInstrument(instrument)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setReferenceA4(settings.concertPitchA4)
            }
        }
        viewModelScope.launch {
            profileRepository.activeProfile.collect { profile ->
                best = profile?.let { whackRepository.best(it.id) } ?: 0
                _state.update { it.copy(best = best) }
            }
        }
        viewModelScope.launch {
            profileRepository.activeProfile
                .map { it?.id }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (_state.value.playing) stop() }
        }
    }

    fun start(
        durationMs: Long = _state.value.durationMs,
        persistBest: Boolean = this.persistBest
    ) {
        if (_state.value.playing) return
        reset(durationMs, persistBest)
        resetPitch()
        while (judgements.tryReceive().isSuccess) Unit
        previousEmoji = null
        _state.update { it.copy(playing = true, finished = false, best = best) }
        startListening()
        gameJob = viewModelScope.launch {
            delay(WhackRules.START_MS)
            playTurns()
        }
        timerJob = viewModelScope.launch {
            val end = SystemClock.elapsedRealtime() + durationMs
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                _state.update { it.copy(remainingMs = left.coerceAtLeast(0)) }
                if (left <= 0) break
                delay(250)
            }
            finish()
        }
    }

    fun reset(
        durationMs: Long = _state.value.durationMs,
        persistBest: Boolean = this.persistBest
    ) {
        stop()
        this.persistBest = persistBest
        _state.update {
            it.copy(
                level = 1,
                hits = 0,
                score = 0,
                remainingMs = durationMs,
                durationMs = durationMs,
                target = null,
                hammer = null,
                scored = null,
                levelUp = null
            )
        }
    }

    fun selectProfile(id: String) {
        profileRepository.selectProfile(id)
    }

    fun createProfile(name: String, emoji: String, instrument: Instrument): Boolean =
        createProfileWithInstrument(viewModelScope, profileRepository, settingsRepository, name, emoji, instrument)

    fun stop() {
        gameJob?.cancel()
        gameJob = null
        timerJob?.cancel()
        timerJob = null
        stopListening()
        _state.update {
            it.copy(
                playing = false,
                finished = false,
                level = 1,
                hits = 0,
                score = 0,
                remainingMs = it.durationMs,
                target = null,
                hammer = null,
                scored = null,
                levelUp = null
            )
        }
    }

    private fun finish() {
        gameJob?.cancel()
        gameJob = null
        timerJob?.cancel()
        timerJob = null
        stopListening()
        _state.update {
            it.copy(
                playing = false,
                finished = true,
                remainingMs = 0,
                target = null,
                hammer = null,
                levelUp = null
            )
        }
    }

    override fun onCleared() {
        stopListening()
    }

    private fun startListening() {
        if (listenJob != null) return
        listenJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { handlePitch(it) }
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
    }

    private fun resetPitch() {
        armedMidi = -1
        runMidi = -1
        runFrames = 0
        silentFrames = 0
    }

    private fun handlePitch(result: PitchResult) {
        if (result.isSilent) {
            runFrames = 0
            if (++silentFrames >= SILENT_FRAMES) {
                armedMidi = -1
                runMidi = -1
            }
            return
        }
        silentFrames = 0
        val midi = result.midiNoteNumber
        if (midi == runMidi) runFrames++ else {
            runMidi = midi
            runFrames = 1
        }
        if (runFrames < STABLE_FRAMES || midi == armedMidi) return
        armedMidi = midi
        val current = _state.value
        val target = current.target ?: return
        val judgement = judgeNote(midi, target.hole, visibleHoles(current.level)) ?: return
        if (judgement is WhackJudgement.Wrong && SystemClock.elapsedRealtime() < wrongsMutedUntil) return
        judgements.trySend(Judged(target.id, judgement))
    }

    private fun visibleHoles(level: Int): List<WhackHole> = whackVisibleHoles(holes, level)

    private suspend fun playTurns() {
        while (true) {
            while (judgements.tryReceive().isSuccess) Unit
            val current = _state.value
            val visible = visibleHoles(current.level)
            val emoji = WhackEmoji.pick(previousEmoji)
            previousEmoji = emoji
            val target = WhackTarget(nextId++, visible.random(), emoji, SystemClock.elapsedRealtime())
            wrongsMutedUntil = SystemClock.elapsedRealtime() + WhackRules.EMOJI_RISE_MS
            _state.update { it.copy(target = target, hammer = null, scored = null) }
            awaitJudgement(target)
            _state.update { it.copy(target = null) }
            if (_state.value.levelUp != null) {
                delay(WhackRules.LEVEL_UP_MS)
                _state.update { it.copy(levelUp = null) }
            } else {
                delay(WhackRules.HIT_GAP_MS)
            }
        }
    }

    private suspend fun awaitJudgement(target: WhackTarget) {
        while (true) {
            val judged = judgements.receive()
            if (judged.targetId != target.id) continue
            when (val judgement = judged.judgement) {
                is WhackJudgement.Hit -> {
                    score(target)
                    return
                }
                is WhackJudgement.Wrong -> {
                    swingPress(judgement.hole, hit = false)
                    delay(WhackRules.FREEZE_MS)
                    while (judgements.tryReceive().isSuccess) Unit
                }
            }
        }
    }

    private fun swingPress(hole: WhackHole, hit: Boolean) {
        val press = WhackHammer(nextId++, hole, hit)
        _state.update { it.copy(hammer = press, scored = if (hit) it.scored else null) }
        viewModelScope.launch {
            delay(WhackRules.PRESS_MS)
            _state.update { if (it.hammer?.id == press.id) it.copy(hammer = null) else it }
        }
    }

    private fun score(target: WhackTarget) {
        val current = _state.value
        val points = WhackRules.pointsFor(SystemClock.elapsedRealtime() - target.shownAt)
        val score = current.score + points
        val hits = current.hits + 1
        if (score > best) {
            best = score
            if (persistBest) {
                profileRepository.activeProfileId?.let { whackRepository.saveBest(it, score) }
            }
        }
        val level = WhackRules.levelFor(hits)
        swingPress(target.hole, hit = true)
        _state.update {
            it.copy(
                hits = hits,
                score = score,
                best = maxOf(it.best, best),
                level = level,
                scored = WhackScored(nextId++, target.hole, target.emoji, points),
                levelUp = if (level > current.level) WhackLevelUp(nextId++, level) else it.levelUp
            )
        }
    }
}
