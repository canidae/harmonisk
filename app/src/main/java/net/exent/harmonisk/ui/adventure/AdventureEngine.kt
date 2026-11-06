package net.exent.harmonisk.ui.adventure

import kotlin.math.abs
import kotlin.random.Random

const val ADVENTURE_FIELD_COUNT = 50
const val ADVENTURE_GOAL = ADVENTURE_FIELD_COUNT + 1
const val ADVENTURE_CASTLE_BONUS = 100

enum class AdventureTurnPhase {
    ROLLING,
    DICE,
    MOVING,
    EVENT,
    PULLING,
    SPINNING,
    HIGHLIGHT,
    REVEALED
}

object AdventureTiming {
    const val WALK_MS = 900L
    const val STEP_MS = 180L
    const val MAX_WALK_MS = 2_200L
    const val DICE_SHOW_MS = 900L
    const val FLASH_FLY_MS = 650L
    const val SPIN_MS = 2800L
    const val SPIN_EXTRA_MS = 1_000L
    const val HOLD_MS = 400L
    const val FLY_MS = 600L
    const val REVEAL_MS = HOLD_MS + FLY_MS
    const val AUTO_PLAY_MS = 2_000L
    const val REWARD_MS = 2900L
    const val REWARD_FLY_MS = 700L

    fun walkMs(steps: Int): Long =
        (WALK_MS + (steps - 1).coerceAtLeast(0) * STEP_MS).coerceAtMost(MAX_WALK_MS)
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
    data object Song : AdventureReward
    data object GoAgain : AdventureReward
    data object Whack : AdventureReward
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

data class AdventureLanding(
    val player: AdventurePlayer,
    val reward: AdventureReward?,
    val reachedGoal: Boolean
)

fun AdventurePlayer.movedBy(fields: Int): AdventurePlayer =
    copy(position = (position + fields.coerceAtLeast(0)).coerceAtMost(ADVENTURE_GOAL))

fun AdventurePlayer.withScore(score: Float): AdventurePlayer = copy(
    songScore = songScore + score,
    songsPlayed = songsPlayed + 1
)

fun AdventurePlayer.withBonus(points: Int): AdventurePlayer =
    copy(bonusScore = bonusScore + points)

fun AdventurePlayer.withReward(reward: AdventureReward): AdventurePlayer = when (reward) {
    is AdventureReward.Stickers -> copy(
        stickerScore = stickerScore + reward.stickers.sumOf { it.value }.toFloat(),
        stickers = stickers + reward.stickers
    )
    AdventureReward.Song, AdventureReward.GoAgain, AdventureReward.Whack -> this
}

fun resolveLanding(
    player: AdventurePlayer,
    dice: Int,
    fieldRewards: List<AdventureReward?>
): AdventureLanding {
    val moved = player.movedBy(dice)
    val reachedGoal = moved.position >= ADVENTURE_GOAL
    return AdventureLanding(
        player = moved,
        reward = if (reachedGoal) null else fieldRewards.getOrNull(moved.position),
        reachedGoal = reachedGoal
    )
}

fun generateFieldRewards(random: Random): List<AdventureReward?> =
    (0..ADVENTURE_FIELD_COUNT).map { field ->
        when (field) {
            0, 1 -> null
            else -> randomFieldReward(random)
        }
    }

fun randomFieldReward(random: Random): AdventureReward = when (random.nextInt(100)) {
    in 0 until 55 -> AdventureReward.Song
    in 55 until 75 -> AdventureReward.Stickers(
        AdventureSticker.entries.shuffled(random).take(1 + random.nextInt(3))
    )
    in 75 until 90 -> AdventureReward.Whack
    else -> AdventureReward.GoAgain
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
