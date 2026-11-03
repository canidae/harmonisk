package net.exent.harmonisk.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
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
import net.exent.harmonisk.R
import net.exent.harmonisk.data.ProfileRepository
import net.exent.harmonisk.ui.components.AppLogo
import net.exent.harmonisk.ui.components.brandBackground

@Composable
fun ProfileButton(name: String, onClick: () -> Unit) {
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
        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
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
fun ProfileSetupScreen(onCreate: (String) -> Boolean) {
    var name by rememberSaveable { mutableStateOf("") }
    var errorRes by rememberSaveable { mutableStateOf<Int?>(null) }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            errorRes = R.string.profile_name_required
        } else if (!onCreate(trimmed)) {
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
                    Text(
                        text = stringResource(R.string.profile_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
fun ProfileDialog(
    profiles: List<ProfileRepository.Profile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
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
                            .clickable { onSelect(profile.id) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
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

@Composable
fun CreateProfileDialog(
    onCreate: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var errorRes by rememberSaveable { mutableStateOf<Int?>(null) }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            errorRes = R.string.profile_name_required
        } else if (!onCreate(trimmed)) {
            errorRes = R.string.profile_name_taken
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_add_title)) },
        text = {
            ProfileNameField(
                name = name,
                error = errorRes?.let { stringResource(it) },
                onNameChange = {
                    name = it.take(ProfileRepository.MAX_NAME_LENGTH)
                    errorRes = null
                },
                onSubmit = ::submit
            )
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
