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
    fun `the adventure has fifty fields and a castle`() {
        assertEquals(50, ADVENTURE_FIELD_COUNT)
        assertEquals(51, ADVENTURE_GOAL)
    }

    @Test
    fun `rewards are preassigned to every field after the start`() {
        val rewards = generateFieldRewards(Random(5))
        assertEquals(ADVENTURE_FIELD_COUNT + 1, rewards.size)
        assertNull(rewards[0])
        assertNull(rewards[1])
        (2..ADVENTURE_FIELD_COUNT).forEach { field ->
            assertNotNull(rewards[field])
        }
    }

    @Test
    fun `rewards are mostly songs with stickers, whack and throw again`() {
        val rewards = List(10_000) { randomFieldReward(Random(it)) }
        val songs = rewards.count { it is AdventureReward.Song }
        val stickers = rewards.count { it is AdventureReward.Stickers }
        val whack = rewards.count { it is AdventureReward.Whack }
        val again = rewards.count { it is AdventureReward.GoAgain }
        assertTrue(songs in 5_000..6_000)
        assertTrue(stickers in 1_500..2_500)
        assertTrue(whack in 1_000..2_000)
        assertTrue(again in 500..1_500)
    }

    @Test
    fun `sticker rewards contain one to three distinct stickers`() {
        repeat(200) { seed ->
            val reward = randomFieldReward(Random(seed))
            if (reward is AdventureReward.Stickers) {
                assertTrue(reward.stickers.size in 1..3)
                assertEquals(reward.stickers.size, reward.stickers.distinct().size)
            }
        }
    }

    @Test
    fun `sticker rewards are worth at least forty points`() {
        repeat(500) { seed ->
            val stickers = randomStickers(Random(seed))
            assertTrue(stickers.size in 1..3)
            assertTrue(stickers.all { it.value > 0 })
            assertTrue(stickers.sumOf { it.value } >= 40)
            assertEquals(stickers.size, stickers.distinct().size)
        }
    }

    @Test
    fun `lucky sticker rewards can exceed a hundred points`() {
        val totals = List(5_000) { randomStickers(Random(it)).sumOf { sticker -> sticker.value } }
        assertTrue(totals.any { it > 100 })
    }

    @Test
    fun `the same sticker kind always has the same value`() {
        val seen = mutableMapOf<AdventureSticker, Int>()
        repeat(500) { seed ->
            randomStickers(Random(seed)).forEach { sticker ->
                val previous = seen.putIfAbsent(sticker, sticker.value)
                if (previous != null) assertEquals(previous, sticker.value)
            }
        }
    }

    @Test
    fun `sticker rewards add their hidden values to the sticker score`() {
        val stickers = listOf(AdventureSticker.MUSHROOM, AdventureSticker.CRYSTAL)
        val player = player("A", songScore = 80f).withReward(AdventureReward.Stickers(stickers))
        assertEquals(80f, player.songScore)
        assertEquals(70f, player.stickerScore)
        assertEquals(stickers, player.stickers)
        assertEquals(150f, player.total)
    }

    @Test
    fun `song fields do not add a score until the song is played`() {
        val player = player("A", songScore = 10f).withReward(AdventureReward.Song)
        assertEquals(10f, player.total)
        val played = player.withScore(30f)
        assertEquals(40f, played.total)
        assertEquals(1, played.songsPlayed)
    }

    @Test
    fun `go again does not change the score`() {
        val player = player("A", songScore = 42f).withReward(AdventureReward.GoAgain)
        assertEquals(42f, player.songScore)
        assertEquals(0f, player.stickerScore)
        assertEquals(42f, player.total)
    }

    @Test
    fun `the first player at the castle gets a hundred point bonus`() {
        val player = player("A", songScore = 120f).withBonus(ADVENTURE_CASTLE_BONUS)
        assertEquals(100f, player.bonusScore)
        assertEquals(220f, player.total)
    }

    @Test
    fun `whack points are added to the total score`() {
        val player = player("A", songScore = 42f).withBonus(7)
        assertEquals(7f, player.bonusScore)
        assertEquals(49f, player.total)
    }

    @Test
    fun `the dice moves the player and returns the landing reward`() {
        val rewards = MutableList<AdventureReward?>(ADVENTURE_GOAL) { null }
        rewards[5] = AdventureReward.Stickers(
            listOf(AdventureSticker.MUSHROOM, AdventureSticker.CRYSTAL)
        )
        val landing = resolveLanding(player("A", position = 1), dice = 4, fieldRewards = rewards)
        assertEquals(5, landing.player.position)
        assertEquals(
            AdventureReward.Stickers(listOf(AdventureSticker.MUSHROOM, AdventureSticker.CRYSTAL)),
            landing.reward
        )
        assertFalse(landing.reachedGoal)
    }

    @Test
    fun `only the landing field reward is returned`() {
        val rewards = MutableList<AdventureReward?>(ADVENTURE_GOAL) { null }
        rewards[3] = AdventureReward.Song
        rewards[5] = AdventureReward.GoAgain
        val landing = resolveLanding(player("A", position = 1), dice = 4, fieldRewards = rewards)
        assertEquals(AdventureReward.GoAgain, landing.reward)
    }

    @Test
    fun `reaching the castle has no field reward and clamps the position`() {
        val rewards = MutableList<AdventureReward?>(ADVENTURE_GOAL) { AdventureReward.Song }
        val landing = resolveLanding(player("A", position = 48), dice = 6, fieldRewards = rewards)
        assertEquals(ADVENTURE_GOAL, landing.player.position)
        assertNull(landing.reward)
        assertTrue(landing.reachedGoal)
    }

    @Test
    fun `players cannot pass the castle`() {
        assertEquals(46, player("A", position = 41).movedBy(5).position)
        assertEquals(ADVENTURE_GOAL, player("A", position = 48).movedBy(10).position)
        assertEquals(ADVENTURE_GOAL, player("A", position = ADVENTURE_GOAL).movedBy(6).position)
    }

    @Test
    fun `walk time grows with the number of fields and is capped`() {
        assertEquals(AdventureTiming.WALK_MS, AdventureTiming.walkMs(1))
        assertEquals(1_800L, AdventureTiming.walkMs(6))
        assertEquals(AdventureTiming.MAX_WALK_MS, AdventureTiming.walkMs(20))
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
    fun `map specs contain fifty fields and a castle at the top`() {
        val spec = generateMapSpec(Random(3), columns = 4)
        assertEquals(ADVENTURE_GOAL, spec.points.size)
        val start = spec.points.first()
        val castle = spec.points.last()
        assertTrue(start.x < 0.5f)
        assertTrue(start.y > 0.8f)
        assertTrue(castle.x in 0.45f..0.55f)
        assertTrue(castle.y < 0.1f)
        assertTrue(spec.contentAspect > 2f)
    }

    @Test
    fun `map specs snake across the width`() {
        val spec = generateMapSpec(Random(11), columns = 4)
        assertTrue(spec.points[3].x > spec.points[0].x)
        assertTrue(spec.points[4].y < spec.points[3].y)
        assertTrue(spec.points[4].x > spec.points[0].x)
        assertTrue(spec.points[7].x < spec.points[4].x)
    }

    @Test
    fun `map specs are deterministic for a seed`() {
        val first = generateMapSpec(Random(21), columns = 5)
        val second = generateMapSpec(Random(21), columns = 5)
        assertEquals(first.points, second.points)
        assertEquals(first.trees, second.trees)
        assertEquals(first.bridge, second.bridge)
    }

    @Test
    fun `map specs keep everything on the map`() {
        repeat(10) { seed ->
            val spec = generateMapSpec(Random(seed), columns = 4)
            (spec.points + spec.mountains + spec.trees + spec.rocks + spec.village).forEach {
                assertTrue(it.x in 0f..1f)
                assertTrue(it.y in 0f..1f)
            }
        }
    }
}
