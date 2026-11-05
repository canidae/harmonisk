package net.exent.harmonisk.ui.adventure

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AdventureTurn(
    val id: Long,
    val playerId: String,
    val playerName: String,
    val melodyId: String,
    val melodyName: String,
    val level: Int,
    val bonusSong: Boolean
)

data class AdventureTurnResult(
    val turn: AdventureTurn,
    val rawScore: Float,
    val unlockedLevel: Int,
    val newlyUnlockedLevel: Int?
)

@Singleton
class AdventureSessionRepository @Inject constructor() {

    private val _turn = MutableStateFlow<AdventureTurn?>(null)
    val turn: StateFlow<AdventureTurn?> = _turn.asStateFlow()

    private val _result = MutableStateFlow<AdventureTurnResult?>(null)
    val result: StateFlow<AdventureTurnResult?> = _result.asStateFlow()

    fun begin(turn: AdventureTurn) {
        _result.value = null
        _turn.value = turn
    }

    fun complete(result: AdventureTurnResult) {
        _turn.value = null
        _result.value = result
    }

    fun consumeResult() {
        _result.value = null
    }

    fun clear() {
        _turn.value = null
        _result.value = null
    }
}
