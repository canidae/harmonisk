package net.exent.harmonisk.ui.adventure

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

const val ADVENTURE_DEFAULT_GOAL = 40
const val ADVENTURE_MIN_GOAL = 20
const val ADVENTURE_MAX_GOAL = 60
const val ADVENTURE_GOAL_STEP = 5

private const val ADVENTURE_SONG_PERCENT = 55
private const val ADVENTURE_STICKER_PERCENT = 20
private const val ADVENTURE_WHACK_PERCENT = 15
private const val ADVENTURE_GO_AGAIN_PERCENT =
    100 - ADVENTURE_SONG_PERCENT - ADVENTURE_STICKER_PERCENT - ADVENTURE_WHACK_PERCENT

private const val ADVENTURE_STEPS_PER_TURN = 3.5
private const val ADVENTURE_SONG_PERFORMANCE_FACTOR = 1.5
private const val ADVENTURE_PREVIEWED_SONG_FRACTION = 1.0 / 3.0
private const val ADVENTURE_SONG_TURN_OVERHEAD_SECONDS = 35.0
private const val ADVENTURE_WHACK_TURN_SECONDS = 75.0
private const val ADVENTURE_OTHER_TURN_SECONDS = 11.0

enum class AdventureTurnPhase {
    ROLLING,
    DICE,
    DELIVERING,
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
    const val DICE_PAUSE_MS = 1_000L
    const val DICE_HANDOFF_MS = 600L
    const val TURN_FLASH_MS = 2_000L
    const val SPIN_MS = 2800L
    const val SPIN_EXTRA_MS = 1_000L
    const val HOLD_MS = 400L
    const val FLY_MS = 600L
    const val REVEAL_MS = HOLD_MS + FLY_MS
    const val AUTO_PLAY_MS = 2_000L
    const val REWARD_MS = 2900L
    const val REWARD_FLY_MS = 700L
    const val REWARD_TOTAL_MS = REWARD_MS + REWARD_FLY_MS + 250L
    const val ANNOUNCE_MS = 2_300L
    const val RACE_MS = 3_600L
    const val CASTLE_CELEBRATION_MS = 4_400L
    const val FAERIE_ENTER_MS = 2_000L
    const val FAERIE_APPEAR_HOLD_MS = 1_300L
    const val FAERIE_POP_MS = 320L
    const val FAERIE_FLY_MS = 1_500L
    const val FAERIE_BLESS_HOLD_MS = 600L
    const val FAERIE_EXIT_MS = 1_300L
    const val FAERIE_TIMEOUT_MS = 15_000L

    fun walkMs(steps: Int): Long =
        (WALK_MS + (steps - 1).coerceAtLeast(0) * STEP_MS).coerceAtMost(MAX_WALK_MS)
}

enum class AdventureSticker(val emoji: String, val value: Int) {
    MUSHROOM("🍄", 15),
    ACORN("🌰", 14),
    FEATHER("🪶", 17),
    LANTERN("🏮", 24),
    COMPASS("🧭", 22),
    MAP("🗺️", 40),
    CRYSTAL("💎", 55),
    KEY("🗝️", 45),
    SHIELD("🛡️", 30),
    BOW("🏹", 28),
    POTION("🧪", 21),
    CROWN("👑", 75),
    SCROLL("📜", 19),
    COIN("🪙", 16),
    FLAME("🔥", 20),
    LEAF("🌿", 15),
    ROCK("🪨", 16),
    FLOWER("🌸", 14),
    CLOVER("🍀", 17),
    CANDLE("🕯️", 18)
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
    val position: Int = 0,
    val songScore: Float = 0f,
    val stickerScore: Float = 0f,
    val whackScore: Float = 0f,
    val faerieScore: Float = 0f,
    val castleScore: Float = 0f,
    val stickers: List<AdventureSticker> = emptyList(),
    val songsPlayed: Int = 0,
    val songQueue: List<String> = emptyList(),
    val queueIndex: Int = 0
) {
    val bonusTotal: Float
        get() = stickerScore + whackScore + faerieScore + castleScore

    val total: Float get() = songScore + bonusTotal
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

data class AdventureFaerie(
    val playerId: String,
    val points: Int
)

fun AdventurePlayer.movedBy(fields: Int, goal: Int): AdventurePlayer =
    copy(position = (position + fields.coerceAtLeast(0)).coerceAtMost(goal))

fun AdventurePlayer.withScore(score: Float): AdventurePlayer = copy(
    songScore = songScore + score,
    songsPlayed = songsPlayed + 1
)

fun AdventurePlayer.withWhackScore(points: Int): AdventurePlayer =
    copy(whackScore = whackScore + points)

fun AdventurePlayer.withFaerieScore(points: Int): AdventurePlayer =
    copy(faerieScore = faerieScore + points)

fun AdventurePlayer.withCastleScore(goal: Int): AdventurePlayer =
    copy(castleScore = castleScore + goal * 2)

fun AdventurePlayer.withReward(reward: AdventureReward): AdventurePlayer = when (reward) {
    is AdventureReward.Stickers -> copy(
        stickerScore = stickerScore + reward.stickers.sumOf { it.value }.toFloat(),
        stickers = stickers + reward.stickers
    )
    AdventureReward.Song, AdventureReward.GoAgain, AdventureReward.Whack -> this
}

fun adventureMinutes(goal: Int, playerCount: Int, averageSongSeconds: Double): Int {
    if (playerCount <= 0) return 0
    val songTurnSeconds = ADVENTURE_SONG_TURN_OVERHEAD_SECONDS +
        averageSongSeconds * (ADVENTURE_SONG_PERFORMANCE_FACTOR + ADVENTURE_PREVIEWED_SONG_FRACTION)
    val turnSeconds =
        ADVENTURE_SONG_PERCENT / 100.0 * songTurnSeconds +
            ADVENTURE_WHACK_PERCENT / 100.0 * ADVENTURE_WHACK_TURN_SECONDS +
            (ADVENTURE_STICKER_PERCENT + ADVENTURE_GO_AGAIN_PERCENT) / 100.0 *
            ADVENTURE_OTHER_TURN_SECONDS
    val stepsPerRoll = ADVENTURE_STEPS_PER_TURN *
        (1.0 - ADVENTURE_GO_AGAIN_PERCENT / 100.0)
    val rolls = goal / stepsPerRoll * playerCount
    return (rolls * turnSeconds / 60.0).roundToInt().coerceAtLeast(1)
}

fun resolveLanding(
    player: AdventurePlayer,
    dice: Int,
    fieldRewards: List<AdventureReward?>
): AdventureLanding {
    val goal = fieldRewards.lastIndex
    val moved = player.movedBy(dice, goal)
    val reachedGoal = moved.position >= goal
    return AdventureLanding(
        player = moved,
        reward = if (reachedGoal) null else fieldRewards.getOrNull(moved.position),
        reachedGoal = reachedGoal
    )
}

fun generateFieldRewards(random: Random, goal: Int = ADVENTURE_DEFAULT_GOAL): List<AdventureReward?> =
    (0..goal).map { field ->
        when (field) {
            0, goal -> null
            else -> randomFieldReward(random)
        }
    }

fun randomFieldReward(random: Random): AdventureReward {
    val roll = random.nextInt(100)
    val stickerEnd = ADVENTURE_SONG_PERCENT + ADVENTURE_STICKER_PERCENT
    val whackEnd = stickerEnd + ADVENTURE_WHACK_PERCENT
    return when {
        roll < ADVENTURE_SONG_PERCENT -> AdventureReward.Song
        roll < stickerEnd -> AdventureReward.Stickers(randomStickers(random))
        roll < whackEnd -> AdventureReward.Whack
        else -> AdventureReward.GoAgain
    }
}

fun randomStickers(random: Random): List<AdventureSticker> {
    val stickers = AdventureSticker.entries.shuffled(random).take(1 + random.nextInt(3)).toMutableList()
    if (stickers.sumOf { it.value } >= 40) return stickers
    val cheapest = stickers.minBy { it.value }
    val replacement = AdventureSticker.entries.filter { it !in stickers && it.value >= 40 }.random(random)
    stickers[stickers.indexOf(cheapest)] = replacement
    return stickers
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

fun faerieGift(players: List<AdventurePlayer>): AdventureFaerie? {
    if (players.size < 2) return null
    val lowest = players.minByOrNull { it.total } ?: return null
    val highest = players.maxOf { it.total }
    val points = ((highest - lowest.total) * 2f).roundToInt()
    return if (points > 0) AdventureFaerie(lowest.profileId, points) else null
}

fun standings(players: List<AdventurePlayer>): List<AdventureStanding> {
    val ranks = mutableMapOf<String, Int>()
    var rank = 0
    var previous = Float.NaN
    players.sortedWith(
        compareByDescending<AdventurePlayer> { it.total }.thenBy { it.name.lowercase() }
    ).forEachIndexed { index, player ->
        if (index == 0 || abs(player.total - previous) > 0.001f) rank = index + 1
        previous = player.total
        ranks[player.profileId] = rank
    }
    return players.map { player ->
        val playerRank = ranks.getValue(player.profileId)
        AdventureStanding(player, playerRank, medalFor(playerRank, players.size))
    }
}

private fun medalFor(rank: Int, playerCount: Int): AdventureMedal = when {
    playerCount < 2 -> AdventureMedal.NONE
    rank == 1 -> AdventureMedal.GOLD
    rank == 2 -> AdventureMedal.SILVER
    rank == 3 && playerCount >= 3 -> AdventureMedal.BRONZE
    else -> AdventureMedal.NONE
}
