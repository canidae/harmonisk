package com.perfectpitch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.perfectpitch.ui.theme.Green80
import com.perfectpitch.ui.theme.Red80
import com.perfectpitch.ui.theme.Yellow80
import kotlin.math.abs

/**
 * A horizontal bar showing pitch accuracy with color coding.
 */
@Composable
fun AccuracyIndicator(
    centsOff: Float,
    modifier: Modifier = Modifier,
    maxCents: Float = 50f
) {
    val accuracy = 1f - (abs(centsOff) / maxCents).coerceIn(0f, 1f)
    val animatedAccuracy by animateFloatAsState(
        targetValue = accuracy,
        animationSpec = tween(durationMillis = 150),
        label = "accuracy"
    )

    val barColor by animateColorAsState(
        targetValue = when {
            abs(centsOff) <= 10f -> Green80
            abs(centsOff) <= 25f -> Yellow80
            else -> Red80
        },
        animationSpec = tween(durationMillis = 200),
        label = "bar_color"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .padding(horizontal = 24.dp)
        ) {
            // Background bar
            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.2f),
                cornerRadius = CornerRadius(8f, 8f),
                size = Size(size.width, size.height)
            )

            // Filled bar
            drawRoundRect(
                color = barColor,
                cornerRadius = CornerRadius(8f, 8f),
                size = Size(size.width * animatedAccuracy, size.height)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = when {
                abs(centsOff) <= 10f -> "Perfect!"
                abs(centsOff) <= 25f -> "Good"
                abs(centsOff) <= 50f -> "Off pitch"
                else -> "Way off"
            },
            style = MaterialTheme.typography.labelLarge,
            color = barColor,
            textAlign = TextAlign.Center
        )
    }
}
