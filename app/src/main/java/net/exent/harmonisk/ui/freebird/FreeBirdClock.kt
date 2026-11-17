package net.exent.harmonisk.ui.freebird

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

@Composable
fun rememberFreeBirdPhase(periodMs: Long, fps: Int = 30): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs, fps) {
        val interval = (1_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
        val period = periodMs.coerceAtLeast(1L)
        while (true) {
            phase.floatValue = (SystemClock.elapsedRealtime() % period).toFloat() / period
            delay(interval)
        }
    }
    return phase
}
