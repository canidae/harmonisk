package net.exent.harmonisk.ui.adventure

import kotlin.math.abs
import kotlin.random.Random

const val ADVENTURE_FIELD_COUNT = 5
const val ADVENTURE_GOAL = ADVENTURE_FIELD_COUNT + 1

enum class AdventureTurnPhase { MOVING, PULLING, SPINNING, HIGHLIGHT, REVEALED }

object AdventureTiming {
    const val WALK_MS = 900L
    const val LEVER_MS = 950L
    const val LEVER_HOLD_MS = 500L
    const val SPIN_MS = 2800L
    const val HOLD_MS = 400L
    const val FLY_MS = 600L
    const val REVEAL_MS = HOLD_MS + FLY_MS
    const val REWARD_MS = 2900L
    const val REWARD_FLY_MS = 700L
}

enum class AdventureSticker(val emoji: String, val value: Int) {
    MUSHROOM("🍄", 4),
    ACORN("🌰", 3),
    FEATHER("🪶", 6),
    LANTERN("🏮", 7),
    COMPASS("🧭", 8),
    MAP("🗺️", 9),
    CRYSTAL("💎", 12),
    KEY("🗝️", 14),
    SHIELD("🛡️", 10),
    BOW("🏹", 11),
    POTION("🧪", 8),
    CROWN("👑", 18),
    SCROLL("📜", 7),
    COIN("🪙", 5),
    FLAME("🔥", 9),
    LEAF("🌿", 4),
    ROCK("🪨", 5),
    FLOWER("🌸", 3),
    CLOVER("🍀", 6),
    CANDLE("🕯️", 7)
}

sealed interface AdventureReward {
    data class Stickers(val stickers: List<AdventureSticker>) : AdventureReward
    data class Points(val points: Int) : AdventureReward
    data object GoAgain : AdventureReward
}

enum class AdventureMedal { GOLD, SILVER, BRONZE, NONE }

data class AdventurePlayer(
    val profileId: String,
    val name: String,
    val colorIndex: Int,
    val emoji: String = "🙂",
    val position: Int = 1,
    val songScore: Float = 0f,
    val stickerScore: Float = 0f,
    val bonusScore: Float = 0f,
    val stickers: List<AdventureSticker> = emptyList(),
    val songsPlayed: Int = 0,
    val songQueue: List<String> = emptyList(),
    val queueIndex: Int = 0
) {
    val total: Float get() = songScore + stickerScore + bonusScore
}

data class AdventureStanding(
    val player: AdventurePlayer,
    val rank: Int,
    val medal: AdventureMedal
)

fun AdventurePlayer.advanced(): AdventurePlayer = copy(position = (position + 1).coerceAtMost(ADVENTURE_GOAL))

fun AdventurePlayer.withScore(score: Float): AdventurePlayer = copy(
    songScore = songScore + score,
    songsPlayed = songsPlayed + 1
)

fun AdventurePlayer.withReward(reward: AdventureReward): AdventurePlayer = when (reward) {
    is AdventureReward.Stickers -> copy(
        stickerScore = stickerScore + reward.stickers.sumOf { it.value }.toFloat(),
        stickers = stickers + reward.stickers
    )
    is AdventureReward.Points -> copy(bonusScore = bonusScore + reward.points)
    AdventureReward.GoAgain -> this
}

fun rewardFor(field: Int, random: Random): AdventureReward? {
    if (field >= ADVENTURE_GOAL) return null
    return when (random.nextInt(3)) {
        0 -> AdventureReward.Stickers(AdventureSticker.entries.shuffled(random).take(1 + random.nextInt(3)))
        1 -> AdventureReward.Points(12 + random.nextInt(13))
        else -> AdventureReward.GoAgain
    }
}

fun nextPlayerIndex(current: Int, playerCount: Int): Int =
    if (playerCount <= 0) 0 else (current + 1) % playerCount

fun nextSong(player: AdventurePlayer, songPool: List<String>, random: Random): Pair<String?, AdventurePlayer> {
    if (songPool.isEmpty()) return null to player
    val queue = if (player.songQueue.isEmpty() || player.queueIndex >= player.songQueue.size) {
        songPool.shuffled(random)
    } else {
        player.songQueue
    }
    val index = if (player.queueIndex >= player.songQueue.size) 0 else player.queueIndex
    val next = queue.getOrNull(index) ?: return null to player
    return next to player.copy(songQueue = queue, queueIndex = index + 1)
}

fun standings(players: List<AdventurePlayer>): List<AdventureStanding> {
    val sorted = players.sortedWith(
        compareByDescending<AdventurePlayer> { it.total }.thenBy { it.name.lowercase() }
    )
    var rank = 0
    var previous = Float.NaN
    return sorted.mapIndexed { index, player ->
        if (index == 0 || abs(player.total - previous) > 0.001f) rank = index + 1
        previous = player.total
        AdventureStanding(player, rank, medalFor(rank, players.size))
    }
}

private fun medalFor(rank: Int, playerCount: Int): AdventureMedal = when {
    playerCount < 2 -> AdventureMedal.NONE
    rank == 1 -> AdventureMedal.GOLD
    rank == 2 -> AdventureMedal.SILVER
    rank == 3 && playerCount >= 3 -> AdventureMedal.BRONZE
    else -> AdventureMedal.NONE
}
