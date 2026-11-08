package net.exent.harmonisk.ui.games

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import net.exent.harmonisk.data.DevModeState
import javax.inject.Inject

@HiltViewModel
class GamesViewModel @Inject constructor(
    devModeState: DevModeState
) : ViewModel() {
    val devModeEnabled: StateFlow<Boolean> = devModeState.enabled
}
