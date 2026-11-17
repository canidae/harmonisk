package net.exent.harmonisk.ui.freebird

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.R

@Composable
fun FreeBirdScreen(
    onBack: () -> Unit,
    viewModel: FreeBirdViewModel = hiltViewModel()
) {
    val instrument by viewModel.instrument.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        FreeBirdBackdrop(Modifier.fillMaxSize())
        FreeBirdStaff(instrument, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to Color(0xFF08130C).copy(alpha = 0.82f)
                    )
                )
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.28f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = Color.White
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.freebird_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                fontSize = 52.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.freebird_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.88f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.freebird_preview_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.26f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}
