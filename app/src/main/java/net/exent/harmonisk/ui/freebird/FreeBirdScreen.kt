package net.exent.harmonisk.ui.freebird

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.exent.harmonisk.R
import net.exent.harmonisk.ui.components.KeepScreenOn
import net.exent.harmonisk.ui.components.RequireMicPermission

@Composable
fun FreeBirdScreen(
    onBack: () -> Unit,
    viewModel: FreeBirdViewModel = hiltViewModel()
) {
    val instrument by viewModel.instrument.collectAsStateWithLifecycle()
    val pitch by viewModel.pitch.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    KeepScreenOn()
    Box(Modifier.fillMaxSize()) {
        FreeBirdBackdrop(Modifier.fillMaxSize())
        FreeBirdStaff(instrument, modifier = Modifier.fillMaxSize())
        FreeBirdBranch(Modifier.fillMaxSize())
        RequireMicPermission {
            LaunchedEffect(Unit) { viewModel.startListening() }
            FreeBirdBird(instrument, pitch, modifier = Modifier.fillMaxSize())
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
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
    }
}
