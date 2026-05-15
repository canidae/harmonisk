package com.perfectpitch.ui.melody

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.perfectpitch.music.*
import com.perfectpitch.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreResultScreen(
    melodyScore: MelodyScore,
    noteScores: List<NoteScore>,
    onPlayAgain: () -> Unit,
    onBack: () -> Unit
) {
    // Animated score count-up
    val animatedScore by animateFloatAsState(
        targetValue = melodyScore.overallScore,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "score_animation"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Overall score
            Text(
                text = "${"%.0f".format(animatedScore)}",
                style = MaterialTheme.typography.displayLarge,
                color = gradeColor(melodyScore.grade)
            )

            // Letter grade
            Text(
                text = melodyScore.grade.name,
                style = MaterialTheme.typography.headlineLarge,
                color = gradeColor(melodyScore.grade)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Stats cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard("Notes Hit", "${melodyScore.notesHit}/${melodyScore.totalNotes}")
                StatCard("Perfect", "${melodyScore.perfectNotes}")
                StatCard("Streak", "${melodyScore.longestStreak}")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard("Pitch Avg", "${"%.0f".format(melodyScore.avgPitchScore)}")
                StatCard("Duration Avg", "${"%.0f".format(melodyScore.avgDurationScore)}")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Per-note breakdown
            Text(
                text = "Note Breakdown",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Start)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(noteScores) { index, score ->
                    NoteScoreItem(index + 1, score)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Back to Menu")
                }
                Button(
                    onClick = onPlayAgain,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Play Again")
                }
            }
        }
    }
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

@Composable
private fun NoteScoreItem(number: Int, score: NoteScore) {
    val color = when (score.pitchAccuracy) {
        PitchAccuracy.PERFECT -> Green80
        PitchAccuracy.GOOD -> Green80.copy(alpha = 0.7f)
        PitchAccuracy.FAIR -> Yellow80
        PitchAccuracy.MISS -> Red80
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("#$number", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = score.pitchAccuracy.name,
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
        Text(
            text = "${"%.0f".format(score.overallScore)}",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End
        )
    }
}

private fun gradeColor(grade: LetterGrade) = when (grade) {
    LetterGrade.S -> Green80
    LetterGrade.A -> Green80
    LetterGrade.B -> Teal80
    LetterGrade.C -> Yellow80
    LetterGrade.D -> Red80
    LetterGrade.F -> Red80
}
