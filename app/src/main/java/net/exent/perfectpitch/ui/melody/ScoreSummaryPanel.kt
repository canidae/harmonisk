package net.exent.perfectpitch.ui.melody

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.exent.perfectpitch.R
import net.exent.perfectpitch.music.MelodyScore
import net.exent.perfectpitch.music.ScoreBreakdown
import net.exent.perfectpitch.music.toBreakdown
import net.exent.perfectpitch.ui.utils.scoreColor

@Composable
fun ScoreSummaryPanel(
    score: MelodyScore,
    modifier: Modifier = Modifier
) {
    val breakdown = score.toBreakdown()

    Column(modifier = modifier.padding(top = 8.dp)) {
        BreakdownRow(stringResource(R.string.score_pitch_accuracy), breakdown.avgPitchScore)
        BreakdownRow(stringResource(R.string.score_duration_accuracy), breakdown.avgDurationScore)
    }
}

@Composable
private fun BreakdownRow(label: String, value: Float) {
    val color = scoreColor(value)

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
