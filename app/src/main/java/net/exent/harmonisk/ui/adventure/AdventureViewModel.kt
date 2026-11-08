package net.exent.harmonisk.ui.adventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.exent.harmonisk.R
import net.exent.harmonisk.data.MelodyRepository
import net.exent.harmonisk.data.MelodySettingsRepository
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.music.Instrument
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.random.Random

enum class AdventureStep { PLAYERS, SONGS, MAP, PLAY, WHACK, RESULTS }

data class AdventureState(
    val step: AdventureStep = AdventureStep.PLAYERS,
    val selectedPlayerIds: Set<String> = emptySet(),
    val selectedSongIds: Set<String> = emptySet(),
    val players: List<AdventurePlayer> = emptyList(),
    val currentPlayerIndex: Int = 0,
    val currentSong: MelodyRepository.MelodyInfo? = null,
    val songPool: List<MelodyRepository.MelodyInfo> = emptyList(),
    val phase: AdventureTurnPhase = AdventureTurnPhase.ROLLING,
    val diceValue: Int? = null,
    val goAgain: Boolean = false,
    val moveFrom: Int = 0,
    val spinDurationMs: Long = AdventureTiming.SPIN_MS,
    val currentLevel: Int = 1,
    val mapSeed: Long = 0L,
    val fieldRewards: List<AdventureReward?> = emptyList(),
    val reward: AdventureReward? = null,
    val faerie: AdventureFaerie? = null,
    val faerieGiven: Boolean = false,
    val finisherId: String? = null,
    val levelUp: Int? = null,
    val turnScore: Int? = null,
    val loading: Boolean = false,
    val error: Int? = null,
    val turnId: Long = 0
) {
    val currentPlayer: AdventurePlayer? get() = players.getOrNull(currentPlayerIndex)
    val solo: Boolean get() = players.size == 1
}

@HiltViewModel
class AdventureViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val melodyRepository: MelodyRepository,
    private val melodySettingsRepository: MelodySettingsRepository,
    private val settingsRepository: SettingsRepository,
    private val session: AdventureSessionRepository
) : ViewModel() {

    private val random = Random.Default
    private val turnIds = AtomicLong(0)

    private val _state = MutableStateFlow(AdventureState())
    val state: StateFlow<AdventureState> = _state.asStateFlow()

    val profiles: StateFlow<List<ProfileRepository.Profile>> = profileRepository.profiles

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPlayerInstrument: StateFlow<Instrument> = _state
        .map { it.currentPlayer?.profileId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(Instrument.VIOLIN) else settingsRepository.instrument(id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Instrument.VIOLIN)

    val melodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        melodyRepository.importedMelodies
            .map { melodyRepository.getBundledMelodies() + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, melodyRepository.getBundledMelodies())

    private var songPool: List<MelodyRepository.MelodyInfo> = emptyList()
    private var turnJob: Job? = null
    private var spinJob: Job? = null
    private var resultJob: Job? = null
    private var faerieJob: Job? = null
    private var pendingLanding: AdventureLanding? = null
    private var lastResultId = 0L

    init {
        viewModelScope.launch {
            session.result.collect { result ->
                if (result != null && result.turn.id != lastResultId) {
                    lastResultId = result.turn.id
                    onResult(result)
                }
            }
        }
    }

    fun togglePlayer(profileId: String) {
        _state.update { current ->
            val selected = current.selectedPlayerIds
            when {
                profileId in selected -> current.copy(selectedPlayerIds = selected - profileId)
                selected.size >= 4 -> current
                else -> current.copy(selectedPlayerIds = selected + profileId)
            }
        }
    }

    fun createPlayer(name: String, emoji: String): Boolean {
        val profile = profileRepository.createProfile(name, emoji, makeActive = false) ?: return false
        viewModelScope.launch {
            settingsRepository.updateInstrument(
                profile.id,
                settingsRepository.settings.first().instrumentName
            )
        }
        if (_state.value.selectedPlayerIds.size < 4) {
            _state.update {
                it.copy(selectedPlayerIds = it.selectedPlayerIds + profile.id)
            }
        }
        return true
    }

    fun goToSongs() {
        if (_state.value.selectedPlayerIds.isNotEmpty()) {
            _state.update { it.copy(step = AdventureStep.SONGS) }
        }
    }

    fun backToPlayers() {
        _state.update { it.copy(step = AdventureStep.PLAYERS) }
    }

    fun toggleSong(songId: String) {
        _state.update { current ->
            val selected = current.selectedSongIds
            current.copy(selectedSongIds = if (songId in selected) selected - songId else selected + songId)
        }
    }

    fun setSongs(songIds: Collection<String>, selected: Boolean) {
        _state.update { current ->
            current.copy(
                selectedSongIds = if (selected) current.selectedSongIds + songIds
                else current.selectedSongIds - songIds.toSet()
            )
        }
    }

    fun startGame() {
        val snapshot = _state.value
        val selectedProfiles = profiles.value.filter { it.id in snapshot.selectedPlayerIds }
        val selectedSongs = melodies.value.filter { it.id in snapshot.selectedSongIds }
        if (selectedProfiles.isEmpty() || selectedSongs.isEmpty()) return

        pendingLanding = null
        faerieJob?.cancel()
        faerieJob = null
        songPool = selectedSongs
        val players = selectedProfiles.map { profile ->
            AdventurePlayer(
                profileId = profile.id,
                name = profile.name,
                colorIndex = profiles.value.indexOf(profile),
                emoji = profile.emoji,
                position = 0,
                songQueue = selectedSongs.map { it.id }.shuffled(random)
            )
        }
        val mapSeed = random.nextLong()
        val first = random.nextInt(players.size)
        _state.update {
            it.copy(
                step = AdventureStep.MAP,
                players = players,
                currentPlayerIndex = first,
                currentSong = null,
                songPool = selectedSongs,
                phase = AdventureTurnPhase.ROLLING,
                diceValue = null,
                goAgain = false,
                moveFrom = players[first].position,
                spinDurationMs = AdventureTiming.SPIN_MS,
                fieldRewards = generateFieldRewards(Random(mapSeed)),
                currentLevel = 1,
                mapSeed = mapSeed,
                reward = null,
                faerie = null,
                faerieGiven = false,
                finisherId = null,
                levelUp = null,
                turnScore = null,
                loading = false,
                error = null
            )
        }
    }

    fun rollDice() {
        val snapshot = _state.value
        if (snapshot.phase != AdventureTurnPhase.ROLLING || snapshot.loading) return
        val player = snapshot.currentPlayer ?: return
        val dice = random.nextInt(1, 7)
        _state.update { it.copy(diceValue = dice, phase = AdventureTurnPhase.DICE) }
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            delay(AdventureTiming.DICE_SHOW_MS + AdventureTiming.DICE_PAUSE_MS)
            _state.update { it.copy(phase = AdventureTurnPhase.DELIVERING) }
            delay(AdventureTiming.DICE_HANDOFF_MS)
            val landing = resolveLanding(player, dice, snapshot.fieldRewards)
            val players = snapshot.players.toMutableList().also { it[snapshot.currentPlayerIndex] = landing.player }
            _state.update {
                it.copy(
                    players = players,
                    phase = AdventureTurnPhase.MOVING,
                    moveFrom = player.position
                )
            }
            delay(AdventureTiming.walkMs(dice))
            revealLanding(landing)
        }
    }

    private fun revealLanding(landing: AdventureLanding) {
        val snapshot = _state.value
        val index = snapshot.currentPlayerIndex
        val player = snapshot.players.getOrNull(index) ?: return
        if (landing.reachedGoal) {
            val finished = player.withBonus(ADVENTURE_CASTLE_BONUS)
            _state.update {
                it.copy(
                    players = snapshot.players.toMutableList().also { list -> list[index] = finished },
                    phase = AdventureTurnPhase.EVENT,
                    reward = null,
                    finisherId = finished.profileId
                )
            }
            resultJob?.cancel()
            resultJob = viewModelScope.launch {
                delay(AdventureTiming.CASTLE_CELEBRATION_MS)
                _state.update { it.copy(step = AdventureStep.RESULTS) }
            }
            return
        }
        val reward = landing.reward
        val landed = if (reward is AdventureReward.Stickers) player.withReward(reward) else player
        val players = snapshot.players.toMutableList().also { it[index] = landed }
        val crossedHalfway = !snapshot.faerieGiven && players.all { it.position >= ADVENTURE_HALFWAY }
        val faerie = if (crossedHalfway) faerieGift(players) else null
        if (faerie != null) {
            val recipient = players.indexOfFirst { it.profileId == faerie.playerId }
            if (recipient >= 0) players[recipient] = players[recipient].withBonus(faerie.points)
        }
        _state.update {
            it.copy(
                players = players,
                faerie = faerie,
                faerieGiven = snapshot.faerieGiven || crossedHalfway
            )
        }
        if (faerie == null) {
            finishLanding(landing)
            return
        }
        pendingLanding = landing
        faerieJob?.cancel()
        faerieJob = viewModelScope.launch {
            delay(AdventureTiming.FAERIE_TIMEOUT_MS)
            finishFaerie()
        }
    }

    fun finishFaerie() {
        val landing = pendingLanding
        if (_state.value.faerie == null) return
        pendingLanding = null
        faerieJob?.cancel()
        faerieJob = null
        _state.update { it.copy(faerie = null) }
        if (landing != null) finishLanding(landing) else beginTurn()
    }

    private fun finishLanding(landing: AdventureLanding) {
        val snapshot = _state.value
        val index = snapshot.currentPlayerIndex
        val player = snapshot.players.getOrNull(index) ?: return
        when (val reward = landing.reward) {
            is AdventureReward.Stickers -> {
                _state.update { it.copy(phase = AdventureTurnPhase.EVENT, reward = reward) }
                scheduleRewardAdvance(reward)
            }
            AdventureReward.GoAgain -> {
                _state.update {
                    it.copy(phase = AdventureTurnPhase.EVENT, reward = AdventureReward.GoAgain)
                }
                scheduleRewardAdvance(AdventureReward.GoAgain)
            }
            AdventureReward.Whack -> {
                _state.update {
                    it.copy(step = AdventureStep.WHACK, phase = AdventureTurnPhase.EVENT, reward = null)
                }
            }
            AdventureReward.Song -> startSongTurn(player)
            null -> {
                val next = nextPlayerIndex(index, snapshot.players.size)
                _state.update { it.copy(currentPlayerIndex = next, currentSong = null) }
                beginTurn()
            }
        }
    }

    private fun startSongTurn(player: AdventurePlayer) {
        val snapshot = _state.value
        val index = snapshot.currentPlayerIndex
        val (songId, withSong) = nextSong(player, songPool.map { it.id }, random)
        val song = songPool.find { it.id == songId }
        if (song == null) {
            val next = nextPlayerIndex(index, snapshot.players.size)
            _state.update { it.copy(currentPlayerIndex = next) }
            beginTurn()
            return
        }
        val players = snapshot.players.toMutableList().also { it[index] = withSong }
        val spinDuration = AdventureTiming.SPIN_MS + random.nextLong(AdventureTiming.SPIN_EXTRA_MS + 1)
        _state.update {
            it.copy(
                players = players,
                currentSong = song,
                phase = AdventureTurnPhase.PULLING,
                spinDurationMs = spinDuration,
                currentLevel = 1,
                loading = true,
                step = AdventureStep.MAP,
                reward = null
            )
        }
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            try {
                val instrument = settingsRepository.instrument(player.profileId).first().name
                val level = melodySettingsRepository
                    .getSettings(player.profileId, song.id, instrument)
                    .first()
                    .unlockedLevel
                    .coerceIn(1, 5)
                _state.update { it.copy(currentLevel = level, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun scheduleRewardAdvance(reward: AdventureReward) {
        resultJob?.cancel()
        resultJob = viewModelScope.launch {
            delay(AdventureTiming.REWARD_TOTAL_MS)
            _state.update { it.copy(reward = null) }
            advanceAfterReward(reward is AdventureReward.GoAgain)
        }
    }

    private fun advanceAfterReward(goAgain: Boolean) {
        val snapshot = _state.value
        if (goAgain) {
            beginTurn(goAgain = true)
        } else {
            val next = nextPlayerIndex(snapshot.currentPlayerIndex, snapshot.players.size)
            _state.update { it.copy(currentPlayerIndex = next) }
            beginTurn()
        }
    }

    private fun beginTurn(goAgain: Boolean = false) {
        spinJob?.cancel()
        turnJob?.cancel()
        val snapshot = _state.value
        val player = snapshot.players.getOrNull(snapshot.currentPlayerIndex) ?: return
        _state.update {
            it.copy(
                step = AdventureStep.MAP,
                phase = AdventureTurnPhase.ROLLING,
                diceValue = null,
                goAgain = goAgain,
                moveFrom = player.position,
                currentSong = null,
                currentLevel = 1,
                spinDurationMs = AdventureTiming.SPIN_MS,
                reward = null,
                levelUp = null,
                turnScore = null,
                loading = false
            )
        }
    }

    fun playCurrentSong() {
        val snapshot = _state.value
        if (snapshot.phase != AdventureTurnPhase.REVEALED) return
        if (snapshot.reward != null || snapshot.finisherId != null) return
        val song = snapshot.currentSong ?: return
        val player = snapshot.currentPlayer ?: return
        val turn = AdventureTurn(
            id = turnIds.incrementAndGet(),
            playerId = player.profileId,
            melodyId = song.id,
            level = snapshot.currentLevel
        )
        session.begin(turn)
        _state.update { it.copy(step = AdventureStep.PLAY, turnId = turn.id) }
    }

    fun pullLever() {
        val snapshot = _state.value
        if (snapshot.phase != AdventureTurnPhase.PULLING || snapshot.loading) return
        if (snapshot.currentSong == null) return
        _state.update { it.copy(phase = AdventureTurnPhase.SPINNING) }
        val spinDuration = snapshot.spinDurationMs
        spinJob?.cancel()
        spinJob = viewModelScope.launch {
            delay(spinDuration)
            _state.update { it.copy(phase = AdventureTurnPhase.HIGHLIGHT) }
            delay(AdventureTiming.REVEAL_MS)
            _state.update { it.copy(phase = AdventureTurnPhase.REVEALED) }
        }
    }

    fun onSongLoadFailed() {
        spinJob?.cancel()
        session.clear()
        _state.update { it.copy(error = R.string.adventure_load_failed) }
        advanceToNextPlayer()
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    fun abandonSong() {
        spinJob?.cancel()
        session.clear()
        resultJob?.cancel()
        advanceToNextPlayer()
    }

    private fun advanceToNextPlayer() {
        _state.update {
            it.copy(currentPlayerIndex = nextPlayerIndex(it.currentPlayerIndex, it.players.size))
        }
        beginTurn()
    }

    fun finishWhack(points: Int) {
        val snapshot = _state.value
        if (snapshot.step != AdventureStep.WHACK) return
        val index = snapshot.currentPlayerIndex
        val player = snapshot.players.getOrNull(index) ?: return
        val gained = points.coerceAtLeast(0)
        val rewarded = player.withBonus(gained)
        val players = snapshot.players.toMutableList().also { list -> list[index] = rewarded }
        _state.update {
            it.copy(
                step = AdventureStep.MAP,
                players = players,
                reward = null,
                currentSong = null,
                phase = AdventureTurnPhase.EVENT,
                turnScore = gained,
                levelUp = null,
                loading = false
            )
        }
        advanceAfterScore(index, players.size)
    }

    fun abandonWhack() {
        if (_state.value.step != AdventureStep.WHACK) return
        advanceToNextPlayer()
    }

    private fun advanceAfterScore(playerIndex: Int, playerCount: Int) {
        resultJob?.cancel()
        resultJob = viewModelScope.launch {
            delay(AdventureTiming.REWARD_TOTAL_MS)
            _state.update { it.copy(currentPlayerIndex = nextPlayerIndex(playerIndex, playerCount)) }
            beginTurn()
        }
    }

    fun restart() {
        resultJob?.cancel()
        turnJob?.cancel()
        spinJob?.cancel()
        faerieJob?.cancel()
        pendingLanding = null
        session.clear()
        _state.value = AdventureState()
    }

    fun clear() {
        resultJob?.cancel()
        turnJob?.cancel()
        spinJob?.cancel()
        faerieJob?.cancel()
        pendingLanding = null
        session.clear()
    }

    private fun onResult(result: AdventureTurnResult) {
        val snapshot = _state.value
        val index = snapshot.players.indexOfFirst { it.profileId == result.turn.playerId }
        if (index < 0) return
        val played = snapshot.players[index].withScore(result.rawScore)
        session.consumeResult()
        _state.update {
            it.copy(
                step = AdventureStep.MAP,
                players = snapshot.players.toMutableList().also { list -> list[index] = played },
                phase = AdventureTurnPhase.EVENT,
                reward = null,
                levelUp = result.newlyUnlockedLevel,
                turnScore = result.rawScore.roundToInt(),
                currentSong = null,
                loading = false
            )
        }
        advanceAfterScore(index, snapshot.players.size)
    }
}
