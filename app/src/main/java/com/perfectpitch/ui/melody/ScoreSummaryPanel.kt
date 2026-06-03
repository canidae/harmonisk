package com.perfectpitch.ui.melody

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.perfectpitch.music.MelodyScore
import com.perfectpitch.music.ScoreBreakdown
import com.perfectpitch.music.toBreakdown
import com.perfectpitch.ui.theme.Green80
import com.perfectpitch.ui.theme.Red80
import com.perfectpitch.ui.theme.Teal80
import com.perfectpitch.ui.theme.Yellow80

@Composable
fun ScoreSummaryPanel(
    score: MelodyScore,
    modifier: Modifier = Modifier
) {
    val breakdown = score.toBreakdown()

    Column(modifier = modifier.padding(top = 8.dp)) {
        Text(
            text = "Improvement Tips",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        BreakdownRow("Pitch accuracy", breakdown.avgPitchScore)
        BreakdownRow("Duration accuracy", breakdown.avgDurationScore)

        if (score.totalNotes > 0) {
            val missColor = when {
                breakdown.missRatePercent >= 30f -> Red80
                breakdown.missRatePercent >= 10f -> Yellow80
                else -> Teal80
            }
            Text(
                text = "Miss rate: ${"%.0f".format(breakdown.missRatePercent)}% (${score.totalNotes - score.notesHit}/${score.totalNotes} notes)",
                style = MaterialTheme.typography.bodySmall,
                color = missColor,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = breakdown.tipMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BreakdownRow(label: String, value: Float) {
    val color = when {
        value >= 90f -> Green80
        value >= 60f -> Teal80
        value >= 20f -> Yellow80
        else -> Red80
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${"%.0f".format(value)}%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
