package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import com.northphoenix.hairdresserclientmanager.data.EarningsTrend
import com.northphoenix.hairdresserclientmanager.domain.Earnings
import com.northphoenix.hairdresserclientmanager.domain.EarningsPeriod
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.state.TrendRead
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
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

/**
 * Итоги: what a week, month or year came to. Period chips and arrows pick the period; the numbers
 * are added up by the server (`stats.summary`) from completed Appointments: the price of their
 * Services (a custom total replaces the sum) and their tips. Scheduled ones whose time has passed
 * are shown apart, and lead to the Today tab, where they are marked: [onOpenToday]. Above them,
 * bars show what the last few periods earned side by side (`stats.trend`); a tap on one picks it.
 */
@Composable
fun EarningsScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenToday: () -> Unit) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(EarningsPeriod.MONTH) }
    // Any day of the period shown: changing the period keeps the day, so Месяц after a week is that week's month.
    var anchor by rememberSaveable { mutableStateOf(today.toString()) }
    val date = LocalDate.parse(anchor)
    val read by rememberEarnings(viewModel, period, date)
    // The newest period of the bars, kept while the selected period is among them (Earnings.trendNewest).
    var shownNewest by rememberSaveable(period) { mutableStateOf<String?>(null) }
    val newest = Earnings.trendNewest(period, date, today, shownNewest?.let(LocalDate::parse))
    SideEffect { shownNewest = newest.toString() }
    val trend = rememberTrend(viewModel, period, newest)
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
            val selected = Earnings.span(period, date).first
            val bars = trend.read.data?.takeIf { Earnings.showsTrend(it, selected.takeIf { trend.current }) }
            // The summary waits a moment for bars still on their way, which come above it, so it is not pushed down
            // once shown; not longer, since it does without them.
            val trendSettled = trend.read.data != null || trend.read.state !is LoadState.Loading
            var barsLate by remember(period, newest) { mutableStateOf(false) }
            LaunchedEffect(period, newest, summary != null && !trendSettled) {
                if (summary != null && !trendSettled) {
                    delay(BARS_WAIT_MS)
                    barsLate = true
                }
            }

            if (bars != null) {
                TrendCard(
                    bars,
                    period,
                    selected,
                    today,
                    locale,
                    onSelect = { first -> Earnings.anchorForBar(period, first, date, today)?.let { anchor = it.toString() } },
                )
            }

            when {
                summary != null && (trendSettled || barsLate) -> Summary(summary, period, onOpenToday)
                read.state is LoadState.Failed ->
                    LoadFailed(
                        (read.state as LoadState.Failed).message,
                        onRetry = {
                            viewModel.loadEarnings(period, date)
                            viewModel.loadEarningsTrend(period, newest)
                        },
                    )
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

/**
 * The bars ending with the period that holds [newest], as the cache has them and each time they are
 * refreshed. When they move to other periods of the same length, the ones shown stay until those
 * are read ([TrendRead.then]); bars of another length never do.
 */
@Composable
private fun rememberTrend(viewModel: AppViewModel, period: EarningsPeriod, newest: LocalDate): TrendRead {
    val first = Earnings.span(period, newest).first
    // Keyed, so the first frame of other bars starts from their own cached answer, not the last bars' one.
    val read = key(viewModel, period, first) {
        remember { viewModel.earningsTrend(period, first) }.collectWhileOpen(
            initialValue = remember { viewModel.shownEarningsTrend(period, first) },
        ).value
    }
    val last = remember(period) { arrayOfNulls<TrendRead>(1) }
    val shown = last[0]?.then(read) ?: TrendRead(read)

    SideEffect { last[0] = shown }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.loadEarningsTrend(period, first) }

    return shown
}

/**
 * What each period of [trend] earned, as bars side by side with the amount on each, the period
 * [selected] (its first day) marked. Tapping a bar selects its period: [onSelect] gets its first day.
 */
@Composable
private fun TrendCard(
    trend: EarningsTrend,
    period: EarningsPeriod,
    selected: LocalDate,
    today: LocalDate,
    locale: Locale,
    onSelect: (LocalDate) -> Unit,
) {
    val fractions = remember(trend) { Earnings.trendFractions(trend.periods) }
    val firsts = remember(trend) { trend.periods.map { LocalDate.parse(it.firstDay) } }
    val labels = remember(trend, period, locale) { Earnings.barLabels(period, firsts, locale) }
    val primary = MaterialTheme.colorScheme.primary
    val small = MaterialTheme.typography.bodySmall
    // One line of the labels, however small one is set to fit: the bars stand on one line.
    val labelHeight = with(LocalDensity.current) { small.lineHeight.toDp() }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Kicker(
                stringResource(
                    when (period) {
                        EarningsPeriod.WEEK -> R.string.earnings_trend_week
                        EarningsPeriod.MONTH -> R.string.earnings_trend_month
                        EarningsPeriod.YEAR -> R.string.earnings_trend_year
                    },
                ),
            )

            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
                trend.periods.forEachIndexed { index, bar ->
                    val first = firsts[index]
                    val isSelected = first == selected
                    // Whole dollars: the amounts sit side by side, and the summary below has the cents.
                    val amount = Money.formatShort((bar.earnedCents + 50) / 100 * 100)
                    val description = stringResource(R.string.earnings_trend_bar, Earnings.title(period, first, today, locale), amount)
                    val ink = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    val weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal

                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(first) })
                            // Read as one: "12–18 окт.: $120", not the amount and the label again.
                            .clearAndSetSemantics { contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        BasicText(
                            amount,
                            style = small.copy(color = ink, fontWeight = weight, textAlign = TextAlign.Center),
                            maxLines = 1,
                            softWrap = false,
                            // Smaller rather than cut: a cut amount would read as another number. What still does not fit ends in «…».
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = small.fontSize),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .width(TREND_BAR_WIDTH)
                                .height(TREND_BAR_HEIGHT * fractions[index])
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (isSelected) primary else primary.copy(alpha = 0.3f)),
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(labelHeight), contentAlignment = Alignment.Center) {
                            BasicText(
                                labels[index],
                                style = small.copy(color = ink, fontWeight = weight, textAlign = TextAlign.Center),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = small.fontSize),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How long a summary that is there waits for bars still on their way. */
private const val BARS_WAIT_MS = 700L

private val TREND_BAR_HEIGHT = 96.dp
private val TREND_BAR_WIDTH = 28.dp

@Composable
private fun Summary(summary: EarningsSummary, period: EarningsPeriod, onOpenToday: () -> Unit) {
    if (Earnings.isEmpty(summary)) {
        EmptyState(
            Icons.Rounded.Insights,
            stringResource(R.string.earnings_empty_title),
            body = stringResource(R.string.earnings_empty_body),
        )
        return
    }

    EarnedCard(summary)
    summary.unresolved?.takeIf { it.count > 0 }?.let { UnresolvedCard(it, onOpenToday) }
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

/**
 * Scheduled Appointments of the period whose start time has passed: what they come to is not in
 * Earned until they are marked, which the Today tab does in one tap, so the card opens it.
 */
@Composable
private fun UnresolvedCard(unresolved: EarningsSummary.Unresolved, onOpenToday: () -> Unit) {
    HcmCard(onClick = onOpenToday) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.earnings_unresolved), style = MaterialTheme.typography.bodyMedium, color = HcmColors.Warning)
                Text(
                    Money.formatShort(unresolved.totalCents) + " · " +
                        pluralStringResource(R.plurals.earnings_booked_count, unresolved.count, unresolved.count),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(stringResource(R.string.earnings_unresolved_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
