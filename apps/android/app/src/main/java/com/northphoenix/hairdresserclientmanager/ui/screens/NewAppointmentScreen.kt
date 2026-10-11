package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.domain.UnsavedInput
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.BottomActionBar
import com.northphoenix.hairdresserclientmanager.ui.components.ActionChip
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.PickerField
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

private enum class AppointmentPicker { PRIMARY, ADDITIONAL }

/** Appointment Creation: primary Client (existing or a minimal inline one), time and location. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewAppointmentScreen(viewModel: AppViewModel, initialDate: LocalDate?, onBack: () -> Unit, onCreated: (Appointment) -> Unit) {
    val format = LocalTimeFormat.current
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val clientsLoad by viewModel.clientsLoad.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    // The next full hour in the Stylist's timezone, on the day chosen in the Calendar when that is another day.
    val defaultStart = remember { AppointmentDraft.defaultStart(ZonedDateTime.now(format.zone), initialDate) }

    var primaryClientId by rememberSaveable { mutableStateOf<String?>(null) }
    var inlineClientName by rememberSaveable { mutableStateOf("") }
    var additionalClientIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var date by rememberSaveable { mutableStateOf(defaultStart.toLocalDate()) }
    var startTime by rememberSaveable { mutableStateOf(defaultStart.toLocalTime()) }
    var endTime by rememberSaveable { mutableStateOf<LocalTime?>(null) }
    var locationType by rememberSaveable { mutableStateOf(LocationType.IN_SALON) }
    // What the Stylist typed into the at-home address field. Null until they type: the field then shows the primary Client's address.
    var typedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<AppointmentPicker?>(null) }
    var timePicker by rememberSaveable { mutableStateOf<AppointmentTimeField?>(null) }

    val primaryClient = clients.firstOrNull { it.id == primaryClientId }
    // In the order they were picked, which is the order the Appointment will list them in.
    val additionalClients = AppointmentClients.inPickedOrder(additionalClientIds, clients)
    val ownAddress = AppointmentDraft.ownAddress(typedAddress, primaryClient?.address)
    // An end time before the start time is on the next day. Null as well when the two times cannot be an Appointment: submit says so.
    val endDate = endTime?.let { AppointmentDraft.endDate(date, startTime, it) }

    // Back asks before it discards the draft, once it differs from what the form opened with.
    val content = UnsavedInput.newAppointment(primaryClientId, inlineClientName, additionalClientIds, date, startTime, endTime, locationType, ownAddress.orEmpty())
    val opened = rememberSaveable { content }

    // Called before the primary Client or the location changes: an address that is not the Stylist's own must not stay for the next one.
    fun keepOnlyOwnAddress() {
        typedAddress = ownAddress
    }

    fun submit() {
        if (primaryClientId == null && inlineClientName.isBlank()) {
            viewModel.notify(R.string.appointment_client_required, isError = true)
            return
        }

        when (val times = AppointmentDraft.times(date, startTime, endTime, format)) {
            AppointmentDraft.Times.StartSkipped -> viewModel.notify(R.string.appointment_start_time_skipped, isError = true)
            AppointmentDraft.Times.EndSkipped -> viewModel.notify(R.string.appointment_end_time_skipped, isError = true)
            AppointmentDraft.Times.EndNotAfterStart -> viewModel.notify(R.string.appointment_end_before_start, isError = true)
            is AppointmentDraft.Times.Valid -> viewModel.createAppointment(
                primaryClientId = primaryClientId,
                additionalClientIds = additionalClientIds,
                inlineClientName = inlineClientName.takeIf { primaryClientId == null },
                startsAt = times.startsAt,
                endsAt = times.endsAt,
                locationType = locationType,
                // Sent only when it is the Stylist's own: otherwise the Appointment takes the address of its primary Client.
                customLocationAddress = ownAddress.takeIf { locationType == LocationType.AT_HOME },
                onSuccess = { onCreated(it.appointment) },
            )
        }
    }

    SubScreen(
        title = stringResource(R.string.new_appointment_title),
        onBack = onBack,
        busy = busy,
        unsaved = content != opened,
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

                    // A Client created inline can be the primary Client of a group Appointment as well.
                    if (primaryClient != null || inlineClientName.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.additional_clients_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                additionalClients.forEach { client ->
                                    ActionChip(
                                        client.name,
                                        icon = Icons.Rounded.Close,
                                        onClick = { additionalClientIds = additionalClientIds - client.id },
                                        highlighted = true,
                                        description = stringResource(R.string.remove_additional_client, client.name),
                                    )
                                }
                                ActionChip(stringResource(R.string.add_client_to_appointment), icon = Icons.Rounded.Add, onClick = { picker = AppointmentPicker.ADDITIONAL })
                            }
                        }
                    }
                }
            }

            HcmCard {
                AppointmentTimeFields(
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                    endDate = endDate,
                    onOpen = { timePicker = it },
                    onClearEnd = { endTime = null },
                )
            }

            HcmCard {
                // An at-home Appointment is at the primary Client's address unless the Stylist types another one.
                AppointmentLocationFields(
                    locationType = locationType,
                    onLocationType = {
                        locationType = it
                        keepOnlyOwnAddress()
                    },
                    address = AppointmentDraft.shownAddress(typedAddress, primaryClient?.address),
                    onAddress = { typedAddress = it },
                )
            }
        }
    }

    when (picker) {
        AppointmentPicker.PRIMARY -> ClientPickerSheet(
            title = stringResource(R.string.primary_client_label),
            clients = clients,
            load = clientsLoad,
            onRetry = viewModel::retryLoad,
            selectedIds = setOfNotNull(primaryClientId),
            onDismiss = { picker = null },
            onPick = { client ->
                keepOnlyOwnAddress()
                primaryClientId = client.id
                inlineClientName = ""
                additionalClientIds = additionalClientIds - client.id
                picker = null
            },
            onCreateInline = { name ->
                keepOnlyOwnAddress()
                inlineClientName = name
                primaryClientId = null
                picker = null
            },
        )
        AppointmentPicker.ADDITIONAL -> ClientPickerSheet(
            title = stringResource(R.string.additional_clients_label),
            clients = clients,
            takenIds = setOfNotNull(primaryClientId),
            load = clientsLoad,
            onRetry = viewModel::retryLoad,
            selectedIds = additionalClientIds.toSet(),
            onDismiss = { picker = null },
            onPick = { client ->
                additionalClientIds = if (client.id in additionalClientIds) additionalClientIds - client.id else additionalClientIds + client.id
            },
        )
        null -> Unit
    }

    AppointmentTimePicker(
        open = timePicker,
        date = date,
        startTime = startTime,
        endTime = endTime,
        today = today,
        onDismiss = { timePicker = null },
        onDate = { date = it },
        onStartTime = { startTime = it },
        onEndTime = { endTime = it },
    )
}
