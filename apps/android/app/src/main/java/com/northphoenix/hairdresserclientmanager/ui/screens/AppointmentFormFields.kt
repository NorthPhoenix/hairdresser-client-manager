package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.PickerField
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import java.time.LocalDate
import java.time.LocalTime

// The fields for the time and the place of an Appointment. Appointment Creation and the edit of an
// Appointment show the same ones, so that both ask the same way. Each form holds its own values.

/** The fields of [AppointmentTimeFields] that open a picker. */
enum class AppointmentTimeField { DATE, START, END }

/**
 * Date, start time and end time. One field to a line, each showing its value whole: side by side
 * the times were cut on narrow screens and at large font sizes. [endDate] is the day the end time
 * falls on, which is said under the field when it is another day than [date].
 */
@Composable
fun AppointmentTimeFields(
    date: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime?,
    endDate: LocalDate?,
    onOpen: (AppointmentTimeField) -> Unit,
    onClearEnd: () -> Unit,
) {
    val format = LocalTimeFormat.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PickerField(
            label = stringResource(R.string.appointment_date_label),
            value = format.longDay(date),
            icon = Icons.Rounded.CalendarMonth,
            onClick = { onOpen(AppointmentTimeField.DATE) },
            wraps = true,
        )
        PickerField(
            label = stringResource(R.string.appointment_start_label),
            value = format.time(startTime),
            icon = Icons.Rounded.Schedule,
            onClick = { onOpen(AppointmentTimeField.START) },
            wraps = true,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PickerField(
                label = stringResource(R.string.appointment_end_label),
                value = endTime?.let(format::time) ?: stringResource(R.string.optional),
                isPlaceholder = endTime == null,
                icon = Icons.Rounded.Schedule,
                onClick = { onOpen(AppointmentTimeField.END) },
                trailing = if (endTime != null) {
                    { IconButton(onClick = onClearEnd) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear)) } }
                } else {
                    null
                },
                wraps = true,
            )
            if (endDate != null && endDate != date) {
                Text(
                    stringResource(R.string.appointment_ends_next_day) + " · " + format.longDay(endDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The picker of the field that is [open]. Picking a value closes it. */
@Composable
fun AppointmentTimePicker(
    open: AppointmentTimeField?,
    date: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onDate: (LocalDate) -> Unit,
    onStartTime: (LocalTime) -> Unit,
    onEndTime: (LocalTime) -> Unit,
) {
    when (open) {
        AppointmentTimeField.DATE -> HcmDatePickerDialog(
            initial = date,
            today = today,
            onDismiss = onDismiss,
            onPick = {
                onDate(it)
                onDismiss()
            },
        )
        AppointmentTimeField.START -> HcmTimePickerDialog(
            title = stringResource(R.string.appointment_start_label),
            initial = startTime,
            onDismiss = onDismiss,
            onPick = {
                onStartTime(it)
                onDismiss()
            },
        )
        AppointmentTimeField.END -> HcmTimePickerDialog(
            title = stringResource(R.string.appointment_end_label),
            initial = endTime ?: startTime.plusHours(1),
            onDismiss = onDismiss,
            onPick = {
                onEndTime(it)
                onDismiss()
            },
        )
        null -> Unit
    }
}

/**
 * In salon or at home, and at home the [address] of the Appointment: the primary Client's unless
 * the Stylist types another one.
 */
@Composable
fun AppointmentLocationFields(
    locationType: LocationType,
    onLocationType: (LocationType) -> Unit,
    address: String,
    onAddress: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.appointment_location_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedControl(
                options = LocationType.entries,
                selected = locationType,
                onSelect = onLocationType,
                label = { stringResource(it.label) },
            )
        }
        if (locationType == LocationType.AT_HOME) {
            HcmTextField(
                value = address,
                onValueChange = onAddress,
                label = stringResource(R.string.appointment_address_label),
                placeholder = stringResource(R.string.client_address_label),
                singleLine = false,
                minLines = 2,
                capitalization = KeyboardCapitalization.Words,
                maxLength = TextLimits.ADDRESS,
            )
        }
    }
}
