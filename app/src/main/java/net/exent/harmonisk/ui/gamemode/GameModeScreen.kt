package net.exent.harmonisk.ui.gamemode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.adventure.AdventureBackdrop

@Composable
fun GameModeScreen(
    onBack: () -> Unit,
    onAdventure: () -> Unit
) {
    AdventureBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                BrandTopBar(
                    title = stringResource(R.string.game_mode_title),
                    onBack = onBack
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            ) {
                Card(
                    onClick = onAdventure,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(
                                    Brush.linearGradient(listOf(ADVENTURE_ACCENT, ADVENTURE_ACCENT.copy(alpha = 0.62f))),
                                    RoundedCornerShape(52.dp / 3)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🗺️", fontSize = 26.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.game_mode_adventure),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = ADVENTURE_ACCENT,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

private val ADVENTURE_ACCENT = Color(0xFFFFC857)
