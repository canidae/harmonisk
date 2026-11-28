package net.exent.harmonisk.ui.freebird

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.data.createProfileWithInstrument
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import kotlin.math.abs
import javax.inject.Inject

@HiltViewModel
class FreeBirdViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val freeBirdRepository: FreeBirdRepository,
    private val pitchProcessor: PitchProcessor
) : ViewModel() {
    private val _instrument = MutableStateFlow(Instrument.VIOLIN)
    val instrument: StateFlow<Instrument> = _instrument.asStateFlow()

    private val _pitch = MutableStateFlow(PitchResult.SILENT)
    val pitch: StateFlow<PitchResult> = _pitch.asStateFlow()

    private val _game = MutableStateFlow(FreeBirdGameState())
    val game: StateFlow<FreeBirdGameState> = _game.asStateFlow()

    val profiles: StateFlow<List<ProfileRepository.Profile>> = profileRepository.profiles
    val activeProfile: StateFlow<ProfileRepository.Profile?> = profileRepository.activeProfile

    private var listenJob: Job? = null
    private var gameJob: Job? = null
    private var best = 0
    private var paused = false
    private var latestPitch = PitchResult.SILENT

    init {
        viewModelScope.launch {
            var lastProfileId: String? = null
            profileRepository.activeProfile.collect { profile ->
                val profileId = profile?.id
                best = profile?.let { freeBirdRepository.best(it.id) } ?: 0
                _game.update { it.copy(best = best) }
                if (lastProfileId != null && lastProfileId != profileId) stopGame()
                lastProfileId = profileId
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val instrument = Instrument.fromName(settings.instrumentName)
                _instrument.value = instrument
                pitchProcessor.setInstrument(instrument)
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                pitchProcessor.setReferenceA4(settings.concertPitchA4)
            }
        }
    }

    fun startGame() {
        gameJob?.cancel()
        paused = false
        startListening()
        best = profileRepository.activeProfileId?.let { freeBirdRepository.best(it) } ?: 0
        _game.value = FreeBirdGameState(best = best, playing = true, timeMs = FREEBIRD_GAME_TIME_MS, level = 1)
        gameJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var last = startedAt
            var lastSpawn = startedAt
            var nextSpawn = startedAt + FREEBIRD_NOTE_INTERVAL_MS
            var feedback: FreeBirdHit? = null
            var feedbackUntil = 0L
            var nextId = 1L
            var level = 1
            var phrase = emptyList<FreeBirdNote>()
            var phraseIndex = 0
            var phrasesLeft = FREEBIRD_PHRASES_PER_LEVEL
            while (isActive) {
                delay(33)
                val now = SystemClock.elapsedRealtime()
                val delta = (now - last).coerceAtLeast(0L)
                last = now
                if (paused) continue
                val state = _game.value
                if (!state.playing) break
                _pitch.value = latestPitch
                if (feedback != null && now >= feedbackUntil) feedback = null
                var timeLeft = state.timeMs - delta
                if (timeLeft <= 0) {
                    _game.update {
                        it.copy(
                            playing = false,
                            finished = true,
                            timeMs = 0,
                            letters = emptyList(),
                            feedback = null
                        )
                    }
                    stopListening()
                    break
                }
                val letters = state.letters.toMutableList()
                if (now >= nextSpawn) {
                    if (phraseIndex >= phrase.size) {
                        if (phrasesLeft <= 0) {
                            if (level < FREEBIRD_MAX_LEVEL) level++
                            phrasesLeft = FREEBIRD_PHRASES_PER_LEVEL
                        }
                        phrase = freeBirdPhrase(_instrument.value, level)
                        phraseIndex = 0
                        phrasesLeft--
                    }
                    val bonus = if (nextId == 1L) now - startedAt + FREEBIRD_BEAK_LEAD_MS.toLong() else now - lastSpawn
                    lastSpawn = now
                    val note = phrase[phraseIndex++]
                    letters += FreeBirdLetter(nextId++, note.midi, note.finger, FREEBIRD_SPAWN_X, bonus)
                    val phraseFinished = phraseIndex >= phrase.size
                    nextSpawn = now +
                        if (phraseFinished) FREEBIRD_PHRASE_GAP_MS else FREEBIRD_NOTE_INTERVAL_MS
                }
                val speed = (FREEBIRD_SPAWN_X - BIRD_PERCH_X) / FREEBIRD_NOTE_TRAVEL_MS
                val next = letters.firstOrNull { !it.judged }
                val remaining = ArrayList<FreeBirdLetter>(letters.size)
                var score = state.score
                for (letter in letters) {
                    val x = letter.x - speed * delta
                    var judged = letter.judged
                    if (!judged) {
                        if (letter.id == next?.id && freeBirdInHitWindow(x)) {
                            val pitch = _pitch.value
                            val targetCents = if (pitch.isSilent) 0f else centsFromTarget(pitch, letter.midi)
                            if (!pitch.isSilent && abs(targetCents) <= FREEBIRD_HIT_CENTS) {
                                val points = pointsForCents(targetCents)
                                score += points
                                timeLeft = timeAfterHit(timeLeft, letter.bonusMs, points)
                                feedback = FreeBirdHit(nextId++, letter.midi, letter.finger, now)
                                feedbackUntil = now + FREEBIRD_FEEDBACK_MS
                                continue
                            }
                        }
                        if (x - FREEBIRD_NOTE_RADIUS < BIRD_BEAK_X - FREEBIRD_HIT_WINDOW) {
                            judged = true
                        }
                    }
                    if (x > -0.12f) {
                        remaining += letter.copy(x = x, judged = judged)
                    }
                }
                if (score > best) {
                    best = score
                    profileRepository.activeProfileId?.let { freeBirdRepository.saveBest(it, score) }
                }
                _game.value = state.copy(
                    score = score,
                    best = best,
                    timeMs = timeLeft,
                    level = level,
                    letters = remaining,
                    feedback = feedback
                )
            }
        }
    }

    fun pauseGame() {
        if (_game.value.playing) paused = true
    }

    fun resumeGame() {
        paused = false
    }

    fun selectProfile(id: String) {
        profileRepository.selectProfile(id)
    }

    fun createProfile(name: String, emoji: String, instrument: Instrument): Boolean =
        createProfileWithInstrument(viewModelScope, profileRepository, settingsRepository, name, emoji, instrument)

    private fun stopGame() {
        gameJob?.cancel()
        gameJob = null
        stopListening()
        _game.value = FreeBirdGameState(best = best)
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        latestPitch = PitchResult.SILENT
        _pitch.value = PitchResult.SILENT
    }

    private fun startListening() {
        if (listenJob != null) return
        listenJob = viewModelScope.launch {
            pitchProcessor.pitchFlow().collect { latestPitch = it }
        }
    }

    override fun onCleared() {
        listenJob?.cancel()
        listenJob = null
        gameJob?.cancel()
        gameJob = null
    }
}
