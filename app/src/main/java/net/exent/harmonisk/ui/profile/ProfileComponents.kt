package net.exent.harmonisk.ui.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.exent.harmonisk.R
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.music.Instrument
import net.exent.harmonisk.ui.components.AppLogo
import net.exent.harmonisk.ui.components.InstrumentSelector
import net.exent.harmonisk.ui.components.brandBackground

@Composable
fun ProfileButton(name: String, emoji: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .widthIn(max = 150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 17.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
    }
}

@Composable
fun ProfileSetupScreen(
    initialInstrument: Instrument = Instrument.VIOLIN,
    onCreate: (String, String, Instrument) -> Boolean
) {
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf(ProfileRepository.DEFAULT_EMOJI) }
    var instrument by rememberSaveable { mutableStateOf(initialInstrument.name) }
    var errorRes by rememberSaveable { mutableStateOf<Int?>(null) }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            errorRes = R.string.profile_name_required
        } else if (!onCreate(trimmed, emoji, Instrument.fromName(instrument))) {
            errorRes = R.string.profile_name_taken
        }
    }

    Box(modifier = Modifier.fillMaxSize().brandBackground()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .safeDrawingPadding()
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppLogo(size = 76.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(28.dp))
            ElevatedCard(modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_setup_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    ProfileNameField(
                        name = name,
                        error = errorRes?.let { stringResource(it) },
                        onNameChange = {
                            name = it.take(ProfileRepository.MAX_NAME_LENGTH)
                            errorRes = null
                        },
                        onSubmit = ::submit
                    )
                    EmojiPicker(selected = emoji, onSelect = { emoji = it })
                    InstrumentField(
                        instrument = Instrument.fromName(instrument),
                        onSelect = { instrument = it.name }
                    )
                    Button(
                        onClick = ::submit,
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.profile_create))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileDialog(
    profiles: List<ProfileRepository.Profile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onEdit: ((ProfileRepository.Profile) -> Unit)? = null
) {
    var pendingDelete by remember { mutableStateOf<ProfileRepository.Profile?>(null) }
    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.profile_delete_title, target.name)) },
            text = { Text(stringResource(R.string.profile_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete?.invoke(target.id)
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_switch_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        val selected = profile.id == activeProfileId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { onSelect(profile.id) },
                                    onLongClick = onDelete?.let { { pendingDelete = profile } },
                                    onLongClickLabel = onDelete?.let { stringResource(R.string.profile_delete) }
                                )
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(profile.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            onEdit?.let { edit ->
                                IconButton(onClick = { edit(profile) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.profile_edit),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.profile_current),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onAdd) {
                    Text(stringResource(R.string.profile_add))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ProfileSwitchDialog(
    profiles: List<ProfileRepository.Profile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) = ProfileDialog(profiles, activeProfileId, onSelect, onAdd, onDismiss)

@Composable
fun ProfileIconButton(emoji: String, name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(role = Role.Button, onClickLabel = name, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 18.sp)
    }
}

@Composable
fun CreateProfileDialog(
    initialInstrument: Instrument,
    showInstrument: Boolean = true,
    onCreate: (String, String, Instrument) -> Boolean,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf(ProfileRepository.DEFAULT_EMOJI) }
    var instrument by rememberSaveable { mutableStateOf(initialInstrument.name) }
    var errorRes by rememberSaveable { mutableStateOf<Int?>(null) }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            errorRes = R.string.profile_name_required
        } else if (!onCreate(trimmed, emoji, Instrument.fromName(instrument))) {
            errorRes = R.string.profile_name_taken
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_add_title)) },
        text = {
            Column {
                ProfileNameField(
                    name = name,
                    error = errorRes?.let { stringResource(it) },
                    onNameChange = {
                        name = it.take(ProfileRepository.MAX_NAME_LENGTH)
                        errorRes = null
                    },
                    onSubmit = ::submit
                )
                Spacer(Modifier.height(8.dp))
                EmojiPicker(selected = emoji, onSelect = { emoji = it })
                if (showInstrument) {
                    Spacer(Modifier.height(8.dp))
                    InstrumentField(
                        instrument = Instrument.fromName(instrument),
                        onSelect = { instrument = it.name }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.profile_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ProfileEditDialog(
    profile: ProfileRepository.Profile,
    instrument: Instrument,
    onSave: (String, String, Instrument) -> Boolean,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var emoji by rememberSaveable(profile.id) { mutableStateOf(profile.emoji) }
    var selectedInstrument by rememberSaveable(profile.id, instrument.name) {
        mutableStateOf(instrument.name)
    }
    var errorRes by rememberSaveable(profile.id) { mutableStateOf<Int?>(null) }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            errorRes = R.string.profile_name_required
        } else if (!onSave(trimmed, emoji, Instrument.fromName(selectedInstrument))) {
            errorRes = R.string.profile_name_taken
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_edit)) },
        text = {
            Column {
                ProfileNameField(
                    name = name,
                    error = errorRes?.let { stringResource(it) },
                    onNameChange = {
                        name = it.take(ProfileRepository.MAX_NAME_LENGTH)
                        errorRes = null
                    },
                    onSubmit = ::submit
                )
                Spacer(Modifier.height(8.dp))
                EmojiPicker(selected = emoji, onSelect = { emoji = it })
                Spacer(Modifier.height(8.dp))
                InstrumentField(
                    instrument = Instrument.fromName(selectedInstrument),
                    onSelect = { selectedInstrument = it.name }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun InstrumentField(instrument: Instrument, onSelect: (Instrument) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.profile_instrument),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        InstrumentSelector(selected = instrument, onSelect = onSelect)
    }
}

@Composable
private fun EmojiPicker(selected: String, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 216.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ProfileRepository.PROFILE_EMOJIS.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (emoji == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                else Color.Transparent
                            )
                            .border(
                                width = if (emoji == selected) 2.dp else 1.dp,
                                color = if (emoji == selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                shape = CircleShape
                            )
                            .clickable { onSelect(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
                repeat(6 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfileNameField(
    name: String,
    error: String?,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.profile_name)) },
        singleLine = true,
        isError = error != null,
        supportingText = {
            Text(
                text = error ?: stringResource(
                    R.string.profile_name_counter,
                    name.length,
                    ProfileRepository.MAX_NAME_LENGTH
                )
            )
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = Modifier.fillMaxWidth()
    )
}
