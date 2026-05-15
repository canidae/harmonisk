package com.perfectpitch.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.perfectpitch.ui.theme.*
import kotlin.math.abs

/**
 * Large note name display with octave number and sharp/flat indicators.
 */
@Composable
fun NoteDisplay(
    noteName: String,
    octave: Int,
    centsOff: Float,
    isSilent: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isSilent) {
            Text(
                text = "—",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Text(
                text = "Play a note",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                // Flat indicator
                Text(
                    text = if (centsOff < -5f) "♭" else " ",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Yellow80,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.Center
                )

                // Note name
                Text(
                    text = noteName,
                    style = MaterialTheme.typography.displayLarge,
                    color = when {
                        abs(centsOff) <= 10f -> Green80
                        abs(centsOff) <= 25f -> Yellow80
                        else -> Red80
                    }
                )

                // Octave number
                Text(
                    text = "$octave",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Sharp indicator
                Text(
                    text = if (centsOff > 5f) "♯" else " ",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Yellow80,
                    modifier = Modifier.width(32.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
