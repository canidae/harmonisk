package net.exent.harmonisk.ui.adventure

import net.exent.harmonisk.data.ProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AdventureEngineTest {

    private fun player(
        name: String,
        position: Int = 0,
        songScore: Float = 0f,
        stickerScore: Float = 0f,
        color: Int = 0
    ) = AdventurePlayer(
        profileId = name,
        name = name,
        colorIndex = color,
        position = position,
        songScore = songScore,
        stickerScore = stickerScore
    )

    @Test
    fun `rewards are never granted at the castle`() {
        repeat(50) {
            assertNull(rewardFor(ADVENTURE_GOAL, Random(it)))
        }
    }

    @Test
    fun `rewards are split between stickers, points and go again`() {
        val kinds = (0 until 60).mapNotNull { seed ->
            when (rewardFor(1, Random(seed))) {
                is AdventureReward.Stickers -> "stickers"
                is AdventureReward.Points -> "points"
                AdventureReward.GoAgain -> "go"
                null -> null
            }
        }.toSet()
        assertEquals(setOf("stickers", "points", "go"), kinds)
    }

    @Test
    fun `points rewards add to the bonus score`() {
        val player = player("A", songScore = 50f).withReward(AdventureReward.Points(18))
        assertEquals(18f, player.bonusScore)
        assertEquals(68f, player.total)
    }

    @Test
    fun `sticker rewards contain one to three distinct stickers`() {
        repeat(50) { seed ->
            val reward = rewardFor(1, Random(seed))
            if (reward is AdventureReward.Stickers) {
                assertTrue(reward.stickers.size in 1..3)
                assertEquals(reward.stickers.size, reward.stickers.distinct().size)
            }
        }
    }

    @Test
    fun `sticker rewards add their hidden values to the sticker score`() {
        val stickers = listOf(AdventureSticker.MUSHROOM, AdventureSticker.CRYSTAL)
        val player = player("A", songScore = 80f).withReward(AdventureReward.Stickers(stickers))
        assertEquals(80f, player.songScore)
        assertEquals(16f, player.stickerScore)
        assertEquals(stickers, player.stickers)
        assertEquals(96f, player.total)
    }

    @Test
    fun `go again does not change the score`() {
        val player = player("A", songScore = 42f).withReward(AdventureReward.GoAgain)
        assertEquals(42f, player.songScore)
        assertEquals(0f, player.stickerScore)
        assertEquals(42f, player.total)
    }

    @Test
    fun `players cannot advance past the castle`() {
        val player = player("A", position = ADVENTURE_GOAL).advanced()
        assertEquals(ADVENTURE_GOAL, player.position)
    }

    @Test
    fun `standings rank by total and handle a tie`() {
        val standings = standings(
            listOf(
                player("A", songScore = 100f),
                player("B", songScore = 70f, stickerScore = 10f),
                player("C", songScore = 65f, stickerScore = 15f),
                player("D", songScore = 50f)
            )
        )
        assertEquals(listOf("A", "B", "C", "D"), standings.map { it.player.name })
        assertEquals(listOf(1, 2, 2, 4), standings.map { it.rank })
        assertEquals(AdventureMedal.GOLD, standings[0].medal)
        assertEquals(AdventureMedal.SILVER, standings[1].medal)
        assertEquals(AdventureMedal.SILVER, standings[2].medal)
        assertEquals(AdventureMedal.NONE, standings[3].medal)
    }

    @Test
    fun `solo play has no medals`() {
        val standings = standings(listOf(player("A", songScore = 100f)))
        assertEquals(AdventureMedal.NONE, standings.single().medal)
    }

    @Test
    fun `two players get gold and silver`() {
        val standings = standings(
            listOf(
                player("A", songScore = 100f),
                player("B", songScore = 90f, color = 1)
            )
        )
        assertEquals(AdventureMedal.GOLD, standings[0].medal)
        assertEquals(AdventureMedal.SILVER, standings[1].medal)
    }

    @Test
    fun `song queue is reused and reshuffled when exhausted`() {
        val random = Random(7)
        val pool = listOf("a", "b", "c")
        var player = AdventurePlayer("A", "A", 0, songQueue = listOf("a", "b"))
        val first = nextSong(player, pool, random)
        assertEquals("a", first.first)
        player = first.second
        val second = nextSong(player, pool, random)
        assertEquals("b", second.first)
        player = second.second
        val third = nextSong(player, pool, random)
        assertNotNull(third.first)
        assertTrue(third.first in pool)
        assertEquals(pool.size, third.second.songQueue.size)
        assertEquals(1, third.second.queueIndex)
    }

    @Test
    fun `next player wraps around`() {
        assertEquals(2, nextPlayerIndex(1, 3))
        assertEquals(0, nextPlayerIndex(2, 3))
    }

    @Test
    fun `sticker emojis are not available as profile emojis`() {
        AdventureSticker.entries.forEach { sticker ->
            assertFalse(sticker.emoji in ProfileRepository.PROFILE_EMOJIS)
        }
    }

    @Test
    fun `map specs start and end in every corner`() {
        val corners = (0 until 40).map { seed ->
            val start = generateMapSpec(Random(seed)).points.first()
            (if (start.x < 0.5f) "L" else "R") + (if (start.y < 0.5f) "T" else "B")
        }.toSet()
        assertEquals(4, corners.size)
    }

    @Test
    fun `map specs have a full path and stay on the map`() {
        repeat(10) { seed ->
            val spec = generateMapSpec(Random(seed))
            assertEquals(6, spec.points.size)
            (spec.points + spec.mountains + spec.trees + spec.rocks + spec.village).forEach {
                assertTrue(it.x in 0f..1f)
                assertTrue(it.y in 0f..1f)
            }
        }
    }
}
