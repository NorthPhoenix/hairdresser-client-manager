package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import java.time.LocalDate

/**
 * Edit Appointment: the day, the times and the place of an Appointment that exists, with the
 * fields and the rules of Appointment Creation.
 *
 * The form is filled from the Appointment when the sheet opens. A part of it starts again from the
 * Appointment when that part changes underneath it, so the form never puts back a time or a place
 * that was changed elsewhere in the meantime. [onSave] gets the times only when the Stylist changed
 * them (see [AppointmentDraft.editedTimes]), and the at-home address only when it is the Stylist's
 * own; [onRefused] gets the reason, a string resource, for times that cannot be saved.
 */
@Composable
internal fun EditAppointmentSheet(
    appointment: Appointment,
    today: LocalDate,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRefused: (Int) -> Unit,
    onSave: (times: AppointmentDraft.Times.Valid?, locationType: LocationType, ownAddress: String?) -> Unit,
) {
    val format = LocalTimeFormat.current
    val saved = AppointmentDraft.saved(appointment, format)
    val primaryClientAddress = appointment.participants.firstOrNull { it.isPrimary }?.address

    // The day and the times are the Appointment's in the Stylist's timezone: in another timezone they are other ones.
    val zone = format.zone.id
    var date by rememberSaveable(appointment.id, appointment.startsAt, appointment.endsAt, zone) { mutableStateOf(saved.date) }
    var startTime by rememberSaveable(appointment.id, appointment.startsAt, appointment.endsAt, zone) { mutableStateOf(saved.startTime) }
    var endTime by rememberSaveable(appointment.id, appointment.startsAt, appointment.endsAt, zone) { mutableStateOf(saved.endTime) }
    var locationType by rememberSaveable(appointment.id, appointment.locationType, appointment.customLocationAddress) { mutableStateOf(saved.locationType) }
    // What the Stylist typed into the at-home address field. Null shows the primary Client's address, as in Appointment Creation.
    var typedAddress by rememberSaveable(appointment.id, appointment.locationType, appointment.customLocationAddress) { mutableStateOf(saved.ownAddress) }
    var picker by rememberSaveable { mutableStateOf<AppointmentTimeField?>(null) }

    val ownAddress = AppointmentDraft.ownAddress(typedAddress, primaryClientAddress)

    fun save() {
        val place = { times: AppointmentDraft.Times.Valid? -> onSave(times, locationType, ownAddress.takeIf { locationType == LocationType.AT_HOME }) }

        when (val times = AppointmentDraft.editedTimes(saved, date, startTime, endTime, format)) {
            AppointmentDraft.Times.StartSkipped -> onRefused(R.string.appointment_start_time_skipped)
            AppointmentDraft.Times.EndSkipped -> onRefused(R.string.appointment_end_time_skipped)
            AppointmentDraft.Times.EndNotAfterStart -> onRefused(R.string.appointment_end_before_start)
            is AppointmentDraft.Times.Valid -> place(times)
            null -> place(null)
        }
    }

    HcmBottomSheet(title = stringResource(R.string.edit_appointment), onDismiss = onDismiss) {
        AppointmentTimeFields(
            date = date,
            startTime = startTime,
            endTime = endTime,
            endDate = AppointmentDraft.editedEndDate(saved, date, startTime, endTime),
            onOpen = { picker = it },
            onClearEnd = { endTime = null },
        )
        Hairline()
        AppointmentLocationFields(
            locationType = locationType,
            onLocationType = {
                locationType = it
                // An address that is not the Stylist's own does not stay for the other kind of place.
                typedAddress = ownAddress
            },
            address = AppointmentDraft.shownAddress(typedAddress, primaryClientAddress),
            onAddress = { typedAddress = it },
        )
        PrimaryButton(stringResource(R.string.save_appointment_time_and_place), onClick = ::save, modifier = Modifier.fillMaxWidth(), loading = busy)
    }

    AppointmentTimePicker(
        open = picker,
        date = date,
        startTime = startTime,
        endTime = endTime,
        today = today,
        onDismiss = { picker = null },
        onDate = { date = it },
        onStartTime = { startTime = it },
        onEndTime = { endTime = it },
    )
}
