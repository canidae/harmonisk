package net.exent.harmonisk.audio

import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns the noisy per-frame pitch estimates coming out of the detector into the steady reading
 * a tuner needle needs, without the lag a plain low-pass filter would add.
 *
 * Three stages, applied in order:
 *
 *  1. **Median gate** — a short median over the most recent estimates. Isolated misreads (octave
 *     slips, frames where the detector is briefly confused by an overtone) get discarded instead
 *     of smeared into a visible needle jump. A median has no gain error on a steady pitch and
 *     delays a genuine ramp by only half its window; a mean, by contrast, would let a single
 *     outlier pull the reading off-pitch.
 *
 *  2. **Step snap** — when the estimate commits to a different pitch (a deviation holding the same
 *     sign for long enough) the filter jumps straight to it. A large jump snaps within a few
 *     frames, while a smaller tuning correction — a finger moved or a peg turned — snaps once it
 *     has held for a moment. This is what stops smoothing from making corrections feel sluggish:
 *     the needle only moves slowly while the pitch is genuinely steady, which is exactly when slow
 *     is what you want.
 *
 *  3. **Time-constant EMA** — smooths the remaining frame-to-frame noise. The per-frame weight
 *     comes from elapsed time rather than a fixed alpha, so the needle behaves the same whether
 *     frames arrive at 25 or 85 per second. Estimates the detector reports low confidence for
 *     move the needle less, since those are the ones with the least trustworthy frequency.
 *
 * None of the stages introduce a steady-state offset, so a held in-tune note still reads the
 * pitch it actually is — only the wobble around it is removed.
 */
class PitchStabilizer {

    companion object {
        /** Number of recent estimates the median gate looks at. */
        const val MEDIAN_WINDOW = 7

        /**
         * Smoothing time constant in seconds. The needle covers 95% of a step in ~3x this;
         * 220 ms damps bow noise and vibrato without making a peg turn feel laggy.
         */
        const val TIME_CONSTANT_S = 0.22

        /** Changes smaller than this are not shown, so a steady note's needle stops jittering. */
        const val OUTPUT_DEADBAND_SEMITONES = 0.010

        /** Confidence at or below which an estimate moves the needle at [MIN_WEIGHT]. */
        private const val CONFIDENCE_FLOOR = 0.55f

        /** Confidence at or above which an estimate moves the needle at full weight. */
        private const val CONFIDENCE_CEILING = 0.92f

        /** Relative weight given to a low-confidence estimate. */
        private const val MIN_WEIGHT = 0.25

        /** Deviation, in semitones, that counts as committing to a different pitch. */
        private const val SNAP_SEMITONES = 0.40

        /** How long a [SNAP_SEMITONES] deviation must hold before the filter jumps to it. */
        private const val SNAP_TIME_S = 0.055

        /** A tuning correction this size is followed once it has held for [FINE_SNAP_TIME_S]. */
        private const val FINE_SNAP_SEMITONES = 0.09

        /** How long a [FINE_SNAP_SEMITONES] correction must hold to count as deliberate. */
        private const val FINE_SNAP_TIME_S = 0.12

        /** Frame intervals outside this range are clamped; guards against stalls and test input. */
        private const val MIN_DT_S = 0.004
        private const val MAX_DT_S = 0.12
    }

    private val window = DoubleArray(MEDIAN_WINDOW)
    private val scratch = DoubleArray(MEDIAN_WINDOW)
    private var windowSize = 0
    private var windowNext = 0

    private var smoothed = 0.0
    private var shown = 0.0
    private var started = false

    private var deviationSeconds = 0.0
    private var deviationSign = 0

    /**
     * Feed one detector estimate.
     *
     * @param semitones Pitch relative to A4 — the same space the tuner's cents display lives in.
     * @param confidence Detector confidence for this frame, 0..1.
     * @param dtSeconds Time since the previous estimate; drives the time-constant smoothing.
     * @return The smoothed pitch in semitones, ready to display.
     */
    fun accept(semitones: Double, confidence: Float, dtSeconds: Double): Double {
        push(semitones)
        val median = median()

        if (!started) {
            smoothed = median
            shown = median
            started = true
            return shown
        }

        val dt = dtSeconds.coerceIn(MIN_DT_S, MAX_DT_S)
        val delta = median - smoothed
        val magnitude = abs(delta)
        val sign = if (delta >= 0) 1 else -1
        if (magnitude > FINE_SNAP_SEMITONES && sign == deviationSign) {
            deviationSeconds += dt
        } else {
            deviationSeconds = 0.0
            deviationSign = sign
        }

        if (deviationSeconds >= if (magnitude > SNAP_SEMITONES) SNAP_TIME_S else FINE_SNAP_TIME_S) {
            // A new pitch or a deliberate correction, not noise: follow it immediately.
            smoothed = median
            shown = median
            deviationSeconds = 0.0
            return shown
        }

        val alpha = (1.0 - exp(-dt / TIME_CONSTANT_S)) * confidenceWeight(confidence)
        smoothed += alpha * delta
        if (abs(smoothed - shown) >= OUTPUT_DEADBAND_SEMITONES) shown = smoothed
        return shown
    }

    /** Forget all history, so the next estimate is adopted as-is. */
    fun reset() {
        windowSize = 0
        windowNext = 0
        smoothed = 0.0
        shown = 0.0
        started = false
        deviationSeconds = 0.0
        deviationSign = 0
    }

    private fun push(value: Double) {
        window[windowNext] = value
        windowNext = (windowNext + 1) % MEDIAN_WINDOW
        if (windowSize < MEDIAN_WINDOW) windowSize++
    }

    /** Insertion sort over at most [MEDIAN_WINDOW] values — no allocation on the audio path. */
    private fun median(): Double {
        System.arraycopy(window, 0, scratch, 0, windowSize)
        for (i in 1 until windowSize) {
            val value = scratch[i]
            var j = i - 1
            while (j >= 0 && scratch[j] > value) {
                scratch[j + 1] = scratch[j]
                j--
            }
            scratch[j + 1] = value
        }
        return scratch[windowSize / 2]
    }

    private fun confidenceWeight(confidence: Float): Double {
        val span = (CONFIDENCE_CEILING - CONFIDENCE_FLOOR).toDouble()
        val t = ((confidence - CONFIDENCE_FLOOR) / span).coerceIn(0.0, 1.0)
        return MIN_WEIGHT + (1.0 - MIN_WEIGHT) * t
    }
}
