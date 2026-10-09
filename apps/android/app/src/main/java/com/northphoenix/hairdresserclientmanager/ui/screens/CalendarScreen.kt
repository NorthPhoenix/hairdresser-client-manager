package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.AppointmentCard
import com.northphoenix.hairdresserclientmanager.ui.components.CircleIconButton
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.WeekFields

/** Calendar View: Appointments for one day, with a week strip for moving between days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: AppViewModel, onOpenAppointment: (String, String) -> Unit) {
    val format = LocalTimeFormat.current
    val locale = format.locale
    val selected by viewModel.calendarDate.collectAsStateWithLifecycle()
    val appointments by viewModel.calendar.collectAsStateWithLifecycle()
    val loading by viewModel.calendarLoading.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val today = format.today()
    val weekStart = remember(selected, locale) { selected.with(WeekFields.of(locale).dayOfWeek(), 1) }
    var showDatePicker by remember { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Kicker(stringResource(R.string.calendar_title), color = MaterialTheme.colorScheme.primary)
                        Text(format.monthAndYear(selected), style = MaterialTheme.typography.displaySmall)
                    }
                    CircleIconButton(Icons.Rounded.Today, stringResource(R.string.appointment_date_label), onClick = { showDatePicker = true })
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CircleIconButton(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        stringResource(R.string.previous_day),
                        onClick = { viewModel.setCalendarDate(selected.minusDays(1)) },
                    )
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0L..6L).forEach { offset ->
                            val day = weekStart.plusDays(offset)
                            DayCell(
                                weekday = format.shortWeekday(day),
                                dayOfMonth = day.dayOfMonth.toString(),
                                description = format.longDay(day),
                                selected = day == selected,
                                today = day == today,
                                onClick = { viewModel.setCalendarDate(day) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    CircleIconButton(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        stringResource(R.string.next_day),
                        onClick = { viewModel.setCalendarDate(selected.plusDays(1)) },
                    )
                }
            }

            item {
                ListSectionLabel(
                    if (selected == today) stringResource(R.string.tab_today) + " · " + format.longDay(selected) else format.longDay(selected),
                    appointments.size,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (!loaded || (loading && appointments.isEmpty())) {
                item { LoadingRow() }
            } else if (appointments.isEmpty()) {
                item { EmptyState(Icons.Rounded.EventBusy, stringResource(R.string.appointments_empty_title), body = stringResource(R.string.calendar_empty_body)) }
            }

            items(appointments, key = { it.id }) { appointment ->
                AppointmentCard(appointment, onClick = { onOpenAppointment(appointment.id, appointment.startsAt) })
            }
        }
    }

    if (showDatePicker) {
        HcmDatePickerDialog(
            initial = selected,
            onDismiss = { showDatePicker = false },
            onPick = {
                showDatePicker = false
                viewModel.setCalendarDate(it)
            },
        )
    }
}

@Composable
private fun DayCell(
    weekday: String,
    dayOfMonth: String,
    description: String,
    selected: Boolean,
    today: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (selected) HcmColors.Surface else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) HcmColors.Ink else Color.Transparent,
        border = if (today && !selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                weekday,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) content.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(dayOfMonth, style = MaterialTheme.typography.titleMedium, color = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HcmDatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The Material picker works in UTC midnights regardless of the Stylist's timezone.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
                },
            ) { Text(stringResource(R.string.ok), style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        DatePicker(
            state = state,
            // The grid alone says enough; the built-in header only repeats the selected date.
            title = null,
            headline = null,
            showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedDayContainerColor = HcmColors.Ink,
                selectedDayContentColor = HcmColors.Surface,
                todayDateBorderColor = MaterialTheme.colorScheme.primary,
                todayContentColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
