package net.exent.harmonisk.ui.games

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.adventure.AdventureBackdrop
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.LaunchCard
import net.exent.harmonisk.ui.theme.AdventureGold

private val ADVENTURE_ACCENT = AdventureGold
private val FREEBIRD_ACCENT = Color(0xFF7ED0E8)
private val WHACK_ACCENT = Color(0xFF7FB3C8)

@Composable
fun GamesScreen(
    onBack: () -> Unit,
    onAdventure: () -> Unit,
    onFreeBird: () -> Unit,
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
                LaunchCard(
                    title = stringResource(R.string.adventure_title),
                    accent = ADVENTURE_ACCENT,
                    onClick = onAdventure,
                    icon = { Text("🗺️", fontSize = 40.sp) }
                )
                Spacer(Modifier.height(14.dp))
                LaunchCard(
                    title = stringResource(R.string.freebird_title),
                    accent = FREEBIRD_ACCENT,
                    onClick = onFreeBird,
                    icon = { Text("🐦", fontSize = 40.sp) }
                )
                Spacer(Modifier.height(14.dp))
                LaunchCard(
                    title = stringResource(R.string.whack_title),
                    accent = WHACK_ACCENT,
                    onClick = onWhack,
                    icon = { Text("🎯", fontSize = 40.sp) }
                )
            }
        }
    }
}
