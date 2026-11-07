package net.exent.harmonisk.ui.games

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.adventure.AdventureBackdrop
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.whack.WhackPressIcon

private val ADVENTURE_ACCENT = Color(0xFFFFC857)
private val WHACK_ACCENT = Color(0xFF7FB3C8)

@Composable
fun GamesScreen(
    onBack: () -> Unit,
    onAdventure: () -> Unit,
    onWhack: () -> Unit
) {
    AdventureBackdrop(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                BrandTopBar(
                    title = stringResource(R.string.games_title),
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
                GameCard(
                    title = stringResource(R.string.adventure_title),
                    accent = ADVENTURE_ACCENT,
                    onClick = onAdventure,
                    icon = { Text("🗺️", fontSize = 40.sp) }
                )
                Spacer(Modifier.height(14.dp))
                GameCard(
                    title = stringResource(R.string.whack_title),
                    accent = WHACK_ACCENT,
                    onClick = onWhack,
                    icon = { WhackPressIcon(Modifier.size(width = 34.dp, height = 46.dp)) }
                )
            }
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    accent: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Card(
        onClick = onClick,
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
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
