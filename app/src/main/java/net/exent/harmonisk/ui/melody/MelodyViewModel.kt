package net.exent.harmonisk.ui.melody

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.exent.harmonisk.audio.AudioConstants
import net.exent.harmonisk.audio.MelodyPlayer
import net.exent.harmonisk.audio.SoundFontSynthesizer
import net.exent.harmonisk.audio.NotePlayer
import net.exent.harmonisk.audio.PitchProcessor
import net.exent.harmonisk.audio.PlaybackRecorder
import net.exent.harmonisk.audio.TakeTimelinePoint
import net.exent.harmonisk.data.AudioDelayRepository
import net.exent.harmonisk.data.DevModeState
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.data.MelodySettingsRepository
import net.exent.harmonisk.data.MetronomeMode
import net.exent.harmonisk.data.PlaylistRepository
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.createProfileWithInstrument
import net.exent.harmonisk.data.ScoreHistoryRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.midi.MelodyTrack
import net.exent.harmonisk.midi.MidiDeviceManager
import net.exent.harmonisk.midi.MidiInputReceiver
import net.exent.harmonisk.midi.MidiNoteEvent
import net.exent.harmonisk.midi.MidiOutputSender
import net.exent.harmonisk.midi.NoteEvent
import net.exent.harmonisk.music.*
import net.exent.harmonisk.ui.MAX_FALL_SPEED
import net.exent.harmonisk.ui.MAX_MIDI
import net.exent.harmonisk.ui.MIN_FALL_SPEED
import net.exent.harmonisk.ui.MIN_MIDI
import net.exent.harmonisk.ui.RANGE_PADDING
import net.exent.harmonisk.ui.celebration.CelebrationLevel
import net.exent.harmonisk.ui.adventure.AdventureSessionRepository
import net.exent.harmonisk.ui.adventure.AdventureTurn
import net.exent.harmonisk.ui.adventure.AdventureTurnResult
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.exent.harmonisk.BuildConfig
import javax.inject.Inject
import java.io.File
import kotlin.math.abs

data class MelodyScoreSummary(val level: Int, val score: Float?)

@HiltViewModel
class MelodyViewModel @Inject constructor(
    private val pitchProcessor: PitchProcessor,
    private val playbackRecorder: PlaybackRecorder,
    private val melodyRepository: MelodyRepository,
    private val melodySettingsRepository: MelodySettingsRepository,
    private val settingsRepository: SettingsRepository,
    private val scoringEngine: ScoringEngine,
    private val noteUtils: NoteUtils,
    private val soundFontSynthesizer: SoundFontSynthesizer,
    private val melodyPlayer: MelodyPlayer,
    private val audioDelayRepository: AudioDelayRepository,
    private val midiDeviceManager: MidiDeviceManager,
    private val midiInputReceiver: MidiInputReceiver,
    private val midiOutputSender: MidiOutputSender,
    private val scoreHistoryRepository: ScoreHistoryRepository,
    private val playlistRepository: PlaylistRepository,
    private val profileRepository: ProfileRepository,
    private val bowedAlgorithm: BowedAlgorithm,
    private val beginnerKey: BeginnerKey,
    private val devModeState: DevModeState,
    private val adventureSession: AdventureSessionRepository
) : ViewModel() {

    companion object {
        private val TAG = "MelodyViewModel"
        private const val PREVIEW_CUE_MS = 3000L
        private const val YOUR_TURN_CUE_MS = 3000L
        private const val PREVIEW_TAIL_SECONDS = 0.9
        private const val TIMELINE_SAMPLE_MS = 100L
        private const val EARLY_ONSET_LOOKAHEAD_SECONDS = 0.75
        private const val FRESH_PITCH_MS = 150L
    }

    private val _gameState = MutableStateFlow(MelodyGameState())
    val gameState: StateFlow<MelodyGameState> = _gameState.asStateFlow()

    private val _viewState = MutableStateFlow(MelodyViewState())
    val viewState: StateFlow<MelodyViewState> = _viewState.asStateFlow()

    private val _deviceState = MutableStateFlow(MelodyDeviceState())
    val deviceState: StateFlow<MelodyDeviceState> = _deviceState.asStateFlow()

    val devModeEnabled: StateFlow<Boolean> = devModeState.enabled

    val profiles: StateFlow<List<ProfileRepository.Profile>> = profileRepository.profiles
    val activeProfile: StateFlow<ProfileRepository.Profile?> = profileRepository.activeProfile

    val availableMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        melodyRepository.importedMelodies
            .map { imported -> melodyRepository.getBundledMelodies() + imported }
            .stateIn(viewModelScope, SharingStarted.Eagerly, melodyRepository.getBundledMelodies())

    private val _currentScoreHistory = MutableStateFlow<List<ScoreHistoryRepository.ScoreEntry>>(emptyList())
    val currentScoreHistory: StateFlow<List<ScoreHistoryRepository.ScoreEntry>> = _currentScoreHistory.asStateFlow()

    private val _personalBestScore = MutableStateFlow<Float?>(null)
    val personalBestScore: StateFlow<Float?> = _personalBestScore.asStateFlow()

    private val _allMelodyScores = MutableStateFlow<Map<String, MelodyScoreSummary>>(emptyMap())
    val allMelodyScores: StateFlow<Map<String, MelodyScoreSummary>> = _allMelodyScores.asStateFlow()

    private val _allMelodyLevels = MutableStateFlow<Map<String, Int>>(emptyMap())
    val allMelodyLevels: StateFlow<Map<String, Int>> = _allMelodyLevels.asStateFlow()

    private val _allMelodyOverrides = MutableStateFlow<Set<String>>(emptySet())
    val allMelodyOverrides: StateFlow<Set<String>> = _allMelodyOverrides.asStateFlow()

    val playlists: StateFlow<List<PlaylistRepository.Playlist>> = playlistRepository.playlists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _playlistRun = MutableStateFlow<PlaylistRun?>(null)
    val playlistRun: StateFlow<PlaylistRun?> = _playlistRun.asStateFlow()

    private val lastPlayedAt: StateFlow<Map<String, Long>> = scoreHistoryRepository.lastPlayed
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val sortedMelodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        combine(availableMelodies, lastPlayedAt) { melodies, lastPlayed ->
            melodies.sortedWith(
                compareByDescending<MelodyRepository.MelodyInfo> { lastPlayed[it.id] ?: Long.MIN_VALUE }
                    .thenBy { it.name }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _allMelodyDifficulties = MutableStateFlow<Map<String, Float>>(emptyMap())
    val allMelodyDifficulties: StateFlow<Map<String, Float>> = _allMelodyDifficulties.asStateFlow()

    private val _celebrationLevel = MutableStateFlow(CelebrationLevel.NONE)
    val celebrationLevel: StateFlow<CelebrationLevel> = _celebrationLevel.asStateFlow()

    private var playbackJob: Job? = null
    private var pitchJob: Job? = null
    private var melodySettingsJob: Job? = null
    private var scoreHistoryJob: Job? = null
    private var previewClockJob: Job? = null
    private var previewSavedOffset: Double? = null
    private var runStartJob: Job? = null
    private var adventureStartJob: Job? = null
    private var playbackStartJob: Job? = null
    private var playbackStartWallMs = 0L
    private var pauseOffset = 0.0

    private var waitStartWallMs = 0L
    private var waitMs = 0L

    private val noteTracking = NoteTrackingState()
    private var latestPitchResult: PitchResult = PitchResult.SILENT
    private var latestPitchAtMs = 0L

    private var currentNoteDetectedCorrectly = false
    private var currentChordIndices: List<Int> = emptyList()

    private var originalMelodyTracks: List<MelodyTrack> = emptyList()
    private var currentMelodyId: String? = null
    private val takeTimeline = mutableListOf<TakeTimelinePoint>()
    private val takeNoteOffsets = mutableMapOf<Int, Long>()
    private var replayJob: Job? = null
    private var replaySavedOffset: Double? = null
    private val _recordingAvailable = MutableStateFlow(false)
    val recordingAvailable: StateFlow<Boolean> = _recordingAvailable.asStateFlow()
    private var manualTranspose = Transpose(0, 0)
    private var songOverrides: Set<String> = emptySet()

    private val _currentInstrument = MutableStateFlow(Instrument.VIOLIN.name)
    private val currentInstrumentFlow: StateFlow<String> = _currentInstrument.asStateFlow()

    val currentInstrument: StateFlow<Instrument> = currentInstrumentFlow
        .map { Instrument.fromName(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Instrument.VIOLIN)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val instrumentFlow: Flow<Instrument> =
        adventureSession.turn.flatMapLatest { turn ->
            if (turn != null) settingsRepository.instrument(turn.playerId)
            else settingsRepository.settings.map { Instrument.fromName(it.instrumentName) }
        }

    private var synthOpenJob: Job? = null
    private var audioDelayMs: Int = 0
    private var referenceA4: Float = NoteUtils.DEFAULT_CONCERT_PITCH

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                pitchProcessor.setSilenceThreshold(settings.silenceThresholdDb)
                referenceA4 = settings.concertPitchA4
                pitchProcessor.setReferenceA4(settings.concertPitchA4)
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                _gameState.update { it.copy(metronomeMode = settings.metronomeMode) }
            }
        }
        viewModelScope.launch {
            instrumentFlow.collect { instrument ->
                val newInstrument = instrument.name
                val prevInstrument = _currentInstrument.value
                _currentInstrument.value = newInstrument
                pitchProcessor.setInstrument(instrument)
                if (newInstrument != prevInstrument && soundFontSynthesizer.isReady) {
                    soundFontSynthesizer.setInstrument(instrument.midiBank, instrument.midiProgram)
                }
                updateBowedPositions()
                val melodyId = currentMelodyId
                if (melodyId != null) {
                    loadMelodySettingsAndHistory(melodyId, newInstrument)
                }
            }
        }
        viewModelScope.launch {
            audioDelayRepository.audioDelay.collect { delayMs ->
                audioDelayMs = delayMs
            }
        }
        viewModelScope.launch {
            midiDeviceManager.isMidiInputAvailable.collect { available ->
                _deviceState.update { it.copy(midiAvailable = available) }
            }
        }
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiInputEnabled.collect { enabled ->
                _deviceState.update { it.copy(midiInputEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            midiDeviceManager.effectiveMidiOutputEnabled.collect { enabled ->
                _deviceState.update { it.copy(midiOutputEnabled = enabled) }
                val player: NotePlayer = if (enabled && midiOutputSender.isReady) midiOutputSender else soundFontSynthesizer
                melodyPlayer.setNotePlayer(player)
            }
        }
        loadAllMelodyScores()
        loadAllMelodyDifficulties()
        viewModelScope.launch {
            profileRepository.activeProfile
                .map { it?.id }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (adventureSession.turn.value != null) return@collect
                    endPlaylistSession()
                    if (currentMelodyId != null) {
                        stop()
                        enterReadyPhase()
                    }
                }
        }
    }

    fun toggleMidiInput() {
        midiDeviceManager.setInputOverride(
            computeNextOverride(midiDeviceManager.inputOverride.value, _deviceState.value.midiInputEnabled)
        )
    }

    fun toggleMidiOutput() {
        midiDeviceManager.setOutputOverride(
            computeNextOverride(midiDeviceManager.outputOverride.value, _deviceState.value.midiOutputEnabled)
        )
    }

    private fun computeNextOverride(current: Boolean?, effective: Boolean): Boolean? = when {
        current == true -> null
        effective -> false
        else -> true
    }

    fun selectProfile(id: String) {
        profileRepository.selectProfile(id)
    }

    fun createProfile(name: String, emoji: String, instrument: Instrument): Boolean =
        createProfileWithInstrument(viewModelScope, profileRepository, settingsRepository, name, emoji, instrument)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadAllMelodyScores() {
        viewModelScope.launch {
            combine(availableMelodies, currentInstrumentFlow) { melodies, instrument ->
                melodies to instrument
            }.flatMapLatest { (melodies, instrument) ->
                if (melodies.isEmpty()) {
                    flowOf(Triple(emptyMap<String, Int>(), emptyMap<String, MelodyScoreSummary>(), emptySet<String>()))
                } else {
                    val flows = melodies.map { info ->
                        combine(
                            melodySettingsRepository.getSettings(info.id, instrument),
                            scoreHistoryRepository.getScores(info.id, instrument)
                        ) { settings, scores ->
                            val level = settings.level.coerceIn(1, 5)
                            val summary = if (scores.isEmpty()) null else MelodyScoreSummary(
                                level = level,
                                score = scores.filter { it.level == level }.maxOfOrNull { it.overallScore }
                            )
                            val hasOverrides = settings.overrides.orEmpty().any { it in MelodySettingsRepository.PREFERENCE_OVERRIDES }
                            info.id to Triple(level, summary, hasOverrides)
                        }
                    }
                    combine(flows) { pairs ->
                        Triple(
                            pairs.associate { (id, data) -> id to data.first },
                            pairs.mapNotNull { (id, data) -> data.second?.let { id to it } }.toMap(),
                            pairs.filter { it.second.third }.map { it.first }.toSet()
                        )
                    }
                }
            }.collect { (levels, summaries, overrideIds) ->
                _allMelodyLevels.value = levels
                _allMelodyScores.value = summaries
                _allMelodyOverrides.value = overrideIds
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadAllMelodyDifficulties() {
        viewModelScope.launch {
            availableMelodies
                .flatMapLatest { melodies ->
                    flow {
                        val results = melodies.map { info ->
                            async { info.id to melodyRepository.getDifficulty(info) }
                        }
                        emit(results.awaitAll().toMap())
                    }
                }
                .collect { result ->
                    _allMelodyDifficulties.value = result
                }
        }
    }

    private fun levelToSettings(level: Int): Pair<Boolean, Boolean> {
        return when (level) {
            1 -> Pair(true, false)
            2 -> Pair(true, false)
            3 -> Pair(true, false)
            4 -> Pair(false, false)
            5 -> Pair(false, true)
            else -> Pair(true, false)
        }
    }

    private fun assignChannels(tracks: List<MelodyTrack>): List<MelodyTrack> =
        tracks.mapIndexed { index, track ->
            if (track.notes.isEmpty()) track
            else {
                val channel = if (index >= SoundFontSynthesizer.DRUM_CHANNEL) (index + 1).coerceAtMost(15) else index
                MelodyTrack(
                    name = track.name,
                    notes = track.notes,
                    tempoChanges = track.tempoChanges,
                    timeSignatures = track.timeSignatures,
                    ticksPerQuarterNote = track.ticksPerQuarterNote,
                    channel = channel,
                    keySignature = track.keySignature
                )
            }
        }

    fun loadMelody(info: MelodyRepository.MelodyInfo): Boolean {
        stopPreview()
        playbackStartJob?.cancel()
        runStartJob?.cancel()
        adventureStartJob?.cancel()
        val melodyTracks = assignChannels(melodyRepository.loadMultiTrack(info).filter { it.notes.isNotEmpty() })
        if (melodyTracks.isNotEmpty()) {
            originalMelodyTracks = melodyTracks
            currentMelodyId = info.id
            _recordingAvailable.value = playbackRecorder.takeFor(info.id) != null
            val melodyName = info.name
            currentChordIndices = emptyList()
            val primary = melodyTracks.first()
            val metronomeTrack = generateMetronomeTrack(primary)
            val (viewMin, viewMax) = viewRangeFor(primary)
            manualTranspose = Transpose(0, 0)
            _gameState.update { it.withLoadedMelody(primary, melodyTracks, metronomeTrack, melodyName) }
            _viewState.update {
                it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax, songTimeOffsetSeconds = 0.0)
            }
            updateBowedPositions()
            loadMelodySettingsAndHistory(info.id)
            return true
        }
        return false
    }

    suspend fun importFile(uri: android.net.Uri): MelodyRepository.ImportResult? = melodyRepository.importFile(uri)

    fun createPlaylist(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(playlistRepository.create(name)) }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { playlistRepository.rename(id, name) }
    }

    fun deletePlaylist(id: String) {
        if (_playlistRun.value?.playlistId == id) endPlaylistSession()
        viewModelScope.launch { playlistRepository.delete(id) }
    }

    fun setPlaylistMelodies(id: String, melodyIds: List<String>) {
        viewModelScope.launch { playlistRepository.setMelodyIds(id, melodyIds) }
    }

    suspend fun startPlaylist(playlist: PlaylistRepository.Playlist): Boolean {
        val all = availableMelodies.value.associateBy { it.id }
        val infos = playlist.melodyIds.mapNotNull { all[it] }
        if (infos.isEmpty()) return false
        val songs = infos.map { PlaylistSong(it.id, it.name, levelOf(it.id)) }
        endPlaylistSession()
        stop()
        if (!loadMelody(infos.first())) return false
        _playlistRun.value = PlaylistRun(
            playlistId = playlist.id,
            name = playlist.name,
            songs = songs,
            pending = true
        )
        return true
    }

    fun restartPlaylist() {
        val run = _playlistRun.value ?: return
        viewModelScope.launch {
            startPlaylist(PlaylistRepository.Playlist(run.playlistId, run.name, run.songs.map { it.id }))
        }
    }

    fun continuePlaylist() {
        if (_playlistRun.value?.pending != true) return
        _playlistRun.update { it?.copy(pending = false) }
        viewModelScope.launch {
            awaitSettingsLoaded()
            enterReadyPhase()
            startRun()
        }
    }

    fun retryPlaylistSong(index: Int) {
        val run = _playlistRun.value ?: return
        if (!run.finished || index !in run.results.indices) return
        val song = run.songs.getOrNull(index) ?: return
        viewModelScope.launch {
            val info = availableMelodies.value.firstOrNull { it.id == song.id } ?: return@launch
            loadMelody(info)
            val current = _playlistRun.value ?: return@launch
            if (current !== run) return@launch
            val retryRun = current.copy(
                index = index,
                previous = null,
                pending = false,
                finished = false,
                retrySongIndex = index
            )
            _playlistRun.value = retryRun
            awaitSettingsLoaded()
            if (_playlistRun.value !== retryRun) return@launch
            enterReadyPhase()
            startRun()
        }
    }

    fun endPlaylistSession() {
        _playlistRun.value = null
    }

    private suspend fun levelOf(melodyId: String): Int {
        val profileId = profileRepository.activeProfileId ?: return 1
        return melodySettingsRepository.getSettings(profileId, melodyId, _currentInstrument.value)
            .first().level.coerceIn(1, 5)
    }

    private fun finishPlaylistSong(result: PlaylistResult) {
        val run = _playlistRun.value ?: return
        val retryIndex = run.retrySongIndex
        if (retryIndex != null) {
            val retryResults = run.results.toMutableList()
            if (retryIndex in retryResults.indices) retryResults[retryIndex] = result
            _playlistRun.value = run.copy(
                results = retryResults,
                previous = result,
                pending = false,
                finished = true,
                retrySongIndex = null
            )
            return
        }
        val results = run.results + result
        val nextIndex = run.index + 1
        if (nextIndex >= run.songs.size) {
            _playlistRun.value = run.copy(results = results, previous = result, pending = false, finished = true)
            return
        }
        viewModelScope.launch {
            val next = run.songs[nextIndex]
            val level = levelOf(next.id)
            val songs = run.songs.toMutableList().also { it[nextIndex] = next.copy(level = level) }
            availableMelodies.value.firstOrNull { it.id == next.id }?.let { loadMelody(it) }
            val current = _playlistRun.value ?: return@launch
            if (current !== run) return@launch
            _playlistRun.value = current.copy(
                index = nextIndex,
                songs = songs,
                results = results,
                previous = result,
                pending = true
            )
        }
    }

    suspend fun importFromUrl(url: String): MelodyRepository.ImportResult? = melodyRepository.importFromUrl(url)

    private fun MelodyGameState.withLoadedMelody(
        primary: MelodyTrack,
        melodyTracks: List<MelodyTrack>,
        metronomeTrack: MelodyTrack?,
        name: String
    ): MelodyGameState = copy(
        melodyTrack = primary,
        melodyTracks = melodyTracks,
        metronomeTrack = metronomeTrack,
        melodyName = name,
        activeVoiceIndex = 0,
        accompanimentMode = accompanimentMode,
        phase = PlayPhase.NOT_STARTED,
        transpose = Transpose(0, 0),
        currentTimeSeconds = -twoBarLeadInSeconds(primary),
        previewTimeSeconds = null,
        noteResults = emptyMap(),
        currentNoteIndex = -1,
        melodyScore = null,
        newlyUnlockedLevel = null,
        noteCentsHistory = emptyMap(),
        noteCentsPositions = emptyMap(),
        notePlayProgress = emptyMap(),
        noteFirstDetectionStart = emptyMap(),
        cue = null,
        settingsLoaded = false,
        replayTimeSeconds = null,
        replayNoteOffsetsMs = emptyMap(),
        replayLevel = 0
    )

    private fun loadMelodySettingsAndHistory(melodyId: String, instrument: String = _currentInstrument.value) {
        val adventureTurn = adventureSession.turn.value
        val profileId = adventureTurn?.playerId ?: profileRepository.activeProfileId ?: return
        melodySettingsJob?.cancel()
        scoreHistoryJob?.cancel()
        _currentScoreHistory.value = emptyList()
        _personalBestScore.value = null
        melodySettingsJob = viewModelScope.launch {
            melodySettingsRepository.getSettings(profileId, melodyId, instrument).first().let { saved ->
                val level = (adventureTurn?.level ?: saved.level).coerceIn(1, 5)
                val unlocked = maxOf(saved.unlockedLevel.coerceIn(1, 5), level)
                val (wm, hnb) = levelToSettings(level)
                val accompanimentMode = try {
                    AccompanimentMode.valueOf(saved.accompanimentMode)
                } catch (_: IllegalArgumentException) {
                    AccompanimentMode.ALL
                }
                songOverrides = saved.overrides.orEmpty().toSet()
                _gameState.update {
                    it.copy(
                        waitMode = wm,
                        currentLevel = level,
                        unlockedLevel = unlocked,
                        playedLevel = level,
                        newlyUnlockedLevel = null,
                        hideNoteBars = hnb,
                        settingsLoaded = true,
                        activeVoiceIndex = saved.activeVoiceIndex,
                        accompanimentMode = accompanimentMode,
                        preferOpenStrings = saved.preferOpenStrings,
                        metronomeMode = MetronomeMode.fromName(saved.metronomeMode)
                    )
                }
                _viewState.update { it.copy(fallSpeedPxPerSec = migrateFallSpeed(saved.fallSpeedPxPerSec)) }
                val tracks = _gameState.value.melodyTracks
                if (tracks.isNotEmpty()) {
                    val idx = saved.activeVoiceIndex.coerceIn(0, tracks.lastIndex)
                    setActiveVoice(idx, persist = false)
                }
                manualTranspose = Transpose.fromSemitones(saved.transposeSemitones)
                if (saved.autoTranspose) applyAuto(persist = false)
                else applyTranspose(manualTranspose, auto = false, persist = false)
            }
        }
        scoreHistoryJob = viewModelScope.launch {
            scoreHistoryRepository.getScores(profileId, melodyId, instrument).collect { scores ->
                _currentScoreHistory.value = scores
            }
        }
    }

    fun setLevel(level: Int) {
        val clamped = level.coerceIn(1, 5)
        val (wm, hnb) = levelToSettings(clamped)
        _gameState.update {
            it.copy(
                currentLevel = clamped,
                unlockedLevel = maxOf(it.unlockedLevel, clamped),
                waitMode = wm,
                hideNoteBars = hnb
            )
        }
        saveMelodySettings()
    }

    fun retryLevel(level: Int) {
        if (level > _gameState.value.unlockedLevel) return
        setLevel(level)
        startPlaying()
    }

    fun setOctave(octave: Int) {
        val next = Transpose.of(octave, _gameState.value.transpose.key)
        manualTranspose = next
        pinOverrides(MelodySettingsRepository.OVERRIDE_EASY_KEY)
        applyTranspose(next, auto = false)
    }

    fun setKey(key: Int) {
        val next = Transpose.of(_gameState.value.transpose.octave, key)
        manualTranspose = next
        pinOverrides(MelodySettingsRepository.OVERRIDE_EASY_KEY)
        applyTranspose(next, auto = false)
    }

    fun setAutoTranspose(enabled: Boolean) {
        pinOverrides(MelodySettingsRepository.OVERRIDE_EASY_KEY)
        if (enabled) applyAuto() else applyTranspose(manualTranspose, auto = false)
    }

    fun setPreferOpenStrings(preferOpenStrings: Boolean) {
        if (_gameState.value.preferOpenStrings == preferOpenStrings) return
        _gameState.update { it.copy(preferOpenStrings = preferOpenStrings) }
        pinOverrides(MelodySettingsRepository.OVERRIDE_OPEN_STRINGS)
        if (_gameState.value.autoTranspose) applyAuto() else applyTranspose(manualTranspose, auto = false)
    }

    private fun pinOverrides(vararg keys: String) {
        songOverrides = songOverrides + keys
    }

    private fun autoOffset(): Int {
        val instrument = Instrument.fromName(_currentInstrument.value)
        val track = originalMelodyTracks.getOrNull(_gameState.value.activeVoiceIndex)
            ?: originalMelodyTracks.firstOrNull()
            ?: return 0
        return beginnerKey.offset(
            track.notes,
            track.keySignature,
            instrument,
            _gameState.value.preferOpenStrings
        )
    }

    private fun applyAuto(persist: Boolean = true) {
        val next = Transpose.fromSemitones(autoOffset())
        manualTranspose = next
        applyTranspose(next, auto = true, persist = persist)
    }

    private fun applyTranspose(transposition: Transpose, auto: Boolean, persist: Boolean = true) {
        if (originalMelodyTracks.isEmpty()) return
        val clamped = Transpose.of(transposition.octave, transposition.key)
        val melodyTracks = originalMelodyTracks.map { it.transpose(clamped.semitones) }
        val activeIdx = _gameState.value.activeVoiceIndex.coerceAtMost(melodyTracks.lastIndex)
        val primary = melodyTracks[activeIdx]
        val (viewMin, viewMax) = viewRangeFor(primary)
        _gameState.update {
            it.copy(
                melodyTrack = primary,
                melodyTracks = melodyTracks,
                transpose = clamped,
                autoTranspose = auto
            )
        }
        _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
        updateBowedPositions()
        if (persist) saveMelodySettings()
    }

    private fun viewRangeFor(track: MelodyTrack): Pair<Float, Float> {
        val range = track.noteRange
        return Pair(
            (range.first - RANGE_PADDING).toFloat().coerceAtLeast(MIN_MIDI),
            (range.last + RANGE_PADDING).toFloat().coerceAtMost(MAX_MIDI)
        )
    }

    private fun updateBowedPositions() {
        val track = _gameState.value.melodyTrack
        val instrument = Instrument.fromName(_currentInstrument.value)
        val positions = if (track != null) {
            bowedAlgorithm.recommendPositions(
                instrument,
                track.notes.map { it.noteNumber },
                track.keySignature,
                _gameState.value.preferOpenStrings
            )
        } else {
            emptyList()
        }
        _viewState.update { it.copy(bowedPositions = positions) }
    }

    private fun migrateFallSpeed(saved: Float): Float {
        return if (saved == 150f) 75f else saved
    }

    private fun saveMelodySettings() {
        if (adventureSession.turn.value != null) return
        val id = currentMelodyId ?: return
        val instrument = _currentInstrument.value
        val g = _gameState.value
        val v = _viewState.value
        val overrides = songOverrides.toList()
        viewModelScope.launch {
            melodySettingsRepository.saveSettings(id, instrument, MelodySettingsRepository.MelodySettings(
                autoTranspose = g.autoTranspose,
                transposeSemitones = g.transpose.semitones,
                preferOpenStrings = g.preferOpenStrings,
                fallSpeedPxPerSec = v.fallSpeedPxPerSec,
                level = g.currentLevel,
                unlockedLevel = g.unlockedLevel,
                activeVoiceIndex = g.activeVoiceIndex,
                accompanimentMode = g.accompanimentMode.name,
                metronomeMode = g.metronomeMode.name,
                overrides = overrides
            ))
        }
    }


    fun initSynthesizer() {
        if (soundFontSynthesizer.isReady || synthOpenJob?.isActive == true) return
        synthOpenJob = viewModelScope.launch {
            try {
                soundFontSynthesizer.open()
                val instrument = Instrument.fromName(_currentInstrument.value)
                soundFontSynthesizer.setInstrument(instrument.midiBank, instrument.midiProgram)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Synth init failed", e)
            }
        }
    }

    fun previewMelody() {
        val state = _gameState.value
        val tracks = state.melodyTracks
        if (tracks.isEmpty() || !soundFontSynthesizer.isReady) return
        stopPreview()

        val leadIn = twoBarLeadInSeconds()
        val followsCanvas = state.phase == PlayPhase.READY || state.phase == PlayPhase.FINISHED
        if (followsCanvas) previewSavedOffset = _viewState.value.songTimeOffsetSeconds
        melodyPlayer.playWithTiming(
            tracks + metronomeTracks(
                state.metronomeMode,
                state.waitMode,
                state.metronomeTrack,
                leadInMetronome(state.metronomeTrack)
            ),
            viewModelScope,
            leadInSeconds = leadIn,
            startSeconds = 0.0,
            tailSeconds = PREVIEW_TAIL_SECONDS
        )
        if (followsCanvas) startPreviewClock(state.melodyTrack, leadIn, 0.0)
    }

    fun previewMelody(info: MelodyRepository.MelodyInfo) {
        val tracks = melodyRepository.loadMultiTrack(info)
        if (tracks.isEmpty() || !soundFontSynthesizer.isReady) return
        melodyPlayer.playWithTiming(tracks, viewModelScope, tailSeconds = PREVIEW_TAIL_SECONDS)
    }

    fun stopPreview() {
        melodyPlayer.stop()
        endPreviewClock()
        previewSavedOffset = null
    }

    fun stopPreviewAndReturn() {
        melodyPlayer.stop()
        finishPreview()
    }

    fun toggleRecordingPlayback() {
        if (_gameState.value.replayTimeSeconds != null) stopRecordingPlayback() else playRecording()
    }

    private fun playRecording() {
        val melodyId = currentMelodyId ?: return
        val take = playbackRecorder.takeFor(melodyId) ?: return
        stopPreview()
        stopRecordingPlayback()
        replayJob = viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { playbackRecorder.play(take) }) return@launch
            replaySavedOffset = _viewState.value.songTimeOffsetSeconds
            _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }
            _gameState.update {
                it.copy(
                    replayTimeSeconds = take.gameTimeAt(0),
                    replayNoteOffsetsMs = take.noteOffsetsMs,
                    replayLevel = take.level
                )
            }
            while (isActive && playbackRecorder.isPlaying()) {
                _gameState.update { it.copy(replayTimeSeconds = take.gameTimeAt(playbackRecorder.playbackPositionMs())) }
                delay(16)
            }
            stopRecordingPlayback()
        }
    }

    fun stopRecordingPlayback() {
        replayJob?.cancel()
        replayJob = null
        playbackRecorder.stopPlayback()
        if (_gameState.value.replayTimeSeconds != null) {
            _gameState.update { it.copy(replayTimeSeconds = null, replayNoteOffsetsMs = emptyMap(), replayLevel = 0) }
        }
        val offset = replaySavedOffset ?: return
        replaySavedOffset = null
        _viewState.update { it.copy(songTimeOffsetSeconds = offset) }
    }

    fun recordingFile(): File? {
        val melodyId = currentMelodyId ?: return null
        return playbackRecorder.takeFor(melodyId)?.audioFile?.takeIf { it.isFile }
    }

    val isPreviewPlaying: StateFlow<Boolean> = melodyPlayer.isPlaying

    private fun leadInMetronome(metronome: MelodyTrack?): List<MelodyTrack> {
        val clicks = metronome?.notes?.filter { it.startTick < 0 } ?: return emptyList()
        if (clicks.isEmpty()) return emptyList()
        return listOf(
            MelodyTrack(
                name = metronome.name,
                notes = clicks,
                tempoChanges = metronome.tempoChanges,
                timeSignatures = metronome.timeSignatures,
                ticksPerQuarterNote = metronome.ticksPerQuarterNote,
                channel = metronome.channel
            )
        )
    }

    private fun startPreviewClock(track: MelodyTrack?, leadInSeconds: Double, startSeconds: Double) {
        previewClockJob?.cancel()
        previewClockJob = null
        if (track == null) return
        _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }
        _gameState.update { it.copy(previewTimeSeconds = previewClockSeconds(startSeconds, leadInSeconds, 0.0, audioDelayMs)) }
        previewClockJob = viewModelScope.launch {
            val startTime = SystemClock.elapsedRealtime()
            while (isActive && melodyPlayer.isPlaying.value) {
                val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000.0
                _gameState.update {
                    it.copy(previewTimeSeconds = previewClockSeconds(startSeconds, leadInSeconds, elapsed, audioDelayMs))
                }
                delay(16)
            }
            if (isActive) finishPreview()
        }
    }

    private fun finishPreview() {
        endPreviewClock()
        val offset = previewSavedOffset ?: return
        previewSavedOffset = null
        _viewState.update { it.copy(songTimeOffsetSeconds = offset) }
    }

    private fun endPreviewClock() {
        previewClockJob?.cancel()
        previewClockJob = null
        if (_gameState.value.previewTimeSeconds != null) {
            _gameState.update { it.copy(previewTimeSeconds = null) }
        }
    }

    fun setActiveVoice(index: Int, persist: Boolean = true) {
        val tracks = _gameState.value.melodyTracks
        if (index < 0 || index >= tracks.size) return
        val selectedTrack = tracks[index]
        val (viewMin, viewMax) = viewRangeFor(selectedTrack)
        _gameState.update {
            it.copy(
                activeVoiceIndex = index,
                melodyTrack = selectedTrack
            )
        }
        _viewState.update { it.copy(viewMinMidi = viewMin, viewMaxMidi = viewMax) }
        updateBowedPositions()
        if (persist) saveMelodySettings()
    }

    fun setAccompanimentMode(mode: AccompanimentMode) {
        _gameState.update { it.copy(accompanimentMode = mode) }
        if (mode == AccompanimentMode.NONE) {
            melodyPlayer.resetGameSync()
        }
        saveMelodySettings()
    }

    fun setMetronomeMode(mode: MetronomeMode) {
        _gameState.update { it.copy(metronomeMode = mode) }
        pinOverrides(MelodySettingsRepository.OVERRIDE_METRONOME)
        saveMelodySettings()
    }

    private fun twoBarLeadInSeconds(track: MelodyTrack? = _gameState.value.melodyTrack): Double {
        track ?: return 2.0 * 4 * (60.0 / 120.0)
        val timeSig = track.timeSignatureAt(0)
        val bpm = track.bpmAt(0)
        val beatsPerBar = timeSig.numerator
        return 2.0 * beatsPerBar * (60.0 / bpm)
    }

    private fun generateMetronomeTrack(track: MelodyTrack): MelodyTrack? {
        val endTick = track.notes.maxOfOrNull { it.endTick } ?: return null
        val ppq = track.ticksPerQuarterNote

        val notes = mutableListOf<NoteEvent>()
        val firstTimeSig = track.timeSignatureAt(0)
        val firstTicksPerBar = ppq * 4 * firstTimeSig.numerator / firstTimeSig.denominator
        if (firstTicksPerBar <= 0) return null

        var tick = -2L * firstTicksPerBar
        while (tick <= endTick) {
            val timeSig = track.timeSignatureAt(tick.coerceAtLeast(0))
            val beatsPerBar = timeSig.numerator
            val ticksPerBar = ppq * 4 * timeSig.numerator / timeSig.denominator
            if (ticksPerBar <= 0 || beatsPerBar <= 0) { tick += ppq * 4; continue }
            val ticksPerBeat = ticksPerBar / beatsPerBar
            if (ticksPerBeat <= 0) { tick += ppq * 4; continue }

            for (beat in 0 until beatsPerBar) {
                val beatTick = tick + beat * ticksPerBeat
                if (beatTick > endTick) break
                val isAccent = beat == 0
                val note = if (isAccent) 86 else 85
                val vel = if (isAccent) 120 else 100
                val clickDuration = (ppq / 10).coerceAtLeast(1).toLong()
                notes.add(NoteEvent(note, vel, beatTick, clickDuration))
            }
            tick += ticksPerBar
        }

        if (notes.isEmpty()) return null
        return MelodyTrack(
            name = "metronome",
            notes = notes,
            tempoChanges = track.tempoChanges,
            timeSignatures = track.timeSignatures,
            ticksPerQuarterNote = ppq,
            channel = SoundFontSynthesizer.DRUM_CHANNEL
        )
    }

    fun startRun() {
        if (runStartJob?.isActive == true) return
        val state = _gameState.value
        if (state.phase != PlayPhase.READY) return
        if (state.currentLevel != 1) {
            startPlaying()
            return
        }
        runStartJob = viewModelScope.launch {
            _gameState.update { it.copy(cue = PlayCue.PREVIEW) }
            delay(PREVIEW_CUE_MS)
            if (_gameState.value.phase != PlayPhase.READY) return@launch
            if (!awaitSynthesizer()) {
                _gameState.update { it.copy(cue = null) }
                startPlaying()
                return@launch
            }
            previewMelody()
            _gameState.update { it.copy(cue = null) }
            if (melodyPlayer.isPlaying.value) melodyPlayer.isPlaying.first { !it }
            if (_gameState.value.phase != PlayPhase.READY) return@launch
            _gameState.update { it.copy(cue = PlayCue.YOUR_TURN) }
            delay(YOUR_TURN_CUE_MS)
            _gameState.update { it.copy(cue = null) }
            if (_gameState.value.phase == PlayPhase.READY) startPlaying()
        }
    }

    fun startAdventurePlay() {
        if (adventureStartJob?.isActive == true) return
        adventureStartJob = viewModelScope.launch {
            awaitSettingsLoaded()
            if (_gameState.value.phase == PlayPhase.READY) startRun()
        }
    }

    private suspend fun awaitSettingsLoaded(timeoutMs: Long = 1500): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!_gameState.value.settingsLoaded && SystemClock.elapsedRealtime() < deadline) delay(20)
        return _gameState.value.settingsLoaded
    }

    private suspend fun awaitSynthesizer(timeoutMs: Long = 2000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!soundFontSynthesizer.isReady && SystemClock.elapsedRealtime() < deadline) delay(50)
        return soundFontSynthesizer.isReady
    }

    fun enterReadyPhase() {
        if (_gameState.value.phase == PlayPhase.NOT_STARTED) {
            _gameState.update {
                it.copy(phase = PlayPhase.READY, currentTimeSeconds = -twoBarLeadInSeconds())
            }
        }
    }

    fun startPlaying() {
        if (playbackStartJob?.isActive == true) return
        if (_gameState.value.melodyTrack == null) return
        if (_gameState.value.phase == PlayPhase.PLAYING || _gameState.value.phase == PlayPhase.WAITING_FOR_NOTE) return
        if (!soundFontSynthesizer.isReady && synthOpenJob?.isActive == true) {
            playbackStartJob = viewModelScope.launch {
                awaitSynthesizer()
                startPlayback()
            }
            return
        }
        startPlayback()
    }

    private fun startPlayback() {
        if (_gameState.value.melodyTrack == null) return
        if (_gameState.value.phase == PlayPhase.PLAYING || _gameState.value.phase == PlayPhase.WAITING_FOR_NOTE) return
        stopRecordingPlayback()

        val leadIn = twoBarLeadInSeconds()
        val startTime = playbackStartTime(leadIn)

        _gameState.update {
            it.copy(
                phase = PlayPhase.PLAYING,
                currentTimeSeconds = startTime,
                noteResults = emptyMap(),
                noteScores = emptyList(),
                currentNoteIndex = -1,
                melodyScore = null,
                noteCentsHistory = emptyMap(),
                noteCentsPositions = emptyMap(),
                noteFirstDetectionStart = emptyMap(),
                playedLevel = it.currentLevel,
                runId = it.runId + 1,
                newlyUnlockedLevel = null
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = 0.0) }

        noteTracking.reset()
        latestPitchResult = PitchResult.SILENT
        latestPitchAtMs = 0L
        currentChordIndices = emptyList()
        currentNoteDetectedCorrectly = false
        waitStartWallMs = 0L
        waitMs = 0L

        stopPreview()
        melodyPlayer.resetGameSync()

        takeTimeline.clear()
        takeNoteOffsets.clear()
        val melodyId = currentMelodyId
        if (melodyId != null && adventureSession.turn.value == null) {
            playbackRecorder.startRecording(melodyId, _gameState.value.currentLevel)
        }

        pauseOffset = startTime
        startPlaybackTimer()
        startPitchDetection()
    }

    private fun playbackStartTime(leadInSeconds: Double): Double =
        -leadInSeconds - audioLeadSeconds(audioDelayMs)

    fun stop() {
        playbackStartJob?.cancel()
        playbackJob?.cancel()
        pitchJob?.cancel()
        runStartJob?.cancel()
        runStartJob = null
        stopPreview()
        stopRecordingPlayback()
        if (playbackRecorder.isRecording()) playbackRecorder.stopRecording(false, emptyList(), emptyMap())
        takeTimeline.clear()
        takeNoteOffsets.clear()
        _gameState.update { it.copy(cue = null) }
        pauseOffset = 0.0
        noteTracking.isWaitingForNote = false
        endWait()
        _gameState.update {
            it.copy(
                phase = PlayPhase.NOT_STARTED,
                currentTimeSeconds = 0.0,
                noteResults = emptyMap(),
                noteCentsHistory = emptyMap(),
                noteCentsPositions = emptyMap(),
                noteFirstDetectionStart = emptyMap(),
            )
        }
    }

    fun resetScore(melodyId: String) {
        viewModelScope.launch {
            scoreHistoryRepository.resetScores(melodyId, currentInstrumentFlow.value)
        }
    }

    fun resetMelodyOverrides(melodyId: String) {
        viewModelScope.launch {
            melodySettingsRepository.clearPreferenceOverrides(melodyId, currentInstrumentFlow.value)
        }
    }

    fun removeImportedMelody(melodyId: String) {
        viewModelScope.launch {
            melodyRepository.removeImportedMelody(melodyId)
            scoreHistoryRepository.resetAllScores(melodyId)
            playlistRepository.removeMelodyFromAll(melodyId)
        }
    }

    fun removeImportedFolder(folder: String) {
        viewModelScope.launch {
            melodyRepository.removeImportedFolder(folder).forEach {
                scoreHistoryRepository.resetAllScores(it)
                playlistRepository.removeMelodyFromAll(it)
            }
        }
    }


    fun onVerticalZoom(scaleFactor: Float) {
        _viewState.update { current ->
            val newSpeed = (current.fallSpeedPxPerSec * scaleFactor)
                .coerceIn(MIN_FALL_SPEED, MAX_FALL_SPEED)
            current.copy(fallSpeedPxPerSec = newSpeed)
        }
        saveMelodySettings()
    }

    fun onVerticalScroll(deltaSeconds: Double) {
        if (_gameState.value.previewTimeSeconds != null || _gameState.value.replayTimeSeconds != null) return
        val phase = _gameState.value.phase
        if (phase == PlayPhase.PLAYING || phase == PlayPhase.WAITING_FOR_NOTE) return
        val currentOffset = _viewState.value.songTimeOffsetSeconds
        seekToViewTime(_gameState.value.currentTimeSeconds + currentOffset + deltaSeconds)
    }

    fun seekToViewTime(viewTimeSeconds: Double) {
        if (_gameState.value.previewTimeSeconds != null || _gameState.value.replayTimeSeconds != null) return
        val phase = _gameState.value.phase
        if (phase == PlayPhase.PLAYING || phase == PlayPhase.WAITING_FOR_NOTE) return
        val gameSnapshot = _gameState.value
        val track = gameSnapshot.melodyTrack ?: return
        val currentTimeSeconds = gameSnapshot.currentTimeSeconds
        val timeSig = track.timeSignatureAt(0L)
        val firstTempoUs = track.tempoChanges.firstOrNull()?.microsecondsPerBeat ?: 500_000L
        val barDurationSeconds = timeSig.numerator * firstTempoUs / 1_000_000.0
        val minOffset = -2.0 * barDurationSeconds - currentTimeSeconds
        val newOffset = (viewTimeSeconds - currentTimeSeconds)
            .coerceIn(minOffset, track.durationSeconds - currentTimeSeconds + 2.0)
        _viewState.update { it.copy(songTimeOffsetSeconds = newOffset) }
    }

    fun scrollToBeginning() {
        val track = _gameState.value.melodyTrack ?: return
        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }
    }


    private fun beginWait() {
        if (waitStartWallMs == 0L) waitStartWallMs = SystemClock.elapsedRealtime()
    }

    private fun endWait() {
        if (waitStartWallMs == 0L) return
        waitMs += SystemClock.elapsedRealtime() - waitStartWallMs
        waitStartWallMs = 0L
    }

    private fun startPlaybackTimer() {
        if (!devModeState.isEnabled) melodyPlayer.stop()
        playbackStartWallMs = SystemClock.elapsedRealtime()
        playbackJob = viewModelScope.launch {
            val track = _gameState.value.melodyTrack ?: return@launch
            val countInTracks = leadInMetronome(_gameState.value.metronomeTrack)
            var pitchDetectionStarted = false
            var lastStateUpdate = 0L

            while (isActive) {
                if (noteTracking.isWaitingForNote) {
                    beginWait()
                    delay(16)
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - playbackStartWallMs) / 1000.0
                val currentTime = pauseOffset + elapsed

                if (!pitchDetectionStarted && currentTime >= 0.0) {
                    pitchDetectionStarted = true
                    startPitchDetection()
                }

                if (currentTime >= track.durationSeconds + 1.0) {
                    finishPlaying()
                    break
                }

                if (now - lastStateUpdate >= 33L) {
                    lastStateUpdate = now
                    _gameState.update { it.copy(currentTimeSeconds = currentTime) }
                    recordTimelinePoint(currentTime)
                }

                if (pitchDetectionStarted) {
                    updateCurrentNote(currentTime)
                }

                val gs = _gameState.value

                val playTracks = buildList {
                    addAll(
                        audibleMelodyTracks(
                            melodyTracks = gs.melodyTracks,
                            activeVoiceIndex = gs.activeVoiceIndex,
                            accompanimentMode = gs.accompanimentMode,
                            devModeEnabled = devModeState.isEnabled
                        )
                    )
                    addAll(metronomeTracks(gs.metronomeMode, gs.waitMode, gs.metronomeTrack, countInTracks))
                }
                if (soundFontSynthesizer.isReady) {
                    melodyPlayer.updateGameTime(currentTime + audioLeadSeconds(audioDelayMs), playTracks)
                }

                delay(16)
            }
        }
    }

    private fun pitchResultFromMidi(midiNote: Int): PitchResult {
        val resolution = NoteUtils.resolveMidiNote(midiNote)
        return PitchResult(
            frequency = NoteUtils.midiToFrequency(midiNote, referenceA4),
            noteName = resolution.noteName,
            octave = resolution.octave,
            centsOff = 0f,
            midiNoteNumber = midiNote,
            isSilent = false
        )
    }

    private fun startPitchDetection() {
        if (pitchJob?.isActive == true) return
        val previous = pitchJob
        pitchJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            if (_deviceState.value.midiInputEnabled) {
                val heldNotes = mutableSetOf<Int>()
                midiInputReceiver.midiEventFlow().collect { event ->
                    when (event) {
                        is MidiNoteEvent.NoteOn -> {
                            heldNotes.add(event.noteNumber)
                            val result = pitchResultFromMidi(event.noteNumber)
                            latestPitchResult = result
                            latestPitchAtMs = SystemClock.elapsedRealtime()
                            _gameState.update { it.copy(pitchResult = result) }
                            for (idx in currentChordIndices) {
                                updateNoteProgress(idx, result.frequency)
                            }
                            evaluateNote(result)
                        }
                        is MidiNoteEvent.NoteOff -> {
                            heldNotes.remove(event.noteNumber)
                            if (heldNotes.isEmpty()) {
                                latestPitchResult = PitchResult.SILENT
                                latestPitchAtMs = SystemClock.elapsedRealtime()
                                _gameState.update { it.copy(pitchResult = PitchResult.SILENT) }
                            } else {
                                val result = pitchResultFromMidi(heldNotes.last())
                                latestPitchResult = result
                                latestPitchAtMs = SystemClock.elapsedRealtime()
                                _gameState.update { it.copy(pitchResult = result) }
                            }
                        }
                    }
                }
            } else {
                var lastPitchUiUpdate = 0L
                pitchProcessor.pitchFlow().collect { result ->
                    latestPitchResult = result
                    val now = SystemClock.elapsedRealtime()
                    latestPitchAtMs = now
                    val updateUi = now - lastPitchUiUpdate >= 33L
                    if (updateUi) {
                        lastPitchUiUpdate = now
                        _gameState.update { it.copy(pitchResult = result) }
                    }
                    if (result.isValid) {
                        if (updateUi) {
                            for (idx in currentChordIndices) {
                                updateNoteProgress(idx, result.frequency)
                            }
                        }
                        evaluateNote(result)
                    } else {
                        noteTracking.lastPitchAtMs = 0L
                        noteTracking.lastInTuneAtMs = 0L
                    }
                }
            }
        }
    }

    private fun recordTimelinePoint(gameSeconds: Double) {
        if (!playbackRecorder.isRecording()) return
        val audioMs = playbackRecorder.elapsedMs()
        val last = takeTimeline.lastOrNull()
        if (last != null && audioMs - last.audioMs < TIMELINE_SAMPLE_MS) return
        takeTimeline.add(TakeTimelinePoint(audioMs, gameSeconds))
    }

    private fun recordEarlyOnset(result: PitchResult, track: MelodyTrack) {
        if (!result.isValid || !playbackRecorder.isRecording()) return
        if (_deviceState.value.midiInputEnabled || adventureSession.turn.value != null) return
        if (noteTracking.lastPitchAtMs == 0L) return
        val now = _gameState.value.currentTimeSeconds
        for ((index, note) in track.notes.withIndex()) {
            val start = track.noteStartSeconds(note)
            if (start <= now || start > now + EARLY_ONSET_LOOKAHEAD_SECONDS) continue
            if (note.noteNumber != result.midiNoteNumber || index in takeNoteOffsets) continue
            takeNoteOffsets[index] = ((now - start) * 1000).toLong()
            return
        }
    }

    private fun updateCurrentNote(currentTime: Double) {
        val gs = _gameState.value
        val track = gs.melodyTrack ?: return
        val adjustedTime = currentTime

        val activeIndices = track.activeNoteIndicesAt(adjustedTime)

        if (activeIndices != currentChordIndices) {
            finalizePreviousNote()

            val results = _gameState.value.noteResults.toMutableMap()
            for (idx in activeIndices) results[idx] = NoteResultState.ACTIVE
            _gameState.update {
                it.copy(
                    currentNoteIndex = activeIndices.firstOrNull() ?: -1,
                    noteResults = results
                )
            }
            currentChordIndices = activeIndices
            currentNoteDetectedCorrectly = false
            noteTracking.resetForNewNote()

            if (activeIndices.isNotEmpty() && _gameState.value.waitMode) {
                val pitch = latestPitchResult
                val fresh = pitch.isValid &&
                    SystemClock.elapsedRealtime() - latestPitchAtMs <= FRESH_PITCH_MS
                val alreadyPlaying = fresh && activeIndices.any { index ->
                    val note = track.notes.getOrNull(index) ?: return@any false
                    NoteUtils.isPitchAccepted(
                        noteUtils.frequencyToCents(
                            pitch.frequency,
                            NoteUtils.midiToFrequency(note.noteNumber, referenceA4)
                        ),
                        _gameState.value.currentLevel
                    )
                }
                if (alreadyPlaying) {
                    currentNoteDetectedCorrectly = true
                    noteTracking.isWaitingForNote = false
                } else {
                    val noteStart = activeIndices.minOf { track.noteStartSeconds(track.notes[it]) }
                    noteTracking.isWaitingForNote = true
                    beginWait()
                    pauseOffset = noteStart
                    playbackStartWallMs = SystemClock.elapsedRealtime()
                    _gameState.update {
                        it.copy(currentTimeSeconds = noteStart, phase = PlayPhase.WAITING_FOR_NOTE)
                    }
                }
            }
        }
    }

    private fun evaluateNote(result: PitchResult) {
        val track = _gameState.value.melodyTrack ?: return
        val expectedNotes = currentChordIndices.mapNotNull { track.notes.getOrNull(it) }
        if (expectedNotes.isEmpty()) {
            recordEarlyOnset(result, track)
            return
        }
        val onsetIndex = currentChordIndices.firstOrNull { track.notes.getOrNull(it)?.noteNumber == result.midiNoteNumber }
        if (result.isValid && onsetIndex != null && onsetIndex !in takeNoteOffsets) {
            val noteStart = track.noteStartSeconds(track.notes[onsetIndex])
            takeNoteOffsets[onsetIndex] = ((_gameState.value.currentTimeSeconds - noteStart) * 1000).toLong()
        }
        val centsOff = expectedNotes.minOf { note ->
            abs(noteUtils.frequencyToCents(result.frequency, NoteUtils.midiToFrequency(note.noteNumber, referenceA4)))
        }
        val isCorrectNote = NoteUtils.isPitchAccepted(centsOff, _gameState.value.currentLevel)
        val frameDurationMs = AudioConstants.FRAME_DURATION_MS

        if (result.isValid) {
            noteTracking.detectedPitchFrameCount++
            val now = SystemClock.elapsedRealtime()
            noteTracking.detectedPitchMs += if (noteTracking.lastPitchAtMs == 0L) {
                frameDurationMs
            } else {
                (now - noteTracking.lastPitchAtMs).coerceIn(frameDurationMs, 150L)
            }
            noteTracking.lastPitchAtMs = now
            noteTracking.centsOffSum += abs(centsOff)
            noteTracking.centsOffCount++
            if (isCorrectNote) {
                noteTracking.inTuneFrameCount++
                noteTracking.inTuneMs += if (noteTracking.lastInTuneAtMs == 0L) {
                    frameDurationMs
                } else {
                    (now - noteTracking.lastInTuneAtMs).coerceIn(frameDurationMs, 150L)
                }
                noteTracking.lastInTuneAtMs = now
            } else {
                noteTracking.lastInTuneAtMs = 0L
            }
        }

        if (noteTracking.isWaitingForNote) {
            if (isCorrectNote) {
                currentNoteDetectedCorrectly = true
                noteTracking.isWaitingForNote = false
                endWait()
                pauseOffset = _gameState.value.currentTimeSeconds
                playbackStartWallMs = SystemClock.elapsedRealtime()
                _gameState.update { it.copy(phase = PlayPhase.PLAYING) }
            }
        } else {
            if (isCorrectNote && !currentNoteDetectedCorrectly) {
                currentNoteDetectedCorrectly = true
            }
        }
    }

    private fun finalizePreviousNote() {
        if (currentChordIndices.isEmpty()) return
        val track = _gameState.value.melodyTrack ?: return
        for (noteIndex in currentChordIndices) {
            if (noteIndex !in track.notes.indices) continue

            val existingResult = _gameState.value.noteResults[noteIndex]
            if (existingResult != null && existingResult != NoteResultState.ACTIVE) continue

            val expectedNote = track.notes[noteIndex]
            scoreCurrentNote(expectedNote, noteIndex)
        }
    }

    private fun updateNoteProgress(noteIndex: Int, frequency: Float) {
        _gameState.update { current ->
            val track = current.melodyTrack ?: return@update current
            if (noteIndex < 0 || noteIndex >= track.notes.size) return@update current
            val note = track.notes[noteIndex]
            val noteDurationSeconds = track.noteDurationSeconds(note)
            if (noteDurationSeconds <= 0.0) return@update current
            val noteStartSeconds = track.noteStartSeconds(note)
            val deviation = noteUtils.frequencyToCents(
                frequency,
                NoteUtils.midiToFrequency(note.noteNumber, referenceA4)
            )
            val pos = ((current.currentTimeSeconds - noteStartSeconds) / noteDurationSeconds).toFloat().coerceIn(0f, 1f)
            val existingHistory = current.noteCentsHistory[noteIndex] ?: emptyList()
            val existingPositions = current.noteCentsPositions[noteIndex] ?: emptyList()
            val updatedFirstDetection = if (!current.noteFirstDetectionStart.containsKey(noteIndex)) {
                current.noteFirstDetectionStart + (noteIndex to pos)
            } else {
                current.noteFirstDetectionStart
            }
            current.copy(
                noteCentsHistory = current.noteCentsHistory + (noteIndex to (existingHistory + deviation)),
                noteCentsPositions = current.noteCentsPositions + (noteIndex to (existingPositions + pos)),
                notePlayProgress = current.notePlayProgress + (noteIndex to pos),
                noteFirstDetectionStart = updatedFirstDetection
            )
        }
    }

    private fun scoreCurrentNote(expectedNote: NoteEvent, noteIndex: Int) {
        val track = _gameState.value.melodyTrack ?: return
        val level = _gameState.value.currentLevel
        val expectedDurationMs = (track.noteDurationSeconds(expectedNote) * 1000).toLong()
        val isMidi = _deviceState.value.midiInputEnabled
        val frameDurationMs = AudioConstants.FRAME_DURATION_MS
        val detectedDurationMs = if (isMidi) noteTracking.detectedPitchFrameCount * frameDurationMs else noteTracking.detectedPitchMs
        val inTuneMs = if (isMidi) noteTracking.inTuneFrameCount * frameDurationMs else noteTracking.inTuneMs

        val playedDurationMs = if (level <= 2) detectedDurationMs else inTuneMs
        val score = if (level <= 2) {
            val durationScore = scoringEngine.scoreDuration(playedDurationMs, expectedDurationMs, level)
            NoteScore(
                pitchScore = 100f,
                durationScore = durationScore,
                overallScore = durationScore
            )
        } else {
            val effectiveCentsOff = if (noteTracking.centsOffCount > 0) noteTracking.centsOffSum / noteTracking.centsOffCount else 100f
            val performance = NotePerformance(
                centsOff = effectiveCentsOff,
                expectedDurationMs = expectedDurationMs,
                actualDurationMs = playedDurationMs
            )
            scoringEngine.scoreNote(performance, level)
        }

        val actualDurationMs = playedDurationMs.coerceAtLeast(1L)
        val resultState = if (currentNoteDetectedCorrectly) NoteResultState.HIT else NoteResultState.MISS

        _gameState.update { current ->
            val results = current.noteResults.toMutableMap()
            results[noteIndex] = resultState
            val finalProgress = (actualDurationMs.toFloat() / expectedDurationMs.toFloat()).coerceIn(0f, 1f)
            current.copy(
                noteResults = results,
                noteScores = current.noteScores + score,
                notePlayProgress = current.notePlayProgress + (noteIndex to finalProgress)
            )
        }
    }

    private fun finishPlaying() {
        finalizePreviousNote()

        val track = _gameState.value.melodyTrack ?: return
        val runLevel = _gameState.value.currentLevel
        val currentResults = _gameState.value.noteResults
        for (i in track.notes.indices) {
            if (i !in currentResults) {
                val note = track.notes[i]
                val expectedDurationMs = (track.noteDurationSeconds(note) * 1000).toLong()
                val performance = NotePerformance(
                    centsOff = 100f,
                    expectedDurationMs = expectedDurationMs,
                    actualDurationMs = 0
                )
                val score = scoringEngine.scoreNote(performance, runLevel)
                _gameState.update { current ->
                    val results = current.noteResults.toMutableMap()
                    results[i] = NoteResultState.MISS
                    current.copy(
                        noteResults = results,
                        noteScores = current.noteScores + score
                    )
                }
            }
        }

        playbackJob?.cancel()
        pitchJob?.cancel()
        melodyPlayer.resetGameSync()
        pauseOffset = 0.0
        noteTracking.isWaitingForNote = false
        endWait()

        val noteScores = _gameState.value.noteScores
        val melodyScore = scoringEngine.calculateMelodyScore(noteScores)

        val currentLevel = _gameState.value.currentLevel
        val timeRatio = songTimeRatio(track, waitMs)
        val timeScore = ScoringEngine.scoreTime(timeRatio, currentLevel)

        val adjustedScore = ScoringEngine.combineScores(
            noteScore = melodyScore.overallScore,
            timeScore = timeScore,
            level = currentLevel
        )
        val adjustedMelodyScore = melodyScore.copy(
            overallScore = adjustedScore,
            timeScore = timeScore
        )

        val unlockedLevel = _gameState.value.unlockedLevel
        val previousUnlocked = unlockedLevel
        val newUnlockedLevel = ScoringEngine.unlockedLevelAfterRun(currentLevel, unlockedLevel, adjustedScore)
        val nextLevel = if (newUnlockedLevel > unlockedLevel) newUnlockedLevel else currentLevel
        val (waitMode, hideNoteBars) = levelToSettings(nextLevel)

        val firstNoteStart = track.notes.firstOrNull()?.let { track.noteStartSeconds(it) } ?: 0.0
        val currentTimeSeconds = _gameState.value.currentTimeSeconds

        _gameState.update {
            it.copy(
                phase = PlayPhase.FINISHED,
                melodyScore = adjustedMelodyScore,
                playedLevel = currentLevel,
                currentLevel = nextLevel,
                unlockedLevel = newUnlockedLevel,
                newlyUnlockedLevel = newUnlockedLevel.takeIf { unlocked -> unlocked > unlockedLevel },
                waitMode = waitMode,
                hideNoteBars = hideNoteBars
            )
        }
        _viewState.update { it.copy(songTimeOffsetSeconds = firstNoteStart - currentTimeSeconds) }

        if (playbackRecorder.isRecording()) {
            val savedTake = playbackRecorder.stopRecording(
                save = true,
                timeline = takeTimeline.toList(),
                noteOffsetsMs = takeNoteOffsets.toMap()
            )
            _recordingAvailable.value = savedTake != null
        }
        takeTimeline.clear()
        takeNoteOffsets.clear()

        val melodyId = currentMelodyId
        val adventureTurn = adventureSession.turn.value
        if (adventureTurn != null && melodyId != null) {
            persistAdventureRun(
                turn = adventureTurn,
                melodyId = melodyId,
                rawScore = adjustedScore,
                melodyScore = adjustedMelodyScore,
                level = currentLevel,
                unlockedLevel = newUnlockedLevel,
                nextLevel = nextLevel,
                previousUnlocked = previousUnlocked
            )
            return
        }

        saveMelodySettings()

        if (melodyId != null) {
            val existing = currentScoreHistory.value
            val previousBestScore = ScoringEngine.bestScoreForLevel(
                existing.map { it.level to it.overallScore },
                currentLevel
            )
            val scoreImproved = ScoringEngine.improvesPersonalBest(adjustedScore, previousBestScore)
            _personalBestScore.value = maxOf(previousBestScore, adjustedScore)
            _celebrationLevel.value = if (scoreImproved) CelebrationLevel.fromScore(adjustedScore)
            else CelebrationLevel.NONE

            viewModelScope.launch {
                delay(4000)
                _celebrationLevel.value = CelebrationLevel.NONE
            }

            viewModelScope.launch {
                scoreHistoryRepository.saveScore(
                    melodyId,
                    ScoreHistoryRepository.ScoreEntry.from(
                        score = adjustedMelodyScore,
                        level = currentLevel,
                        instrument = currentInstrumentFlow.value
                    )
                )
            }
        }

        if (_playlistRun.value != null) {
            finishPlaylistSong(
                PlaylistResult(
                    id = melodyId.orEmpty(),
                    name = _gameState.value.melodyName.orEmpty(),
                    level = currentLevel,
                    score = adjustedScore,
                    newLevel = newUnlockedLevel.takeIf { it > unlockedLevel }
                )
            )
        }
    }

    private fun persistAdventureRun(
        turn: AdventureTurn,
        melodyId: String,
        rawScore: Float,
        melodyScore: MelodyScore,
        level: Int,
        unlockedLevel: Int,
        nextLevel: Int,
        previousUnlocked: Int
    ) {
        val instrument = currentInstrumentFlow.value
        viewModelScope.launch {
            scoreHistoryRepository.saveScore(
                turn.playerId,
                melodyId,
                ScoreHistoryRepository.ScoreEntry.from(
                    score = melodyScore,
                    overallScore = rawScore,
                    level = level,
                    instrument = instrument
                )
            )
            val settings = melodySettingsRepository.getSettings(turn.playerId, melodyId, instrument).first()
            melodySettingsRepository.saveSettings(
                turn.playerId,
                melodyId,
                instrument,
                settings.copy(level = nextLevel, unlockedLevel = unlockedLevel)
            )
            adventureSession.complete(
                AdventureTurnResult(
                    turn = turn,
                    rawScore = rawScore,
                    unlockedLevel = unlockedLevel,
                    newlyUnlockedLevel = unlockedLevel.takeIf { it > previousUnlocked }
                )
            )
        }
    }

    override fun onCleared() {
        playbackStartJob?.cancel()
        playbackJob?.cancel()
        pitchJob?.cancel()
        runStartJob?.cancel()
        adventureStartJob?.cancel()
        melodyPlayer.stop()
        if (playbackRecorder.isRecording()) playbackRecorder.stopRecording(false, emptyList(), emptyMap())
        playbackRecorder.stopPlayback()
        soundFontSynthesizer.close()
    }
}

private class NoteTrackingState {
    var centsOffSum = 0f
    var centsOffCount = 0
    var inTuneFrameCount = 0
    var detectedPitchFrameCount = 0
    var inTuneMs = 0L
    var detectedPitchMs = 0L
    var lastPitchAtMs = 0L
    var lastInTuneAtMs = 0L
    var isWaitingForNote = false

    fun reset() {
        resetPitchAccumulators()
        isWaitingForNote = false
    }

    fun resetForNewNote() = resetPitchAccumulators()

    private fun resetPitchAccumulators() {
        centsOffSum = 0f
        centsOffCount = 0
        inTuneFrameCount = 0
        detectedPitchFrameCount = 0
        inTuneMs = 0L
        detectedPitchMs = 0L
        lastPitchAtMs = 0L
        lastInTuneAtMs = 0L
    }
}
