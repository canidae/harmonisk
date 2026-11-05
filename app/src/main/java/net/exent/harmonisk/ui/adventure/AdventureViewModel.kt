package net.exent.harmonisk.ui.adventure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.random.Random

enum class AdventureStep { PLAYERS, SONGS, MAP, PLAY, RESULTS }

data class AdventureState(
    val step: AdventureStep = AdventureStep.PLAYERS,
    val selectedPlayerIds: Set<String> = emptySet(),
    val selectedSongIds: Set<String> = emptySet(),
    val players: List<AdventurePlayer> = emptyList(),
    val currentPlayerIndex: Int = 0,
    val currentSong: MelodyRepository.MelodyInfo? = null,
    val songPool: List<MelodyRepository.MelodyInfo> = emptyList(),
    val phase: AdventureTurnPhase = AdventureTurnPhase.REVEALED,
    val moveFrom: Int = 0,
    val currentLevel: Int = 1,
    val currentBonusSong: Boolean = false,
    val mapSeed: Long = 0L,
    val reward: AdventureReward? = null,
    val finisherId: String? = null,
    val levelUp: Int? = null,
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

    val melodies: StateFlow<List<MelodyRepository.MelodyInfo>> =
        melodyRepository.importedMelodies
            .map { melodyRepository.getBundledMelodies() + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, melodyRepository.getBundledMelodies())

    private var songPool: List<MelodyRepository.MelodyInfo> = emptyList()
    private var turnJob: Job? = null
    private var resultJob: Job? = null
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
        songPool = melodies.value.filter { it.id in snapshot.selectedSongIds }
        if (selectedProfiles.isEmpty() || songPool.isEmpty()) return

        val players = selectedProfiles.map { profile ->
            AdventurePlayer(
                profileId = profile.id,
                name = profile.name,
                colorIndex = profiles.value.indexOf(profile),
                emoji = profile.emoji,
                position = 1,
                songQueue = songPool.map { it.id }.shuffled(random)
            )
        }
        val first = random.nextInt(players.size)
        _state.update {
            it.copy(
                step = AdventureStep.MAP,
                players = players,
                currentPlayerIndex = first,
                currentSong = null,
                songPool = songPool,
                currentLevel = 1,
                currentBonusSong = false,
                mapSeed = random.nextLong(),
                reward = null,
                finisherId = null,
                levelUp = null,
                loading = true,
                error = null
            )
        }
        beginTurn()
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
            playerName = player.name,
            melodyId = song.id,
            melodyName = song.name,
            level = snapshot.currentLevel,
            bonusSong = snapshot.currentBonusSong
        )
        session.begin(turn)
        _state.update { it.copy(step = AdventureStep.PLAY, turnId = turn.id) }
    }

    fun onSongLoadFailed() {
        session.clear()
        _state.update { it.copy(step = AdventureStep.MAP, phase = AdventureTurnPhase.REVEALED, loading = false, error = R.string.adventure_load_failed) }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    fun abandonSong() {
        session.clear()
        resultJob?.cancel()
        _state.update { it.copy(step = AdventureStep.MAP, phase = AdventureTurnPhase.REVEALED, loading = false) }
    }

    private fun advanceAfterReward(reward: AdventureReward?) {
        val snapshot = _state.value
        val current = snapshot.currentPlayer ?: return
        _state.update { it.copy(reward = null) }
        if (reward is AdventureReward.GoAgain && current.position < ADVENTURE_GOAL) {
            beginTurn(bonus = true)
        } else {
            val next = nextPlayerIndex(snapshot.currentPlayerIndex, snapshot.players.size)
            _state.update {
                it.copy(
                    currentPlayerIndex = next,
                    currentBonusSong = false
                )
            }
            beginTurn()
        }
    }

    fun restart() {
        resultJob?.cancel()
        turnJob?.cancel()
        session.clear()
        _state.update {
            it.copy(
                step = AdventureStep.PLAYERS,
                players = emptyList(),
                currentPlayerIndex = 0,
                currentSong = null,
                songPool = emptyList(),
                phase = AdventureTurnPhase.REVEALED,
                reward = null,
                finisherId = null,
                currentBonusSong = false,
                mapSeed = 0L
            )
        }
    }

    fun clear() {
        resultJob?.cancel()
        turnJob?.cancel()
        session.clear()
    }

    private fun beginTurn(bonus: Boolean = false) {
        turnJob?.cancel()
        val snapshot = _state.value
        val index = snapshot.currentPlayerIndex
        val player = snapshot.players.getOrNull(index) ?: return
        val (songId, withSong) = nextSong(player, songPool.map { it.id }, random)
        val song = songPool.find { it.id == songId }
        if (song == null) {
            _state.update { it.copy(loading = false, error = R.string.adventure_load_failed) }
            return
        }
        val players = snapshot.players.toMutableList().also { it[index] = withSong }
        _state.update {
            it.copy(
                players = players,
                currentSong = song,
                phase = AdventureTurnPhase.PULLING,
                moveFrom = player.position,
                currentLevel = 1,
                currentBonusSong = bonus,
                reward = null,
                levelUp = null,
                loading = true,
                step = AdventureStep.MAP
            )
        }
        turnJob = viewModelScope.launch {
            launch {
                val instrument = settingsRepository.settings.first().instrumentName
                val level = melodySettingsRepository
                    .getSettings(player.profileId, song.id, instrument)
                    .first()
                    .unlockedLevel
                    .coerceIn(1, 5)
                _state.update {
                    it.copy(currentLevel = level, loading = false)
                }
            }
            delay(AdventureTiming.LEVER_MS)
            _state.update { it.copy(phase = AdventureTurnPhase.SPINNING) }
            delay(AdventureTiming.SPIN_MS)
            _state.update { it.copy(phase = AdventureTurnPhase.HIGHLIGHT) }
            delay(AdventureTiming.REVEAL_MS)
            _state.update { it.copy(phase = AdventureTurnPhase.REVEALED) }
        }
    }

    private fun onResult(result: AdventureTurnResult) {
        val snapshot = _state.value
        val index = snapshot.players.indexOfFirst { it.profileId == result.turn.playerId }
        if (index < 0) return
        val played = snapshot.players[index].withScore(result.rawScore)
        val advanced = played.advanced()
        val reachedGoal = advanced.position >= ADVENTURE_GOAL
        val reward = if (reachedGoal) null else rewardFor(advanced.position, random)
        val rewarded = reward?.let { advanced.withReward(it) } ?: advanced
        val finisherId = if (reachedGoal) advanced.profileId else snapshot.finisherId
        session.consumeResult()
        resultJob?.cancel()

        if (reachedGoal) {
            _state.update {
                it.copy(
                    step = AdventureStep.MAP,
                    players = snapshot.players.toMutableList().also { list -> list[index] = advanced },
                    phase = AdventureTurnPhase.MOVING,
                    moveFrom = played.position,
                    reward = null,
                    finisherId = finisherId,
                    levelUp = result.newlyUnlockedLevel,
                    currentBonusSong = result.turn.bonusSong
                )
            }
            resultJob = viewModelScope.launch {
                delay(AdventureTiming.WALK_MS)
                _state.update { it.copy(phase = AdventureTurnPhase.REVEALED) }
                delay(900)
                _state.update { it.copy(step = AdventureStep.RESULTS) }
            }
            return
        }

        _state.update {
            it.copy(
                step = AdventureStep.MAP,
                players = snapshot.players.toMutableList()
                    .also { list -> list[index] = rewarded.copy(position = played.position) },
                phase = AdventureTurnPhase.REVEALED,
                reward = reward,
                finisherId = finisherId,
                levelUp = result.newlyUnlockedLevel,
                currentBonusSong = result.turn.bonusSong
            )
        }
        resultJob = viewModelScope.launch {
            delay(AdventureTiming.REWARD_MS + AdventureTiming.REWARD_FLY_MS + 250L)
            _state.update {
                it.copy(
                    players = _state.value.players.toMutableList().also { list -> list[index] = rewarded },
                    phase = AdventureTurnPhase.MOVING,
                    moveFrom = played.position,
                    reward = null
                )
            }
            delay(AdventureTiming.WALK_MS)
            _state.update { it.copy(phase = AdventureTurnPhase.REVEALED) }
            advanceAfterReward(reward)
        }
    }
}
