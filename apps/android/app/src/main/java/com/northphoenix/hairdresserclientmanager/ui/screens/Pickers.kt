package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.i18n.emptyClientPickerText
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.HcmListBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.InlineEmpty
import com.northphoenix.hairdresserclientmanager.ui.components.InlineLoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.LocalTime

/**
 * Sheet for choosing Clients. With [onCreateInline] it also offers creating a
 * minimal Client from just the typed name, for walk-ins.
 * [clients] is the Stylist's whole Client list and [load] its state. The Clients in [takenIds]
 * are in the Appointment already and are not offered; the sheet needs the whole list all the
 * same, to tell a Stylist without Clients from one whose Clients are all taken.
 */
@Composable
fun ClientPickerSheet(
    title: String,
    clients: List<ClientProfile>,
    load: LoadState,
    onRetry: () -> Unit,
    selectedIds: Set<String>,
    onPick: (ClientProfile) -> Unit,
    onDismiss: () -> Unit,
    onCreateInline: ((String) -> Unit)? = null,
    takenIds: Set<String> = emptySet(),
) {
    var search by rememberSaveable { mutableStateOf("") }
    val offered = remember(clients, takenIds) { clients.filter { it.id !in takenIds } }
    val filtered = remember(offered, search) { ClientRules.filter(offered, search) }

    // A lazy list: a Stylist with hundreds of Clients gets the sheet as fast as one with ten.
    HcmListBottomSheet(
        title = title,
        onDismiss = onDismiss,
        content = {
            HcmTextField(
                value = search,
                onValueChange = { search = it },
                label = "",
                placeholder = stringResource(if (onCreateInline != null) R.string.client_name_label else R.string.client_search_label),
                leadingIcon = Icons.Rounded.Search,
                capitalization = KeyboardCapitalization.Words,
                // What is typed here can become the name of a new Client.
                maxLength = TextLimits.NAME.takeIf { onCreateInline != null },
            )

            if (onCreateInline != null && search.isNotBlank()) {
                SecondaryButton(
                    text = stringResource(R.string.inline_client_label) + ": " + search.trim(),
                    onClick = { onCreateInline(search.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.PersonAddAlt1,
                )
            }

            // "No Clients yet" is only true of a list that has loaded.
            when (load) {
                is LoadState.Failed -> InlineLoadFailed(load.message, onRetry)
                LoadState.Loaded -> if (filtered.isEmpty()) {
                    InlineEmpty(stringResource(emptyClientPickerText(clients = clients.size, offered = offered.size)))
                }
                else -> LoadingRow(Modifier.heightIn(max = 96.dp))
            }
        },
    ) {
        items(filtered, key = { it.id }) { client ->
            ClientRow(
                client,
                onClick = { onPick(client) },
                trailing = {
                    if (client.id in selectedIds) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HcmTimePickerDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = !LocalTimeFormat.current.uses12HourClock,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectorColor = HcmColors.Ink,
                        clockDialSelectedContentColor = HcmColors.Surface,
                        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        periodSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        periodSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.ok), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
