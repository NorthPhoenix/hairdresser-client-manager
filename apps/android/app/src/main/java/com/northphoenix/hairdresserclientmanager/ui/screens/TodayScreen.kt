package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.AppointmentCard
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenTitle
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors

/** Home View: today's Appointments plus past ones still marked scheduled, which need an outcome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(viewModel: AppViewModel, onOpenAppointment: (String, String) -> Unit) {
    val format = LocalTimeFormat.current
    val appointments by viewModel.home.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val today = format.today()
    val (unresolved, todays) = remember(appointments, today) {
        appointments.partition {
            it.status == AppointmentStatus.SCHEDULED && format.zoned(it.startsAt).toLocalDate().isBefore(today)
        }
    }

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

            if (!loaded) {
                item { LoadingRow() }
            } else if (appointments.isEmpty()) {
                item {
                    EmptyState(Icons.Rounded.EventAvailable, stringResource(R.string.appointments_empty_title), body = stringResource(R.string.home_empty_body))
                }
            }

            if (unresolved.isNotEmpty()) {
                item { ListSectionLabel(stringResource(R.string.home_unresolved_section), unresolved.size, warning = true) }
                items(unresolved, key = { "unresolved-${it.id}" }) { appointment ->
                    AppointmentCard(appointment, onClick = { onOpenAppointment(appointment.id, appointment.startsAt) }, showDate = true)
                }
            }

            if (todays.isNotEmpty()) {
                item { ListSectionLabel(stringResource(R.string.home_today_section), todays.size) }
                items(todays, key = { "today-${it.id}" }) { appointment ->
                    AppointmentCard(appointment, onClick = { onOpenAppointment(appointment.id, appointment.startsAt) })
                }
            }
        }
    }
}

@Composable
fun ListSectionLabel(title: String, count: Int, modifier: Modifier = Modifier, warning: Boolean = false) {
    Row(
        modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Tag(
            count.toString(),
            content = if (warning) HcmColors.Warning else MaterialTheme.colorScheme.onSurfaceVariant,
            container = if (warning) HcmColors.WarningSoft else MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.5.dp)
    }
}
