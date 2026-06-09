package net.exent.perfectpitch.ui.melody

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.exent.perfectpitch.music.MelodyScore
import net.exent.perfectpitch.music.ScoreBreakdown
import net.exent.perfectpitch.music.toBreakdown
import net.exent.perfectpitch.ui.theme.Green80
import net.exent.perfectpitch.ui.theme.Red80
import net.exent.perfectpitch.ui.theme.Teal80
import net.exent.perfectpitch.ui.theme.Yellow80

@Composable
fun ScoreSummaryPanel(
    score: MelodyScore,
    modifier: Modifier = Modifier
) {
    val breakdown = score.toBreakdown()

    Column(modifier = modifier.padding(top = 8.dp)) {
        BreakdownRow("Pitch accuracy", breakdown.avgPitchScore)
        BreakdownRow("Duration accuracy", breakdown.avgDurationScore)
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
