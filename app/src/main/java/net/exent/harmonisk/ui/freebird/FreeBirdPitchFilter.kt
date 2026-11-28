package net.exent.harmonisk.ui.freebird

import kotlin.math.abs
import kotlin.math.exp

class FreeBirdPitchFilter(
    private val smoothingMs: Float = 300f,
    private val snapSteps: Float = 0.40f,
    private val snapMs: Float = 90f
) {
    private var shown = 0f
    private var started = false
    private var farMs = 0f
    private var farSign = 0

    fun accept(target: Float, dtMs: Float): Float {
        if (!started) {
            shown = target
            started = true
            return shown
        }
        val diff = target - shown
        if (abs(diff) <= snapSteps) {
            farMs = 0f
            farSign = 0
            shown += diff * (1f - exp(-dtMs / smoothingMs))
            return shown
        }
        val sign = if (diff > 0f) 1 else -1
        farMs = if (sign == farSign) farMs + dtMs else dtMs
        farSign = sign
        if (farMs >= snapMs) {
            shown = target
            farMs = 0f
            farSign = 0
        }
        return shown
    }

    fun reset() {
        shown = 0f
        started = false
        farMs = 0f
        farSign = 0
    }
}
