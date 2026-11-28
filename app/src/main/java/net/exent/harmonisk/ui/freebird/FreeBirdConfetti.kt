package net.exent.harmonisk.ui.freebird

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.PartyFactory
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.Rotation
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size
import java.util.concurrent.TimeUnit

private val ConfettiColors = listOf(
    0xFFFFE08A,
    0xFFFFFFFF,
    0xFF7BD88F,
    0xFF7FD4FF,
    0xFFFF8C7A
).map { it.toInt() }

@Composable
fun FreeBirdConfetti(modifier: Modifier = Modifier) {
    val parties = remember {
        listOf(
            PartyFactory(Emitter(2, TimeUnit.SECONDS).max(70))
                .angle(270)
                .spread(110)
                .setSpeedBetween(12f, 28f)
                .setDamping(0.85f)
                .sizes(listOf(Size(8), Size(14)))
                .colors(ConfettiColors)
                .shapes(listOf(Shape.Circle, Shape.Square, Shape.Rectangle(0.5f)))
                .timeToLive(4_000L)
                .fadeOutEnabled(true)
                .position(Position.Relative(0.5, 0.35))
                .rotation(Rotation())
                .build()
        )
    }
    KonfettiView(modifier, parties, null)
}
