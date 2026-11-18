package net.exent.harmonisk.ui.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.components.BrandTopBar
import net.exent.harmonisk.ui.components.LaunchCard
import net.exent.harmonisk.ui.components.brandBackground
import net.exent.harmonisk.ui.theme.BrandPurple

private val SOUND_TEST_ACCENT = Color(0xFFB39DDB)
private val FREQUENCY_ACCENT = Color(0xFF81C784)

@Composable
fun DeveloperToolsScreen(
    onBack: () -> Unit,
    onRecord: () -> Unit,
    onSoundTest: () -> Unit,
    onFrequencyVisualizer: () -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .brandBackground(),
        containerColor = Color.Transparent,
        topBar = {
            BrandTopBar(
                title = stringResource(R.string.developer_tools_title),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LaunchCard(
                title = stringResource(R.string.record_title),
                accent = BrandPurple,
                onClick = onRecord,
                icon = { Text("🎵", fontSize = 36.sp) }
            )
            LaunchCard(
                title = stringResource(R.string.sound_test_title),
                accent = SOUND_TEST_ACCENT,
                onClick = onSoundTest,
                icon = { Text("🔔", fontSize = 36.sp) }
            )
            LaunchCard(
                title = stringResource(R.string.frequency_visualizer_title),
                accent = FREQUENCY_ACCENT,
                onClick = onFrequencyVisualizer,
                icon = { Text("📈", fontSize = 36.sp) }
            )
        }
    }
}
