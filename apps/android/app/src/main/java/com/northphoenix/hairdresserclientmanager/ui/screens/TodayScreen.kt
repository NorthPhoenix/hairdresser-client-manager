package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.domain.HomeOutcomes
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.AppointmentCard
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenTitle
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Home View: today's Appointments plus past ones still marked scheduled, which need an outcome.
 * The card of a Scheduled Appointment whose start time has come offers its outcomes, and a day
 * with several unresolved Appointments can complete them all at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(viewModel: AppViewModel, onOpenAppointment: (String) -> Unit) {
    val format = LocalTimeFormat.current
    val home by viewModel.home.collectAsStateWithLifecycle()
    val appointments = home.data.orEmpty()
    val load = home.state
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    // The day the list was loaded for: the view model moves both on together when the day ends.
    val today by viewModel.today.collectAsStateWithLifecycle()
    // A card of today offers its outcomes from its start time on.
    val now by rememberNow()
    val (unresolved, todays) = remember(appointments, today, format) {
        appointments.partition {
            it.status == AppointmentStatus.SCHEDULED && format.zoned(it.startsAt).toLocalDate().isBefore(today)
        }
    }
    val unresolvedDays = remember(unresolved, format) { HomeOutcomes.byDay(unresolved, format.zone) }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenTitle(
                    title = stringResource(R.string.home_appointments_title),
                    kicker = format.longDay(today),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            when (val state = load) {
                is LoadState.Failed -> item { LoadFailed(state.message, onRetry = viewModel::retryLoad) }
                LoadState.Loaded -> if (appointments.isEmpty()) {
                    item {
                        EmptyState(Icons.Rounded.EventAvailable, stringResource(R.string.appointments_empty_title), body = stringResource(R.string.home_empty_body))
                    }
                }
                else -> item { LoadingRow() }
            }

            if (unresolved.isNotEmpty()) {
                item(key = "unresolved-section") { ListSectionLabel(stringResource(R.string.home_unresolved_section), unresolved.size, warning = true) }
                unresolvedDays.forEach { day ->
                    if (day.closesAll) {
                        item(key = "close-day-${day.day}") {
                            CloseDayRow(
                                day = format.shortWeekday(day.day) + ", " + format.shortDate(day.day),
                                count = day.appointments.size,
                                onCompleteAll = { viewModel.completeAllFromHome(day.appointments) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    items(day.appointments, key = { "unresolved-${it.id}" }) { appointment ->
                        AppointmentCard(
                            appointment,
                            onClick = { onOpenAppointment(appointment.id) },
                            showDate = true,
                            onOutcome = { viewModel.setOutcomeFromHome(appointment, it) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            if (todays.isNotEmpty()) {
                item(key = "today-section") { ListSectionLabel(stringResource(R.string.home_today_section), todays.size) }
                items(todays, key = { "today-${it.id}" }) { appointment ->
                    AppointmentCard(
                        appointment,
                        onClick = { onOpenAppointment(appointment.id) },
                        onOutcome = if (HomeOutcomes.needsOutcome(appointment, now)) {
                            { viewModel.setOutcomeFromHome(appointment, it) }
                        } else {
                            null
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * The time, looked at again at the start of every minute, as Appointments start on the minute,
 * and each time the app comes back into view.
 */
@Composable
private fun rememberNow(): State<Instant> {
    val now = remember { mutableStateOf(Instant.now()) }

    LifecycleResumeEffect(Unit) {
        now.value = Instant.now()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            // A moment after the minute has turned, so that the look does not land just before it.
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now.value = Instant.now()
        }
    }

    return now
}

/** "Fri, Oct 16 — 3 appointments not closed" with a button that completes all of them. [day] is the day as the row names it. */
@Composable
private fun CloseDayRow(day: String, count: Int, onCompleteAll: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = HcmColors.WarningSoft) {
        Row(
            Modifier.padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val text = stringResource(R.string.home_day_not_closed, day, pluralStringResource(R.plurals.home_day_not_closed_count, count, count))
            val dayAt = text.indexOf(day)

            Text(
                buildAnnotatedString {
                    append(text)
                    // The day stands out, wherever the language puts it.
                    if (dayAt >= 0) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), dayAt, dayAt + day.length)
                    }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = HcmColors.Warning,
            )
            Button(
                onClick = onCompleteAll,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = HcmColors.Ink, contentColor = HcmColors.Surface),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_complete_all), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * [count] is null while the list is not loaded: a number, even 0, would say that it is. The count
 * keeps its place beside a [title] of any length, which goes on as many lines as it takes.
 */
@Composable
fun ListSectionLabel(title: String, count: Int?, modifier: Modifier = Modifier, warning: Boolean = false) {
    Row(
        modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall)
        if (count != null) {
            Tag(
                count.toString(),
                content = if (warning) HcmColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
                container = if (warning) HcmColors.WarningSoft else MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.5.dp)
    }
}
