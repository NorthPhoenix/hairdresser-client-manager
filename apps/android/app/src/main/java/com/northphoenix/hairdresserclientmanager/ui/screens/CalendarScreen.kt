package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.domain.CalendarWeek
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.AppointmentCard
import com.northphoenix.hairdresserclientmanager.ui.components.CircleIconButton
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.fittedTextStyle
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.temporal.WeekFields

/** Calendar View: Appointments for one day, with a week strip for moving between days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: AppViewModel, onOpenAppointment: (String) -> Unit) {
    val format = LocalTimeFormat.current
    val locale = format.locale
    // The day and what is loaded of it come together, so one day's Appointments are never under another day.
    val shown by viewModel.calendarDay.collectAsStateWithLifecycle()
    val selected = shown.day
    val appointments = shown.appointments
    val load = shown.load
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val weekStart = remember(selected, locale) { selected.with(WeekFields.of(locale).dayOfWeek(), 1) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    // The days of the strip's week that have Appointments. Another week is another read: a day of the same week is not.
    val marked by remember(viewModel, weekStart) { viewModel.calendarWeekMarks(weekStart) }.collectAsStateWithLifecycle(emptySet())
    val hasAppointments = stringResource(R.string.calendar_day_has_appointments)

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // The buttons share a line with the small title only: beside the month they would leave it too little room at a large font size.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Kicker(stringResource(R.string.calendar_title), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        CircleIconButton(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            stringResource(R.string.previous_day),
                            onClick = { viewModel.setCalendarDate(selected.minusDays(1)) },
                        )
                        CircleIconButton(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            stringResource(R.string.next_day),
                            onClick = { viewModel.setCalendarDate(selected.plusDays(1)) },
                        )
                        CircleIconButton(Icons.Rounded.Today, stringResource(R.string.appointment_date_label), onClick = { showDatePicker = true })
                    }
                    Text(format.monthAndYear(selected), style = MaterialTheme.typography.displaySmall)
                }
            }

            item {
                // The week has the row to itself: seven days that are each wide enough to tap take all of it,
                // and on a narrow phone some of the list's side padding as well.
                BoxWithConstraints(Modifier.padding(top = 6.dp).widenedTo(DayCellMinWidth * 7, into = ScreenPadding)) {
                    val days = CalendarWeek.days(weekStart)
                    val weekdays = days.map(format::shortWeekday)
                    val numbers = days.map { it.dayOfMonth.toString() }
                    // All seven in one size, in which the widest of them is whole.
                    val labelWidth = dayLabelWidth(maxWidth)
                    val weekdayStyle = fittedTextStyle(weekdays, MaterialTheme.typography.labelSmall, labelWidth)
                    val numberStyle = fittedTextStyle(numbers, MaterialTheme.typography.titleMedium, labelWidth)

                    Row(Modifier.fillMaxWidth().selectableGroup()) {
                        days.forEachIndexed { index, day ->
                            DayCell(
                                weekday = weekdays[index],
                                weekdayStyle = weekdayStyle,
                                dayOfMonth = numbers[index],
                                numberStyle = numberStyle,
                                description = format.longDay(day) + if (day in marked) ", $hasAppointments" else "",
                                selected = day == selected,
                                marked = day in marked,
                                today = day == today,
                                onClick = { viewModel.setCalendarDate(day) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                ListSectionLabel(
                    if (selected == today) stringResource(R.string.tab_today) + " · " + format.longDay(selected) else format.longDay(selected),
                    appointments.size.takeIf { load == LoadState.Loaded },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            when (val state = load) {
                // Choosing the day again loads it again.
                is LoadState.Failed -> item { LoadFailed(state.message, onRetry = { viewModel.setCalendarDate(selected) }) }
                LoadState.Loaded -> if (appointments.isEmpty()) {
                    item { EmptyState(Icons.Rounded.EventBusy, stringResource(R.string.appointments_empty_title), body = stringResource(R.string.calendar_empty_body)) }
                }
                else -> item { LoadingRow() }
            }

            items(appointments, key = { it.id }) { appointment ->
                AppointmentCard(appointment, onClick = { onOpenAppointment(appointment.id) })
            }
        }
    }

    if (showDatePicker) {
        HcmDatePickerDialog(
            initial = selected,
            today = today,
            onDismiss = { showDatePicker = false },
            onPick = {
                showDatePicker = false
                viewModel.setCalendarDate(it)
            },
        )
    }
}

/** The least a day of the week strip is wide where the screen has the room: the size of a touch target. */
private val DayCellMinWidth = 48.dp

/** The dot under the number of a day that has Appointments. */
private val DayDotSize = 5.dp

/** What is left clear between the drawn cells of two days. Their touch targets meet. */
private val DayCellGap = 4.dp

/**
 * The width a weekday or a day number has in a week strip [stripWidth] wide: a seventh of the strip
 * less the gap, and one more dp for the rounding to whole pixels. The row gives some of its seven
 * days the seventh rounded down and a cell rounds the gap at each of its sides, so the place can be
 * a pixel narrower than the division says, and a text fitted to that pixel is cut.
 */
internal fun dayLabelWidth(stripWidth: Dp): Dp = stripWidth / 7 - DayCellGap - 1.dp

/**
 * How much wider than the list's own [rowWidth] the week strip is laid out, so that its seven days
 * together are [neededWidth] wide: nothing where the row is wide enough, and never more than the
 * list's [sidePadding] on both sides.
 */
internal fun weekStripExtraWidth(rowWidth: Int, neededWidth: Int, sidePadding: Int): Int =
    (neededWidth - rowWidth).coerceIn(0, 2 * sidePadding)

/** Lays a row of the padded list out [width] wide where the list is narrower, reaching up to [into] into the padding on both sides. */
private fun Modifier.widenedTo(width: Dp, into: Dp) = layout { measurable, constraints ->
    val extra = weekStripExtraWidth(constraints.maxWidth, width.roundToPx(), into.roundToPx())
    val widened = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = widened, maxWidth = widened))

    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun DayCell(
    weekday: String,
    weekdayStyle: TextStyle,
    dayOfMonth: String,
    numberStyle: TextStyle,
    description: String,
    selected: Boolean,
    today: Boolean,
    /** Whether the day has an Appointment that is not canceled: a dot under its number. */
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (selected) HcmColors.Surface else MaterialTheme.colorScheme.onSurface
    val shape = MaterialTheme.shapes.medium
    val interactions = remember { MutableInteractionSource() }

    // The whole cell takes the tap, the gap to the next day included; the press shows on the drawn cell.
    Box(
        modifier
            .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = DayCellGap / 2),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) HcmColors.Ink else Color.Transparent)
                .then(if (today && !selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .indication(interactions, LocalIndication.current)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                weekday,
                style = weekdayStyle,
                color = if (selected) content.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(dayOfMonth, style = numberStyle, color = content, maxLines = 1, softWrap = false)
            // The place of the dot is there on every day, so that the strip does not change height when the marks arrive.
            Box(
                Modifier
                    .size(DayDotSize)
                    .clip(CircleShape)
                    .background(
                        when {
                            !marked -> Color.Transparent
                            selected -> content
                            else -> MaterialTheme.colorScheme.primary
                        },
                    ),
            )
        }
    }
}
