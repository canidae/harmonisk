package net.exent.harmonisk.ui.melody

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.theme.LocalHarmoniskDarkTheme
import net.exent.harmonisk.ui.utils.formatScore
import net.exent.harmonisk.ui.utils.scoreColor

@Composable
fun PlaylistNextDialog(
    run: PlaylistRun,
    onStart: () -> Unit,
    onEnd: () -> Unit
) {
    val next = run.next ?: return
    val darkTheme = LocalHarmoniskDarkTheme.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(run.name) },
        text = {
            Column {
                run.previous?.let { previous ->
                    Text(
                        text = stringResource(R.string.melody_playlist_previous),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = previous.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatScore(previous.score),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor(previous.score, darkTheme)
                        )
                    }
                    Text(
                        text = stringResource(R.string.level, previous.level),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    previous.newLevel?.let { level ->
                        Text(
                            text = stringResource(R.string.melody_playlist_level_up, level),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                }
                Text(
                    text = stringResource(R.string.melody_playlist_next),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = next.name,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.level, next.level),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onStart) {
                Text(
                    stringResource(
                        if (run.results.isEmpty()) R.string.melody_playlist_start
                        else R.string.melody_playlist_continue
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onEnd) { Text(stringResource(R.string.melody_playlist_end)) }
        }
    )
}

@Composable
fun PlaylistSummaryDialog(
    run: PlaylistRun,
    onRetry: (Int) -> Unit,
    onPlayAgain: () -> Unit,
    onDone: () -> Unit
) {
    val darkTheme = LocalHarmoniskDarkTheme.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(run.name) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.melody_playlist_complete),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(run.results) { index, result ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = result.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.level, result.level),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = formatScore(result.score),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = scoreColor(result.score, darkTheme)
                            )
                            IconButton(onClick = { onRetry(index) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.melody_playlist_retry_song),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.melody_playlist_retry_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = onPlayAgain) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.melody_playlist_play_again))
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.melody_playlist_done)) }
        }
    )
}
