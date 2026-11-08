package net.exent.harmonisk.ui.adventure

import net.exent.harmonisk.audio.SynthesizedPlayer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private fun brassSample(time: Float, frequency: Float): Float {
    val envelope = min(1f, time / 0.018f) * exp(-2.2f * time)
    val phase = 2.0 * PI * frequency * time
    return (
        sin(phase) * 0.58 +
            sin(phase * 2.0) * 0.25 +
            sin(phase * 3.0) * 0.12
        ).toFloat() * envelope
}

class AdventureFanfarePlayer : SynthesizedPlayer(3.3f) {
    private val random = Random(11)

    override fun sample(time: Float): Float {
        var sample = 0f
        val drumEnd = 1.35f
        val fanfareStart = 1.35f
        val melody = listOf(
            523.25f to 0f,
            659.25f to 0.19f,
            783.99f to 0.38f,
            1046.50f to 0.57f
        )
        if (time < drumEnd) {
            val noise = random.nextFloat() * 2f - 1f
            val pulse = 0.45f + 0.55f * ((sin(2.0 * PI * 19.0 * time) + 1.0) / 2.0).toFloat()
            val envelope = 1f - time / drumEnd * 0.75f
            sample += noise * pulse * envelope * 0.42f
            sample += (sin(2.0 * PI * 92.0 * time).toFloat()) * envelope * 0.16f
        }
        if (time >= fanfareStart) {
            val local = time - fanfareStart
            melody.forEach { (frequency, start) ->
                if (local >= start && local < start + 0.23f) {
                    sample += brassSample(local - start, frequency) * 0.34f
                }
            }
            if (local >= 0.9f) {
                val chord = listOf(523.25f, 659.25f, 783.99f, 1046.50f)
                chord.forEach { frequency ->
                    sample += brassSample(local - 0.9f, frequency) * 0.16f
                }
            }
        }
        return sample
    }
}

class CastleFanfarePlayer : SynthesizedPlayer(1.9f, 0.7f) {
    private val random = Random(7)
    private val melody = listOf(
        523.25f to 0f,
        659.25f to 0.13f,
        783.99f to 0.26f,
        1046.50f to 0.39f
    )
    private val chord = listOf(523.25f, 659.25f, 783.99f, 1046.50f)

    override fun sample(time: Float): Float {
        var sample = 0f
        if (time < 0.75f) {
            val noise = random.nextFloat() * 2f - 1f
            val pulse = 0.45f + 0.55f * ((sin(2.0 * PI * 22.0 * time) + 1.0) / 2.0).toFloat()
            val envelope = 1f - time / 0.75f * 0.6f
            sample += noise * pulse * envelope * 0.32f
        }
        melody.forEach { (frequency, start) ->
            if (time >= start) sample += brassSample(time - start, frequency) * 0.3f
        }
        if (time >= 0.55f) {
            chord.forEach { frequency ->
                sample += brassSample(time - 0.55f, frequency) * 0.14f
            }
        }
        return sample
    }
}

class FaerieFanfarePlayer : SynthesizedPlayer(3f, 0.62f) {
    private val notes = listOf(
        1046.50f to 0f,
        1318.51f to 0.09f,
        1567.98f to 0.18f,
        2093.00f to 0.27f,
        2637.02f to 0.36f,
        3135.96f to 0.45f
    )
    private val chord = listOf(1046.50f, 1318.51f, 1567.98f, 2093.00f, 2637.02f)
    private val chordStart = 0.72f

    override fun sample(time: Float): Float {
        var sample = 0f
        notes.forEach { (frequency, start) ->
            if (time >= start) sample += bell(time - start, frequency) * 0.33f
        }
        if (time >= chordStart) {
            chord.forEach { frequency ->
                sample += bell(time - chordStart, frequency) * 0.12f
            }
        }
        return sample
    }

    private fun bell(time: Float, frequency: Float): Float {
        val envelope = min(1f, time / 0.006f) * exp(-2.4f * time)
        val phase = 2.0 * PI * frequency * time
        return (
            sin(phase) * 0.6 +
                sin(phase * 2.01) * 0.24 +
                sin(phase * 3.98) * 0.09
            ).toFloat() * envelope
    }
}

class TurnChimePlayer : SynthesizedPlayer(1.1f, 0.5f) {
    private val notes = listOf(
        783.99f to 0f,
        987.77f to 0.13f,
        1174.66f to 0.26f
    )

    override fun sample(time: Float): Float {
        var sample = 0f
        notes.forEach { (frequency, start) ->
            if (time >= start) sample += marimba(time - start, frequency) * 0.3f
        }
        return sample
    }

    private fun marimba(time: Float, frequency: Float): Float {
        val envelope = min(1f, time / 0.008f) * exp(-5.5f * time)
        val phase = 2.0 * PI * frequency * time
        return (sin(phase) * 0.7 + sin(phase * 4.0) * 0.18 + sin(phase * 9.2) * 0.07).toFloat() * envelope
    }
}

class ApplausePlayer : SynthesizedPlayer(2.6f, 0.5f) {
    private val random = Random(29)
    private val claps = buildList {
        var at = 0.04f
        while (at < 2.3f) {
            val swell = sin((at / 2.3f) * PI).toFloat()
            add(at to (0.3f + random.nextFloat() * 0.5f) * (0.4f + 0.6f * swell))
            at += 0.014f + random.nextFloat() * 0.05f
        }
    }
    private var tone = 0f

    override fun sample(time: Float): Float {
        val noise = random.nextFloat() * 2f - 1f
        tone = tone * 0.62f + noise * 0.38f
        var sample = tone * 0.16f
        claps.forEach { (start, gain) ->
            val dt = time - start
            if (dt >= 0f && dt < 0.08f) sample += tone * exp(-dt * 85f) * gain
        }
        val envelope = (time / 0.16f).coerceAtMost(1f) * ((2.6f - time) / 0.6f).coerceIn(0f, 1f)
        return sample * envelope * 0.85f
    }
}

class DiceRattlePlayer : SynthesizedPlayer(1.0f, 0.55f) {
    private val random = Random(5)
    private val clicks = buildList {
        var at = 0.02f
        var gap = 0.03f
        while (at < 0.85f) {
            add(at)
            at += gap
            gap *= 1.16f
        }
        add(0.92f)
    }

    override fun sample(time: Float): Float {
        val noise = random.nextFloat() * 2f - 1f
        var sample = 0f
        clicks.forEach { start ->
            val dt = time - start
            if (dt >= 0f && dt < 0.06f) {
                val last = start > 0.9f
                sample += noise * exp(-dt * (if (last) 45f else 75f)) * (if (last) 0.9f else 0.55f)
                sample += sin(2.0 * PI * 1400.0 * dt).toFloat() * exp(-dt * 120f) * 0.12f
            }
        }
        return sample
    }
}
