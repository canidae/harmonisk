package net.exent.harmonisk.ui.profile

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import net.exent.harmonisk.R
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.music.Instrument

class ProfileDialogState {
    var showSwitchConfirm by mutableStateOf(false)
        private set
    var showProfile by mutableStateOf(false)
        private set
    var showCreate by mutableStateOf(false)
        private set

    fun open(sessionActive: Boolean) {
        if (sessionActive) showSwitchConfirm = true else showProfile = true
    }

    fun dismissSwitchConfirm() {
        showSwitchConfirm = false
    }

    fun confirmSwitch() {
        showSwitchConfirm = false
        showProfile = true
    }

    fun dismissProfile() {
        showProfile = false
    }

    fun openCreate() {
        showProfile = false
        showCreate = true
    }

    fun dismissCreate() {
        showCreate = false
    }
}

@Composable
fun rememberProfileDialogState(): ProfileDialogState = remember { ProfileDialogState() }

@Composable
fun ProfileDialogs(
    state: ProfileDialogState,
    profiles: List<ProfileRepository.Profile>,
    activeProfileId: String?,
    initialInstrument: Instrument,
    onSelect: (String) -> Unit,
    onCreate: (String, String, Instrument) -> Boolean
) {
    if (state.showSwitchConfirm) {
        AlertDialog(
            onDismissRequest = state::dismissSwitchConfirm,
            title = { Text(stringResource(R.string.profile_switch_confirm_title)) },
            text = { Text(stringResource(R.string.profile_switch_confirm_body)) },
            confirmButton = {
                TextButton(onClick = state::confirmSwitch) {
                    Text(stringResource(R.string.profile_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissSwitchConfirm) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (state.showProfile) {
        ProfileDialog(
            profiles = profiles,
            activeProfileId = activeProfileId,
            onSelect = {
                onSelect(it)
                state.dismissProfile()
            },
            onAdd = state::openCreate,
            onDismiss = state::dismissProfile
        )
    }

    if (state.showCreate) {
        CreateProfileDialog(
            initialInstrument = initialInstrument,
            onCreate = onCreate,
            onDismiss = state::dismissCreate
        )
    }
}
