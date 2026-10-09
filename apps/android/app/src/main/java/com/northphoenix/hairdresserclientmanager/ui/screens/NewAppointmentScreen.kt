package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.BottomActionBar
import com.northphoenix.hairdresserclientmanager.ui.components.ChoiceChip
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.PickerField
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

private enum class AppointmentPicker { PRIMARY, ADDITIONAL, DATE, START, END }

/** Appointment Creation: primary Client (existing or a minimal inline one), time and location. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewAppointmentScreen(viewModel: AppViewModel, initialDate: LocalDate?, onBack: () -> Unit, onCreated: (Appointment) -> Unit) {
    val format = LocalTimeFormat.current
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    // Default start: the next full hour, in the Stylist's timezone.
    val nextHour = remember { ZonedDateTime.now(format.zone).withMinute(0).withSecond(0).withNano(0).plusHours(1) }

    var primaryClientId by rememberSaveable { mutableStateOf<String?>(null) }
    var inlineClientName by rememberSaveable { mutableStateOf("") }
    var additionalClientIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var date by rememberSaveable { mutableStateOf(initialDate ?: nextHour.toLocalDate()) }
    var startTime by rememberSaveable { mutableStateOf(nextHour.toLocalTime()) }
    var endTime by rememberSaveable { mutableStateOf<LocalTime?>(null) }
    var locationType by rememberSaveable { mutableStateOf(LocationType.IN_SALON) }
    var address by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf<AppointmentPicker?>(null) }

    val primaryClient = clients.firstOrNull { it.id == primaryClientId }
    val additionalClients = clients.filter { it.id in additionalClientIds }

    fun submit() {
        val end = endTime

        when {
            primaryClientId == null && inlineClientName.isBlank() -> viewModel.notify(R.string.appointment_client_required, isError = true)
            end != null && !end.isAfter(startTime) -> viewModel.notify(R.string.appointment_end_before_start, isError = true)
            else -> viewModel.createAppointment(
                primaryClientId = primaryClientId,
                additionalClientIds = additionalClientIds,
                inlineClientName = inlineClientName.takeIf { primaryClientId == null },
                startsAt = format.toInstant(date, startTime),
                endsAt = end?.let { format.toInstant(date, it) },
                locationType = locationType,
                customLocationAddress = address.takeIf { locationType == LocationType.AT_HOME },
                onSuccess = { onCreated(it.appointment) },
            )
        }
    }

    SubScreen(
        title = stringResource(R.string.new_appointment_title),
        onBack = onBack,
        busy = busy,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(stringResource(R.string.create_appointment), onClick = ::submit, modifier = Modifier.fillMaxWidth(), loading = busy)
            }
        },
    ) { padding ->
        ScreenColumn(padding) {
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PickerField(
                        label = stringResource(R.string.primary_client_label),
                        value = primaryClient?.name ?: inlineClientName.ifBlank { stringResource(R.string.choose_client) },
                        isPlaceholder = primaryClient == null && inlineClientName.isBlank(),
                        icon = Icons.Rounded.Person,
                        onClick = { picker = AppointmentPicker.PRIMARY },
                    )

                    // Group Appointments need an existing primary Client, as on the server.
                    if (primaryClient != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.additional_clients_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                additionalClients.forEach { client ->
                                    ChoiceChip(client.name, selected = true, icon = Icons.Rounded.Close, onClick = { additionalClientIds = additionalClientIds - client.id })
                                }
                                ChoiceChip(stringResource(R.string.add_client_to_appointment), selected = false, icon = Icons.Rounded.Add, onClick = { picker = AppointmentPicker.ADDITIONAL })
                            }
                        }
                    }
                }
            }

            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PickerField(
                        label = stringResource(R.string.appointment_date_label),
                        value = format.longDay(date),
                        icon = Icons.Rounded.CalendarMonth,
                        onClick = { picker = AppointmentPicker.DATE },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerField(
                            label = stringResource(R.string.appointment_start_label),
                            value = format.time(startTime),
                            icon = Icons.Rounded.Schedule,
                            onClick = { picker = AppointmentPicker.START },
                            modifier = Modifier.weight(1f),
                        )
                        PickerField(
                            label = stringResource(R.string.appointment_end_label),
                            value = endTime?.let(format::time) ?: stringResource(R.string.optional),
                            isPlaceholder = endTime == null,
                            onClick = { picker = AppointmentPicker.END },
                            modifier = Modifier.weight(1f),
                            trailing = if (endTime != null) {
                                { IconButton(onClick = { endTime = null }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear)) } }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stringResource(R.string.appointment_location_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SegmentedControl(
                            options = LocationType.entries,
                            selected = locationType,
                            onSelect = {
                                locationType = it
                                // An at-home Appointment defaults to the primary Client's address.
                                if (it == LocationType.AT_HOME && address.isBlank()) {
                                    address = primaryClient?.address.orEmpty()
                                }
                            },
                            label = { stringResource(it.label) },
                        )
                    }
                    if (locationType == LocationType.AT_HOME) {
                        HcmTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = stringResource(R.string.appointment_address_label),
                            placeholder = stringResource(R.string.client_address_label),
                            singleLine = false,
                            minLines = 2,
                            capitalization = KeyboardCapitalization.Words,
                        )
                    }
                }
            }
        }
    }

    when (picker) {
        AppointmentPicker.PRIMARY -> ClientPickerSheet(
            title = stringResource(R.string.primary_client_label),
            clients = clients,
            selectedIds = setOfNotNull(primaryClientId),
            onDismiss = { picker = null },
            onPick = { client ->
                primaryClientId = client.id
                inlineClientName = ""
                additionalClientIds = additionalClientIds - client.id
                if (locationType == LocationType.AT_HOME && address.isBlank()) {
                    address = client.address
                }
                picker = null
            },
            onCreateInline = { name ->
                inlineClientName = name
                primaryClientId = null
                additionalClientIds = emptyList()
                picker = null
            },
        )
        AppointmentPicker.ADDITIONAL -> ClientPickerSheet(
            title = stringResource(R.string.additional_clients_label),
            clients = clients.filter { it.id != primaryClientId },
            selectedIds = additionalClientIds.toSet(),
            onDismiss = { picker = null },
            onPick = { client ->
                additionalClientIds = if (client.id in additionalClientIds) additionalClientIds - client.id else additionalClientIds + client.id
            },
        )
        AppointmentPicker.DATE -> HcmDatePickerDialog(
            initial = date,
            onDismiss = { picker = null },
            onPick = {
                date = it
                picker = null
            },
        )
        AppointmentPicker.START -> HcmTimePickerDialog(
            title = stringResource(R.string.appointment_start_label),
            initial = startTime,
            onDismiss = { picker = null },
            onPick = {
                startTime = it
                picker = null
            },
        )
        AppointmentPicker.END -> HcmTimePickerDialog(
            title = stringResource(R.string.appointment_end_label),
            initial = endTime ?: startTime.plusHours(1),
            onDismiss = { picker = null },
            onPick = {
                endTime = it
                picker = null
            },
        )
        null -> Unit
    }
}
