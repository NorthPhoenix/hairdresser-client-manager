package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import com.northphoenix.hairdresserclientmanager.domain.Earnings
import com.northphoenix.hairdresserclientmanager.domain.EarningsPeriod
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.CircleIconButton
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.SideBySideOrStacked
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.collectWhileOpen
import java.time.LocalDate

/**
 * Итоги: what a week, month or year came to. Period chips and arrows pick the period; the numbers
 * are added up by the server (`stats.summary`) from completed Appointments: the price of their
 * Services (a custom total replaces the sum) and their tips.
 */
@Composable
fun EarningsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(EarningsPeriod.MONTH) }
    // Any day of the period shown: changing the period keeps the day, so Месяц after a week is that week's month.
    var anchor by rememberSaveable { mutableStateOf(today.toString()) }
    val date = LocalDate.parse(anchor)
    val read by rememberEarnings(viewModel, period, date)
    val locale = LocalTimeFormat.current.locale

    SubScreen(title = stringResource(R.string.earnings_title), onBack = onBack) { padding ->
        ScreenColumn(padding) {
            SegmentedControl(
                options = EarningsPeriod.entries,
                selected = period,
                onSelect = { period = it },
                label = {
                    stringResource(
                        when (it) {
                            EarningsPeriod.WEEK -> R.string.earnings_period_week
                            EarningsPeriod.MONTH -> R.string.earnings_period_month
                            EarningsPeriod.YEAR -> R.string.earnings_period_year
                        },
                    )
                },
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleIconButton(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    stringResource(R.string.earnings_previous),
                    onClick = { anchor = Earnings.step(period, date, -1).toString() },
                    enabled = Earnings.hasPrevious(period, date),
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Kicker(stringResource(R.string.tab_profile) + " · " + stringResource(R.string.earnings_title))
                    Text(
                        Earnings.title(period, date, today, locale),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                CircleIconButton(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    stringResource(R.string.earnings_next),
                    onClick = { anchor = Earnings.step(period, date, 1).toString() },
                    enabled = Earnings.hasNext(period, date, today),
                )
            }

            val summary = read.data

            when {
                summary != null -> Summary(summary, period)
                read.state is LoadState.Failed -> LoadFailed((read.state as LoadState.Failed).message, onRetry = { viewModel.loadEarnings(period, date) })
                else -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** The summary of a period, as the cache has it now and each time it is refreshed: asked for again whenever the screen comes into view. */
@Composable
private fun rememberEarnings(viewModel: AppViewModel, period: EarningsPeriod, date: LocalDate): State<Read<EarningsSummary>> {
    val first = Earnings.span(period, date).first
    val read = remember(viewModel, period, first) { viewModel.earnings(period, first) }.collectWhileOpen(
        initialValue = remember(viewModel, period, first) { viewModel.shownEarnings(period, first) },
    )

    // Back from an Appointment or from the background: what it holds may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.loadEarnings(period, first) }

    return read
}

@Composable
private fun Summary(summary: EarningsSummary, period: EarningsPeriod) {
    if (Earnings.isEmpty(summary)) {
        EmptyState(
            Icons.Rounded.Insights,
            stringResource(R.string.earnings_empty_title),
            body = stringResource(R.string.earnings_empty_body),
        )
        return
    }

    EarnedCard(summary)
    summary.upcoming?.let { UpcomingCard(it, period, summary.isCurrent) }
    Outcomes(summary)

    if (summary.topServices.isNotEmpty()) {
        TopServicesCard(summary.topServices)
    }
}

@Composable
private fun EarnedCard(summary: EarningsSummary) {
    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.earnings_earned), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.formatShort(summary.earned.totalCents), style = MaterialTheme.typography.displaySmall)

            if (summary.visits == 0) {
                Text(stringResource(R.string.earnings_no_visits), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    stringResource(R.string.earnings_split, Money.formatShort(summary.earned.servicesCents), Money.formatShort(summary.earned.tipsCents)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (summary.visits > 0) {
            SideBySideOrStacked(Modifier.padding(top = 14.dp)) {
                StatTile(stringResource(R.string.earnings_visits), summary.visits.toString())
                StatTile(stringResource(R.string.earnings_average), Money.formatShort(summary.averageCents))
                StatTile(stringResource(R.string.earnings_at_home), stringResource(R.string.earnings_at_home_value, summary.atHomeVisits, summary.visits))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Scheduled Appointments from now to the end of the period; for a period still to come, all of them. */
@Composable
private fun UpcomingCard(upcoming: EarningsSummary.Upcoming, period: EarningsPeriod, isCurrent: Boolean) {
    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(
                    when {
                        !isCurrent -> R.string.earnings_booked
                        period == EarningsPeriod.WEEK -> R.string.earnings_booked_week
                        period == EarningsPeriod.MONTH -> R.string.earnings_booked_month
                        else -> R.string.earnings_booked_year
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (upcoming.count == 0) {
                    stringResource(R.string.earnings_booked_none)
                } else {
                    Money.formatShort(upcoming.totalCents) + " · " + pluralStringResource(R.plurals.earnings_booked_count, upcoming.count, upcoming.count)
                },
                style = if (upcoming.count == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

/** Canceled and no-show counts, small: only the ones there are. */
@Composable
private fun Outcomes(summary: EarningsSummary) {
    val parts = listOfNotNull(
        summary.canceled.takeIf { it > 0 }?.let { stringResource(R.string.earnings_canceled, it) },
        summary.noShow.takeIf { it > 0 }?.let { stringResource(R.string.earnings_no_show, it) },
    )

    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun TopServicesCard(services: List<EarningsSummary.TopService>) {
    val fractions = remember(services) { Earnings.barFractions(services) }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Kicker(stringResource(R.string.earnings_top_services))

            services.forEachIndexed { index, service ->
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(service.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(Money.formatShort(service.revenueCents), style = MaterialTheme.typography.bodyLarge)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(fractions[index]).height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}
