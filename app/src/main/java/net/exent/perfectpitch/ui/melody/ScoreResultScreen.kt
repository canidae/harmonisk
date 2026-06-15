package net.exent.perfectpitch.ui.melody

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.exent.perfectpitch.R
import net.exent.perfectpitch.data.ScoreHistoryRepository.ScoreEntry
import net.exent.perfectpitch.music.MelodyScore
import net.exent.perfectpitch.music.ScoringEngine
import net.exent.perfectpitch.music.ScoreTier
import net.exent.perfectpitch.ui.celebration.CelebrationLevel
import net.exent.perfectpitch.ui.celebration.ScoreCelebrationOverlay
import net.exent.perfectpitch.ui.utils.scoreColor

@Composable
fun ScoreResultModal(
    melodyScore: MelodyScore,
    onDismiss: () -> Unit,
    scoreHistory: List<ScoreEntry> = emptyList(),
    currentLevel: Int = 1,
    canAdvance: Boolean = false,
    onRetry: () -> Unit = {},
    onAdvance: () -> Unit = {},
    celebrationLevel: CelebrationLevel = CelebrationLevel.NONE
) {
    val animatedScore by animateFloatAsState(
        targetValue = melodyScore.overallScore,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "score_animation"
    )

    val score = melodyScore.overallScore
    val enthusiasm = enthusiasmLevel(score)
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        ScoreCelebrationOverlay(
            celebrationLevel = celebrationLevel,
            modifier = Modifier.fillMaxSize()
        ) { pulseModifier ->
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
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Level $currentLevel",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        EnthusiasmDisplay(
                            modifier = pulseModifier,
                            level = enthusiasm,
                            scoreText = "%.0f".format(animatedScore),
                            scoreColor = scoreColor(score)
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

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider()

                        Spacer(modifier = Modifier.height(8.dp))

                        ScoreSummaryPanel(score = melodyScore)

                        if (scoreHistory.size >= 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ScoreProgressGraph(
                                scores = scoreHistory,
                                compact = true
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                        ) {
                            Button(onClick = onRetry) {
                                Text(context.getString(R.string.retry))
                            }
                            if (canAdvance) {
                                Button(onClick = onAdvance) {
                                    Text(context.getString(R.string.next_level, currentLevel + 1))
                                }
                            }
                        }
                        if (canAdvance) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val change = when (currentLevel + 1) {
                                2 -> context.getString(R.string.level_2_change)
                                3 -> context.getString(R.string.level_3_change)
                                4 -> context.getString(R.string.level_4_change)
                                5 -> context.getString(R.string.level_5_change)
                                else -> null
                            }
                            if (change != null) {
                                Text(
                                    text = change,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
    modifier: Modifier = Modifier,
    level: EnthusiasmLevel,
    scoreText: String,
    scoreColor: androidx.compose.ui.graphics.Color
) {
    Text(
        modifier = modifier,
        text = "${level.emoji} $scoreText",
        style = when (level) {
            EnthusiasmLevel.LEGENDARY, EnthusiasmLevel.EXCELLENT -> MaterialTheme.typography.displayLarge
            EnthusiasmLevel.GREAT, EnthusiasmLevel.GOOD -> MaterialTheme.typography.displayMedium
            EnthusiasmLevel.OK -> MaterialTheme.typography.displaySmall
            EnthusiasmLevel.NEEDS_WORK -> MaterialTheme.typography.headlineMedium
        },
        color = scoreColor
    )
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
