package com.perfectpitch.ui.melody

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.perfectpitch.music.MelodyScore
import com.perfectpitch.ui.theme.*

@Composable
fun ScoreResultModal(
    melodyScore: MelodyScore,
    onDismiss: () -> Unit
) {
    val animatedScore by animateFloatAsState(
        targetValue = melodyScore.overallScore,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "score_animation"
    )

    val score = melodyScore.overallScore
    val enthusiasm = enthusiasmLevel(score)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Dismiss button top-right
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Score
                    Text(
                        text = "${"%.0f".format(animatedScore)}",
                        style = enthusiasm.scoreStyle(),
                        color = scoreColor(score)
                    )

                    // Encouraging message
                    if (melodyScore.encouragingMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = melodyScore.encouragingMessage,
                            style = enthusiasm.messageStyle(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatCard("Notes Hit", "${melodyScore.notesHit}/${melodyScore.totalNotes}")
                        StatCard("Perfect", "${melodyScore.perfectNotes}")
                        StatCard("Great", "${melodyScore.greatNotes}")
                    }
                }
            }
        }
    }
}

private enum class EnthusiasmLevel(val emoji: String) {
    LEGENDARY("\uD83C\uDF1F"),   // 🌟
    EXCELLENT("\uD83C\uDF89"),   // 🎉
    GREAT("\uD83D\uDC4F"),       // 👏
    GOOD("\uD83D\uDCAA"),        // 💪
    OK("\uD83C\uDFB5"),          // 🎵
    NEEDS_WORK("\uD83C\uDFB6"); // 🎶

    @Composable
    fun scoreStyle() = when (this) {
        LEGENDARY, EXCELLENT -> MaterialTheme.typography.displayLarge
        GREAT, GOOD -> MaterialTheme.typography.displayMedium
        OK -> MaterialTheme.typography.displaySmall
        NEEDS_WORK -> MaterialTheme.typography.headlineMedium
    }

    @Composable
    fun messageStyle() = when (this) {
        LEGENDARY -> MaterialTheme.typography.displaySmall
        EXCELLENT -> MaterialTheme.typography.titleLarge
        GREAT -> MaterialTheme.typography.titleMedium
        GOOD -> MaterialTheme.typography.bodyLarge
        OK -> MaterialTheme.typography.bodyLarge
        NEEDS_WORK -> MaterialTheme.typography.bodyMedium
    }
}

private fun enthusiasmLevel(score: Float) = when {
    score >= 95f -> EnthusiasmLevel.LEGENDARY
    score >= 80f -> EnthusiasmLevel.EXCELLENT
    score >= 65f -> EnthusiasmLevel.GREAT
    score >= 50f -> EnthusiasmLevel.GOOD
    score >= 30f -> EnthusiasmLevel.OK
    else -> EnthusiasmLevel.NEEDS_WORK
}

@Composable
private fun StatCard(label: String, value: String) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, style = MaterialTheme.typography.titleLarge)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun scoreColor(score: Float) = when {
    score >= 80f -> Green80
    score >= 65f -> Teal80
    score >= 50f -> Yellow80
    else -> Red80
}
