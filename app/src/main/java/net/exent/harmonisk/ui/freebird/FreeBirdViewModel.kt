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
import net.exent.harmonisk.audio.MetronomeClick
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.data.createProfileWithInstrument
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.music.PitchResult
import kotlin.math.abs
import kotlin.math.roundToInt
import javax.inject.Inject

@HiltViewModel
class FreeBirdViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val freeBirdRepository: FreeBirdRepository,
    private val pitchProcessor: PitchProcessor,
    private val soundFontSynthesizer: SoundFontSynthesizer
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
    private var synthJob: Job? = null
    private var best = 0
    private var runProfileId: String? = null
    private var paused = false
    private var latestPitch = PitchResult.SILENT

    init {
        openSynthesizer()
        viewModelScope.launch {
            var lastProfileId: String? = null
            profileRepository.activeProfile.collect { profile ->
                val profileId = profile?.id
                val switched = lastProfileId != null && lastProfileId != profileId
                if (switched) stopGame()
                if (!_game.value.playing) {
                    best = profile?.let { freeBirdRepository.bestDistance(it.id) } ?: 0
                    _game.update { it.copy(bestTenths = best) }
                }
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
        openSynthesizer()
        val profileId = profileRepository.activeProfileId
        runProfileId = profileId
        best = profileId?.let { freeBirdRepository.bestDistance(it) } ?: 0
        _game.value = FreeBirdGameState(
            bestTenths = best,
            recordTenths = best,
            playing = true,
            timeMs = FREEBIRD_GAME_TIME_MS,
            level = 1
        )
        gameJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            var elapsed = 0L
            var lastSpawn = 0L
            var nextSpawn = FREEBIRD_NOTE_INTERVAL_MS
            val firstNoteBeat = freeBirdFirstNoteBeatMs()
            val firstBeat = freeBirdFirstBeatMs()
            var nextBeat = firstBeat
            var beatIndex = 0L
            var beatMuteUntil = 0L
            var feedback: FreeBirdHit? = null
            var feedbackUntil = 0L
            var nextId = 1L
            var level = 1
            var newRecord = false
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
                elapsed += delta
                var timeLeft = state.timeMs - delta
                val gameElapsed = freeBirdFlightTimeMs(elapsed, timeLeft)
                val distance = freeBirdDistanceMeters(gameElapsed)
                val distanceTenths = (distance * 10f).roundToInt()
                if (distanceTenths > best) best = distanceTenths
                if (state.recordTenths > 0 && distanceTenths > state.recordTenths) newRecord = true
                _pitch.value = latestPitch
                if (feedback != null && now >= feedbackUntil) feedback = null
                if (timeLeft <= 0) {
                    if (distanceTenths > state.recordTenths) newRecord = true
                    saveRunBest()
                    _game.update {
                        it.copy(
                            playing = false,
                            finished = true,
                            distanceMeters = distance,
                            bestTenths = best,
                            newRecord = newRecord,
                            timeMs = 0,
                            letters = emptyList(),
                            feedback = null
                        )
                    }
                    stopListening()
                    break
                }
                var beatAt = state.beatAt
                var beatAccent = state.beatAccent
                if (elapsed >= nextBeat) {
                    val accent = beatIndex % FREEBIRD_BEATS_PER_BAR == 0L
                    beatAt = now
                    beatAccent = accent
                    click(accent)
                    beatMuteUntil = elapsed + FREEBIRD_BEAT_MUTE_MS
                    beatIndex++
                    nextBeat += FREEBIRD_NOTE_INTERVAL_MS
                }
                val letters = state.letters.toMutableList()
                if (elapsed >= nextSpawn) {
                    if (phraseIndex >= phrase.size) {
                        if (phrasesLeft <= 0) {
                            if (level < FREEBIRD_MAX_LEVEL) level++
                            phrasesLeft = FREEBIRD_PHRASES_PER_LEVEL
                        }
                        phrase = freeBirdPhrase(_instrument.value, level)
                        phraseIndex = 0
                        phrasesLeft--
                    }
                    val firstNote = nextId == 1L
                    val bonus = if (firstNote) elapsed + FREEBIRD_BEAK_LEAD_MS.toLong() else elapsed - lastSpawn
                    lastSpawn = elapsed
                    val note = phrase[phraseIndex++]
                    letters += FreeBirdLetter(nextId++, note.midi, note.finger, FREEBIRD_SPAWN_X, bonus)
                    val phraseFinished = phraseIndex >= phrase.size
                    nextSpawn = if (phraseFinished) {
                        val lastBeat = elapsed + FREEBIRD_BEAK_LEAD_MS.toLong()
                        freeBirdNextPhraseBeatMs(lastBeat, firstNoteBeat) - FREEBIRD_BEAK_LEAD_MS.toLong()
                    } else {
                        elapsed + FREEBIRD_NOTE_INTERVAL_MS
                    }
                }
                val speed = (FREEBIRD_SPAWN_X - BIRD_PERCH_X) / FREEBIRD_NOTE_TRAVEL_MS
                val next = letters.firstOrNull { !it.judged }
                val remaining = ArrayList<FreeBirdLetter>(letters.size)
                for (letter in letters) {
                    val x = letter.x - speed * delta
                    var judged = letter.judged
                    if (!judged) {
                        if (letter.id == next?.id && freeBirdInHitWindow(x)) {
                            val pitch = if (elapsed < beatMuteUntil) PitchResult.SILENT else _pitch.value
                            val targetCents = if (pitch.isSilent) 0f else centsFromTarget(pitch, letter.midi)
                            if (!pitch.isSilent && abs(targetCents) <= FREEBIRD_HIT_CENTS) {
                                val points = pointsForCents(targetCents)
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
                    if (x > FREEBIRD_FADE_END_X) {
                        remaining += letter.copy(x = x, judged = judged)
                    }
                }
                _game.value = state.copy(
                    distanceMeters = distance,
                    bestTenths = best,
                    newRecord = newRecord,
                    timeMs = timeLeft,
                    level = level,
                    letters = remaining,
                    feedback = feedback,
                    beatAt = beatAt,
                    beatAccent = beatAccent
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

    private fun click(accented: Boolean) {
        if (!soundFontSynthesizer.isReady) return
        val note = if (accented) MetronomeClick.ACCENT_NOTE else MetronomeClick.BEAT_NOTE
        val velocity = if (accented) MetronomeClick.ACCENT_VELOCITY else MetronomeClick.BEAT_VELOCITY
        soundFontSynthesizer.noteOn(SoundFontSynthesizer.DRUM_CHANNEL, note, velocity)
        viewModelScope.launch {
            delay(MetronomeClick.LENGTH_MS)
            soundFontSynthesizer.noteOff(SoundFontSynthesizer.DRUM_CHANNEL, note)
        }
    }

    private fun openSynthesizer() {
        if (soundFontSynthesizer.isReady || synthJob?.isActive == true) return
        synthJob = viewModelScope.launch {
            try {
                soundFontSynthesizer.open()
            } catch (_: Exception) {
            }
        }
    }

    private fun saveRunBest() {
        val profileId = runProfileId ?: return
        freeBirdRepository.saveBestDistance(profileId, best)
        runProfileId = null
    }

    private fun stopGame() {
        saveRunBest()
        gameJob?.cancel()
        gameJob = null
        stopListening()
        _game.value = FreeBirdGameState(bestTenths = best)
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
        saveRunBest()
        listenJob?.cancel()
        listenJob = null
        gameJob?.cancel()
        gameJob = null
        synthJob?.cancel()
        synthJob = null
        soundFontSynthesizer.close()
    }
}
