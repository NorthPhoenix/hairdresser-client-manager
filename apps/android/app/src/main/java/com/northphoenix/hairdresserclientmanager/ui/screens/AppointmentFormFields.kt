package com.northphoenix.hairdresserclientmanager.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.domain.CalendarWeek
import com.northphoenix.hairdresserclientmanager.domain.NightStart
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.Notice
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.PickerField
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.icon
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

// The fields for the time and the place of an Appointment. Appointment Creation and the edit of an
// Appointment show the same ones, so that both ask the same way. Each form holds its own values.

/** The fields of [AppointmentTimeFields] that open a picker. */
enum class AppointmentTimeField { DATE, START, END }

/**
 * Date, start time and end time. One field to a line, each showing its value whole: side by side
 * the times were cut on narrow screens and at large font sizes. [endDate] is the day the end time
 * falls on, which is said under the field when it is another day than [date]. A start at night is
 * said under its field too (see [NightStart]), with [onDayTime] to move it to the same time on the
 * other half of the day. Under the date are the other Appointments of that day ([DayAppointments]),
 * without [movingAppointmentId], the one whose time is being edited.
 */
@Composable
fun AppointmentTimeFields(
    date: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime?,
    endDate: LocalDate?,
    onOpen: (AppointmentTimeField) -> Unit,
    onClearEnd: () -> Unit,
    onDayTime: () -> Unit,
    movingAppointmentId: String? = null,
) {
    val format = LocalTimeFormat.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PickerField(
                label = stringResource(R.string.appointment_date_label),
                value = format.longDay(date),
                icon = Icons.Rounded.CalendarMonth,
                onClick = { onOpen(AppointmentTimeField.DATE) },
                wraps = true,
            )
            // The date picker is opened on nearly every booking. Its month is loaded with the form, as the list under
            // the date reads it, and so is the next one: the picker opens with its days marked, and a day picked in
            // either month is listed at once.
            PreloadAppointmentMonths(YearMonth.from(date).plusMonths(1))
            DayAppointments(date, excluding = movingAppointmentId)
        }
        Column {
            PickerField(
                label = stringResource(R.string.appointment_start_label),
                value = format.time(startTime),
                icon = Icons.Rounded.Schedule,
                onClick = { onOpen(AppointmentTimeField.START) },
                wraps = true,
            )
            NightStart.dayTime(startTime)?.let { dayTime ->
                NightStartHint(format.time(startTime), format.time(dayTime), onDayTime)
            }
        }
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

/**
 * What [date] already has, under its field, so that a time is picked knowing it: each Appointment
 * that is not canceled ([CalendarWeek.bookedOn]) with its time, its primary Client, how many more
 * Clients it has and a house when it is at home. It comes from the day's month as the date picker
 * reads it ([monthAppointmentsState]); "No Appointments on this day" ("No other Appointments" while one
 * is moved) once it is known to have none, and nothing while it is loading or could not be loaded,
 * where the form is no place to say so.
 */
@Composable
private fun DayAppointments(date: LocalDate, excluding: String?) {
    val format = LocalTimeFormat.current
    val read by monthAppointmentsState(YearMonth.from(date))
    val booked = read.data?.let { CalendarWeek.bookedOn(it, date, format.zone, excluding) } ?: return
    val times = booked.map { appointment -> listOfNotNull(format.time(appointment.startsAt), appointment.endsAt?.let { format.time(it) }).joinToString("–") }
    val timeStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    // The names start in one column: as wide as the widest of the day's times.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timeWidth = remember(times, timeStyle, density) { with(density) { times.maxOfOrNull { measurer.measure(it, timeStyle).size.width }?.toDp() ?: 0.dp } }

    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (booked.isEmpty()) {
            // While an Appointment is moved, the day has at least that one: none of the others.
            Text(stringResource(if (excluding == null) R.string.appointment_day_free else R.string.appointment_day_free_of_others), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        Text(stringResource(R.string.appointment_day_booked), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        booked.forEachIndexed { index, appointment ->
            // Named as its card names it: by a Client who is in it, also when its primary Client is not (AppointmentClients.Fault).
            val others = AppointmentClients.othersCount(appointment)
            val name = AppointmentClients.leadName(appointment) + if (others > 0) " +$others" else ""
            val place = stringResource(appointment.locationType.label)

            // A long name takes a second line rather than being cut to its first word; the time and the house stay on its first line.
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${times[index]}, $name, $place" },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(times[index], modifier = Modifier.width(timeWidth).alignByBaseline(), style = timeStyle, maxLines = 1, softWrap = false)
                Text(
                    name,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (appointment.locationType == LocationType.AT_HOME) {
                    Icon(
                        appointment.locationType.icon,
                        contentDescription = null,
                        // Its foot on the line the letters stand on.
                        modifier = Modifier.size(16.dp).alignBy { it.measuredHeight * 7 / 8 },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "Starts at 11:00 PM. Did you mean 11:00 AM?" under a start at night, and the button that changes it. */
@Composable
private fun NightStartHint(start: String, dayTime: String, onDayTime: () -> Unit) {
    Column {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp), tint = HcmColors.Warning)
            Text(stringResource(R.string.night_start_question, start, dayTime), style = MaterialTheme.typography.bodyMedium, color = HcmColors.Warning)
        }
        TextAction(stringResource(R.string.night_start_change, dayTime), onClick = onDayTime, modifier = Modifier.align(Alignment.End))
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

/**
 * The starts at night the Stylist kept, on this device only ([NightStart.keptKey]): "Time is right"
 * on the Appointment or on its card in the Home View, a form saved with the question under its
 * start time, or its reminder sent checked from 'Remind all'. The Appointment does not ask about
 * that time again.
 */
internal class KeptNightStarts(context: Context) {
    private val memory = context.applicationContext.getSharedPreferences("night_starts_kept", Context.MODE_PRIVATE)

    fun all(): Set<String> = memory.getStringSet(KEY, emptySet()).orEmpty()

    /** Keeps the start of [appointmentId] at [startsAt], and returns all that are kept then. */
    fun keep(appointmentId: String, startsAt: Instant): Set<String> {
        val kept = all() + NightStart.keptKey(appointmentId, startsAt)

        memory.edit().putStringSet(KEY, kept).apply()
        return kept
    }

    /** Calls [onChange] whenever a start is kept, on any screen, until the returned listener is let go. */
    fun listen(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY) onChange() }
            .also(memory::registerOnSharedPreferenceChangeListener)

    fun stopListening(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        memory.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val KEY = "appointments"
    }
}

/**
 * The starts at night kept on this device ([KeptNightStarts]), as they are now: a start kept on the
 * Appointment screen no longer asks on the Home View's card either.
 */
@Composable
internal fun rememberKeptNightStarts(): State<Set<String>> {
    val context = LocalContext.current
    val store = remember(context) { KeptNightStarts(context) }
    val kept = remember(store) { mutableStateOf(store.all()) }

    DisposableEffect(store) {
        // Held here: the preferences keep only a weak reference to it.
        val listener = store.listen { kept.value = store.all() }

        onDispose { store.stopListening(listener) }
    }
    return kept
}

/**
 * Moves [appointment], which starts at night, to the same time on the other half of the same day,
 * the end time with it ([NightStart.moved]), as the edit form saves it. "Start time moved to 11:00 AM."
 * once it is saved.
 */
internal fun moveNightStartToDayTime(viewModel: AppViewModel, appointment: Appointment, format: TimeFormat) {
    val current = AppointmentDraft.saved(appointment, format)
    val moved = NightStart.moved(current.startTime, current.endTime) ?: return

    when (val times = AppointmentDraft.times(current.date, moved.start, moved.end, format)) {
        is AppointmentDraft.Times.Valid -> viewModel.editAppointment(
            appointment.id,
            times,
            locationType = null,
            ownAddress = null,
            saved = Notice(R.string.night_start_moved, args = listOf(format.time(moved.start))),
        ) {}
        AppointmentDraft.Times.StartSkipped -> viewModel.notify(R.string.appointment_start_time_skipped, isError = true)
        AppointmentDraft.Times.EndSkipped -> viewModel.notify(R.string.appointment_end_time_skipped, isError = true)
        AppointmentDraft.Times.EndNotAfterStart -> viewModel.notify(R.string.appointment_end_before_start, isError = true)
    }
}
