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
import com.perfectpitch.music.ScoringEngine
import com.perfectpitch.music.ScoreTier
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
                    EnthusiasmDisplay(
                        level = enthusiasm,
                        scoreText = "%.0f".format(animatedScore),
                        scoreColor = scoreColor(score),
                        message = melodyScore.encouragingMessage
                    )

                    Spacer(modifier = Modifier.height(24.dp))

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
}

@Composable
private fun EnthusiasmDisplay(
    level: EnthusiasmLevel,
    scoreText: String,
    scoreColor: androidx.compose.ui.graphics.Color,
    message: String
) {
    Text(
        text = scoreText,
        style = when (level) {
            EnthusiasmLevel.LEGENDARY, EnthusiasmLevel.EXCELLENT -> MaterialTheme.typography.displayLarge
            EnthusiasmLevel.GREAT, EnthusiasmLevel.GOOD -> MaterialTheme.typography.displayMedium
            EnthusiasmLevel.OK -> MaterialTheme.typography.displaySmall
            EnthusiasmLevel.NEEDS_WORK -> MaterialTheme.typography.headlineMedium
        },
        color = scoreColor
    )

    if (message.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = when (level) {
                EnthusiasmLevel.LEGENDARY -> MaterialTheme.typography.displaySmall
                EnthusiasmLevel.EXCELLENT -> MaterialTheme.typography.titleLarge
                EnthusiasmLevel.GREAT -> MaterialTheme.typography.titleMedium
                EnthusiasmLevel.GOOD, EnthusiasmLevel.OK -> MaterialTheme.typography.bodyLarge
                EnthusiasmLevel.NEEDS_WORK -> MaterialTheme.typography.bodyMedium
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun enthusiasmLevel(score: Float): EnthusiasmLevel = when (ScoringEngine.scoreTier(score)) {
    ScoreTier.LEGENDARY -> EnthusiasmLevel.LEGENDARY
    ScoreTier.EXCELLENT -> EnthusiasmLevel.EXCELLENT
    ScoreTier.GREAT -> EnthusiasmLevel.GREAT
    ScoreTier.GOOD -> EnthusiasmLevel.GOOD
    ScoreTier.OK -> EnthusiasmLevel.OK
    ScoreTier.NEEDS_WORK -> EnthusiasmLevel.NEEDS_WORK
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
