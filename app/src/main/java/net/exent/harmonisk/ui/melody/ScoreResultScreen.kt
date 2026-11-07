package net.exent.harmonisk.ui.melody

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.exent.harmonisk.R
import net.exent.harmonisk.data.ScoreHistoryRepository.ScoreEntry
import net.exent.harmonisk.music.MelodyScore
import net.exent.harmonisk.music.ScoringEngine
import net.exent.harmonisk.music.ScoreTier
import net.exent.harmonisk.ui.celebration.CelebrationLevel
import net.exent.harmonisk.ui.celebration.ScoreCelebrationOverlay
import net.exent.harmonisk.ui.utils.formatScore
import net.exent.harmonisk.ui.utils.scoreColor

@Composable
fun ScoreResultModal(
    melodyScore: MelodyScore,
    onDismiss: () -> Unit,
    scoreHistory: List<ScoreEntry> = emptyList(),
    currentLevel: Int = 1,
    unlockedLevel: Int = 1,
    newlyUnlockedLevel: Int? = null,
    onRetryLevel: (Int) -> Unit = {},
    celebrationLevel: CelebrationLevel = CelebrationLevel.NONE,
    personalBest: Float? = null
) {
    val animatedScore by animateFloatAsState(
        targetValue = melodyScore.overallScore,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "score_animation"
    )

    val score = melodyScore.overallScore
    val enthusiasm = enthusiasmLevel(score)

    Dialog(onDismissRequest = onDismiss) {
        ScoreCelebrationOverlay(
            celebrationLevel = celebrationLevel,
            score = score,
            level = currentLevel,
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
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.score_dismiss))
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (newlyUnlockedLevel != null) {
                            Text(
                                text = stringResource(R.string.level_reached, newlyUnlockedLevel),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = stringResource(R.string.level, currentLevel),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        EnthusiasmDisplay(
                            modifier = pulseModifier,
                            level = enthusiasm,
                            scoreText = formatScore(animatedScore),
                            scoreColor = scoreColor(score)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider()

                        Spacer(modifier = Modifier.height(8.dp))

                        ScoreSummaryPanel(score = melodyScore, currentLevel = currentLevel, personalBest = personalBest)

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
                        Text(
                            text = stringResource(R.string.retry_level),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (level in 1 until unlockedLevel) {
                                    OutlinedButton(
                                        onClick = { onRetryLevel(level) },
                                        modifier = Modifier.size(48.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(level.toString())
                                    }
                                }
                            }
                            Button(
                                onClick = { onRetryLevel(unlockedLevel) },
                                modifier = Modifier.size(48.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(unlockedLevel.toString())
                            }
                        }
                        if (newlyUnlockedLevel != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val change = when (newlyUnlockedLevel) {
                                2 -> stringResource(R.string.level_2_change)
                                3 -> stringResource(R.string.level_3_change)
                                4 -> stringResource(R.string.level_4_change)
                                5 -> stringResource(R.string.level_5_change)
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
    OK("\uD83C\uDFB6"),          // 🎶
    NEEDS_WORK("\uD83C\uDFB5"); // 🎵
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
