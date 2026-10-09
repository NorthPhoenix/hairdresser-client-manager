package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.InlineEmpty
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.LocalTime

/**
 * Sheet for choosing Clients. With [onCreateInline] it also offers creating a
 * minimal Client from just the typed name, for walk-ins.
 */
@Composable
fun ClientPickerSheet(
    title: String,
    clients: List<ClientProfile>,
    selectedIds: Set<String>,
    onPick: (ClientProfile) -> Unit,
    onDismiss: () -> Unit,
    onCreateInline: ((String) -> Unit)? = null,
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(clients, search) { ClientRules.filter(clients, search) }

    HcmBottomSheet(title = title, onDismiss = onDismiss) {
        HcmTextField(
            value = search,
            onValueChange = { search = it },
            label = "",
            placeholder = stringResource(if (onCreateInline != null) R.string.client_name_label else R.string.client_search_label),
            leadingIcon = Icons.Rounded.Search,
            capitalization = KeyboardCapitalization.Words,
        )

        if (onCreateInline != null && search.isNotBlank()) {
            SecondaryButton(
                text = stringResource(R.string.inline_client_label) + ": " + search.trim(),
                onClick = { onCreateInline(search.trim()) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.PersonAddAlt1,
            )
        }

        if (filtered.isEmpty()) {
            InlineEmpty(stringResource(if (clients.isEmpty()) R.string.no_clients_title else R.string.no_client_results))
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filtered.forEach { client ->
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
