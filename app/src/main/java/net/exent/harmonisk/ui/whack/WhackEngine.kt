package net.exent.harmonisk.ui.whack

import androidx.annotation.RawRes
import net.exent.harmonisk.R
import net.exent.harmonisk.music.BowedAlgorithm
import net.exent.harmonisk.music.Instrument
import kotlin.random.Random

data class WhackHole(val stringIndex: Int, val finger: Int, val midi: Int)

enum class WhackEmoji(val emoji: String, @RawRes vararg val sounds: Int) {
    Sheep("🐑", R.raw.whack_baa),
    Cow("🐄", R.raw.whack_moo),
    Pig("🐷", R.raw.whack_oink),
    Cat("🐱", R.raw.whack_meow),
    Dog("🐶", R.raw.whack_bark),
    Horse("🐴", R.raw.whack_whinny),
    Chick("🐥", R.raw.whack_peep),
    Frog("🐸", R.raw.whack_ribbit),
    Poop("💩", R.raw.whack_fart1, R.raw.whack_fart2, R.raw.whack_fart3, R.raw.whack_fart4),
    Balloon("🎈", R.raw.whack_pop),
    Robot("🤖", R.raw.whack_beep),
    Dino("🦖", R.raw.whack_roar),
    Fish("🐟", R.raw.whack_blub);

    companion object {
        fun pick(previous: WhackEmoji?): WhackEmoji {
            val candidates = entries.filter { it != previous }
            return candidates[Random.nextInt(candidates.size)]
        }
    }
}

object WhackRules {
    const val MAX_LEVEL = 5
    const val GAME_MS = 180_000L
    const val ADVENTURE_GAME_MS = 60_000L
    const val HIT_GAP_MS = 1_100L
    const val FREEZE_MS = 800L
    const val LEVEL_UP_MS = 1_400L
    const val PRESS_MS = 650L
    const val START_MS = 700L
    const val RISE_DELAY_MS = 140L
    const val EMOJI_RISE_MS = 700L

    /** Hits needed to reach level 2, 3, 4 and 5. */
    val LEVEL_HITS = listOf(8, 18, 30, 45)

    fun levelFor(hits: Int): Int = 1 + LEVEL_HITS.count { hits >= it }
}

/** Every first-position note, one row per finger, one column per string. */
fun whackHoles(instrument: Instrument): List<WhackHole> = (0..4).flatMap { finger ->
    val offset = BowedAlgorithm.defaultFingerOffset(instrument, finger) ?: return@flatMap emptyList()
    instrument.tuning.mapIndexed { stringIndex, open ->
        WhackHole(stringIndex, finger, open + offset)
    }
}

fun whackVisibleHoles(all: List<WhackHole>, level: Int): List<WhackHole> = all.filter { it.finger < level }

sealed interface WhackJudgement {
    val hole: WhackHole

    data class Hit(override val hole: WhackHole) : WhackJudgement
    data class Wrong(override val hole: WhackHole) : WhackJudgement
}

/**
 * Grades a played note against the hole holding the emoji. Only a note that is actually on the
 * board counts as an attempt, so the press never lands on a hole the player did not play.
 */
fun judgeNote(midi: Int, target: WhackHole, visible: List<WhackHole>): WhackJudgement? {
    if (midi == target.midi) return WhackJudgement.Hit(target)
    val played = visible.filter { it.midi == midi }.minByOrNull { it.finger } ?: return null
    return WhackJudgement.Wrong(played)
}
