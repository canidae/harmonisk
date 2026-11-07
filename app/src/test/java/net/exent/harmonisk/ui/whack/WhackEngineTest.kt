package net.exent.harmonisk.ui.whack

import net.exent.harmonisk.music.Instrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhackEngineTest {

    @Test
    fun `cello first position follows the cello finger spacing`() {
        val holes = whackHoles(Instrument.CELLO)
        assertEquals(20, holes.size)
        assertEquals(listOf(36, 43, 50, 57), row(holes, 0))
        assertEquals(listOf(38, 45, 52, 59), row(holes, 1))
        assertEquals(listOf(39, 46, 53, 60), row(holes, 2))
        assertEquals(listOf(40, 47, 54, 61), row(holes, 3))
        assertEquals(listOf(41, 48, 55, 62), row(holes, 4))
    }

    @Test
    fun `violin fourth finger runs an octave up the string above`() {
        val holes = whackHoles(Instrument.VIOLIN)
        assertEquals(listOf(55, 62, 69, 76), row(holes, 0))
        assertEquals(listOf(62, 69, 76, 83), row(holes, 4))
        assertEquals(Instrument.VIOLIN.highestFirstPositionMidi, row(holes, 4).last())
    }

    @Test
    fun `viola shares the cello spacing one octave up`() {
        val holes = whackHoles(Instrument.VIOLA)
        assertEquals(listOf(48, 55, 62, 69), row(holes, 0))
        assertEquals(listOf(55, 62, 69, 76), row(holes, 4))
    }

    @Test
    fun `levels unlock one row at a time at the hit thresholds`() {
        assertEquals(1, WhackRules.levelFor(0))
        assertEquals(1, WhackRules.levelFor(7))
        assertEquals(2, WhackRules.levelFor(8))
        assertEquals(2, WhackRules.levelFor(17))
        assertEquals(3, WhackRules.levelFor(18))
        assertEquals(4, WhackRules.levelFor(30))
        assertEquals(5, WhackRules.levelFor(45))
        assertEquals(5, WhackRules.levelFor(500))
        assertEquals(20, whackVisibleHoles(whackHoles(Instrument.VIOLIN), 5).size)
        assertEquals(4, whackVisibleHoles(whackHoles(Instrument.VIOLIN), 1).size)
    }

    @Test
    fun `faster whacks score more points`() {
        assertEquals(5, WhackRules.pointsFor(0))
        assertEquals(5, WhackRules.pointsFor(1_000))
        assertEquals(4, WhackRules.pointsFor(1_001))
        assertEquals(4, WhackRules.pointsFor(2_000))
        assertEquals(3, WhackRules.pointsFor(2_001))
        assertEquals(3, WhackRules.pointsFor(3_000))
        assertEquals(2, WhackRules.pointsFor(3_001))
        assertEquals(2, WhackRules.pointsFor(4_000))
        assertEquals(1, WhackRules.pointsFor(4_001))
        assertEquals(1, WhackRules.pointsFor(60_000))
    }

    @Test
    fun `playing the target note counts as a hit`() {
        val holes = whackHoles(Instrument.CELLO)
        val target = holes.first { it.finger == 2 && it.stringIndex == 1 }
        val judgement = judgeNote(target.midi, target, whackVisibleHoles(holes, 3))
        assertEquals(WhackJudgement.Hit(target), judgement)
    }

    @Test
    fun `a wrong note presses the hole it was played on`() {
        val holes = whackHoles(Instrument.CELLO)
        val target = holes.first { it.finger == 1 && it.stringIndex == 0 }
        val played = holes.first { it.finger == 2 && it.stringIndex == 3 }
        val judgement = judgeNote(played.midi, target, whackVisibleHoles(holes, 5))
        assertEquals(WhackJudgement.Wrong(played), judgement)
        assertEquals(3, judgement!!.hole.stringIndex)
    }

    @Test
    fun `a note on a locked row presses nothing`() {
        val holes = whackHoles(Instrument.VIOLIN)
        val target = holes.first { it.finger == 0 && it.stringIndex == 0 }
        val played = holes.first { it.finger == 3 && it.stringIndex == 2 }
        assertNull(judgeNote(played.midi, target, whackVisibleHoles(holes, 1)))
    }

    @Test
    fun `a note above the board presses nothing`() {
        val holes = whackHoles(Instrument.VIOLIN)
        val target = holes.first { it.finger == 0 && it.stringIndex == 3 }
        assertNull(judgeNote(77, target, whackVisibleHoles(holes, 1)))
        assertNull(judgeNote(77, target, whackVisibleHoles(holes, 2)))
        assertEquals(
            WhackJudgement.Wrong(holes.first { it.finger == 1 && it.stringIndex == 3 }),
            judgeNote(78, target, whackVisibleHoles(holes, 2))
        )
    }

    @Test
    fun `notes outside first position are not attempts`() {
        val holes = whackHoles(Instrument.CELLO)
        val target = holes.first()
        assertNull(judgeNote(80, target, whackVisibleHoles(holes, 3)))
    }

    @Test
    fun `every emoji is offered before the previous one repeats`() {
        var previous: WhackEmoji? = null
        repeat(200) {
            val picked = WhackEmoji.pick(previous)
            assertTrue(picked != previous)
            previous = picked
        }
    }

    private fun row(holes: List<WhackHole>, finger: Int): List<Int> =
        holes.filter { it.finger == finger }.sortedBy { it.stringIndex }.map { it.midi }
}
