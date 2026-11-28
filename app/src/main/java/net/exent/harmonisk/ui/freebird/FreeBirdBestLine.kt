package net.exent.harmonisk.ui.freebird

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

private val BestLine = Color(0xFFFF5C5C)

@Composable
fun FreeBirdBestLine(recordTenths: Int, distanceMeters: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (recordTenths <= 0) return@Canvas
        val x = freeBirdBestLineX(size.width, recordTenths, distanceMeters)
        if (x < 0f || x > size.width) return@Canvas
        val start = Offset(x, size.height * 0.15f)
        val end = Offset(x, size.height * 0.80f)
        drawLine(BestLine.copy(alpha = 0.16f), start, end, strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(BestLine.copy(alpha = 0.9f), start, end, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}
