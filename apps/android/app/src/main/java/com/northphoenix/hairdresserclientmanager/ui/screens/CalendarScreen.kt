package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.domain.CalendarPages
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
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Calendar View: Appointments for one day, under a calendar that is swiped through. It shows the
 * week of the selected day, which is swiped sideways to the weeks around it and pulled down into
 * its month; the month is swiped sideways to the months around it and pushed up into the week again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: AppViewModel, onOpenAppointment: (String) -> Unit) {
    val format = LocalTimeFormat.current
    // The day and what is loaded of it come together, so one day's Appointments are never under another day.
    val shown by viewModel.calendarDay.collectAsStateWithLifecycle()
    val appointments = shown.appointments
    val load = shown.load
    // The calendar shows the day asked for last at once: a swipe that has come to rest selects its day with no wait for the list.
    val selected by viewModel.calendarDate.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // The buttons share a line with the small title only: beside the month they would leave it too little room at a large font size.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Kicker(stringResource(R.string.calendar_title), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        CircleIconButton(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            stringResource(R.string.previous_day),
                            onClick = { viewModel.setCalendarDate(viewModel.calendarDate.value.minusDays(1)) },
                        )
                        CircleIconButton(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            stringResource(R.string.next_day),
                            onClick = { viewModel.setCalendarDate(viewModel.calendarDate.value.plusDays(1)) },
                        )
                    }
                    MonthTitle(YearMonth.from(selected))
                }
            }

            item(key = "calendar") {
                SwipeCalendar(
                    selected = selected,
                    today = today,
                    latest = { viewModel.calendarDate.value },
                    onSelect = viewModel::setCalendarDate,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            item {
                ListSectionLabel(
                    if (shown.day == today) stringResource(R.string.tab_today) + " · " + format.longDay(shown.day) else format.longDay(shown.day),
                    appointments.size.takeIf { load == LoadState.Loaded },
                )
            }

            when (val state = load) {
                // Choosing the day again loads it again.
                is LoadState.Failed -> item { LoadFailed(state.message, onRetry = { viewModel.setCalendarDate(shown.day) }) }
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
}

/** The month and year of the selected day. Another month slides in from the side it was swiped from. */
@Composable
private fun MonthTitle(month: YearMonth) {
    val format = LocalTimeFormat.current

    AnimatedContent(
        targetState = month,
        transitionSpec = {
            val later = targetState > initialState
            (slideInHorizontally { width -> if (later) width / 4 else -width / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> if (later) -width / 4 else width / 4 } + fadeOut())
        },
        label = "month title",
    ) { shown ->
        Text(format.monthAndYear(shown.atDay(1)), style = MaterialTheme.typography.displaySmall)
    }
}

/**
 * The calendar above the day's list. It shows the week of [selected] and is swiped sideways to
 * the weeks around it. Pulled down, the week opens into its month: the weeks before it come in
 * from above and those after it from below. The month is swiped sideways to the months around it,
 * and pushed up it closes into the week of the selected day. Every move follows the finger and is
 * finished by an animation from where the finger let go.
 *
 * A swipe that comes to rest on another week or month selects a day there ([CalendarPages]).
 * [latest] is the day asked for last, which a swipe goes on from even before [selected] has caught up with it.
 */
@Composable
private fun SwipeCalendar(selected: LocalDate, today: LocalDate, latest: () -> LocalDate, onSelect: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val format = LocalTimeFormat.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val firstDayOfWeek = format.firstDayOfWeek
    val pages = remember(firstDayOfWeek) { CalendarPages(firstDayOfWeek) }
    val latestDay by rememberUpdatedState(latest)
    val select by rememberUpdatedState(onSelect)

    // Where the month view is going: kept, so the Calendar comes back the way it was left.
    var monthShown by rememberSaveable { mutableStateOf(false) }
    // How far the month is open: 0 is the week, 1 the whole month, and between them while it is pulled or animated.
    var opened by remember { mutableFloatStateOf(if (monthShown) 1f else 0f) }
    var pulling by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val opening by remember { derivedStateOf { opened > 0f } }
    val atRest by remember { derivedStateOf { !pulling && (opened == 0f || opened == 1f) } }
    // Weeks while the week is shown, months as soon as it opens. The month's view of the selected week is the week itself, so
    // the change from one to the other does not show. The pages, their effects and their layout all take it from this composition.
    val inMonth = opening
    // Keyed by plain values: the keys name the pager's saved state, which is found again only under the same name.
    val pager = key(inMonth, firstDayOfWeek.value) {
        rememberPagerState(initialPage = if (inMonth) pages.monthPage(selected) else pages.weekPage(selected)) {
            if (inMonth) pages.monthCount else pages.weekCount
        }
    }
    val currentPager by rememberUpdatedState(pager)
    // Whether a finger has moved the pager since it was last at rest: where it comes to rest then is the Stylist's choice.
    var swiped by remember(pager) { mutableStateOf(false) }

    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) swiped = true }
    }
    // A swipe that has come to rest selects the day on the page it has brought into view, also when it came back to the page it
    // started on while the arrows had moved the selected day to the next one.
    LaunchedEffect(pager, inMonth) {
        snapshotFlow { pager.isScrollInProgress }.collect { moving ->
            if (!moving && swiped) {
                swiped = false
                val now = latestDay()
                val day = if (inMonth) pages.dayOnMonthPage(now, pager.currentPage) else pages.dayOnWeekPage(now, pager.currentPage)

                if (day != now) {
                    select(day)
                }
            }
        }
    }
    // A day selected otherwise, with the arrows, a tap on a day of the month next to this one, or by the clock, brings its
    // page into view: the page next to this one slides in, one further away is there at once. A day selected again before the
    // page is in place moves it on from where it is. A finger on the pager has the last word.
    val selectedPage = if (inMonth) pages.monthPage(selected) else pages.weekPage(selected)
    LaunchedEffect(pager, selectedPage) {
        if (!swiped && (pager.currentPage != selectedPage || pager.currentPageOffsetFraction != 0f)) {
            if (abs(selectedPage - pager.currentPage) <= 1) pager.animateScrollToPage(selectedPage) else pager.scrollToPage(selectedPage)
        }
    }
    // The days are marked from the months of the pages in view, of the pages on both sides and of the month the week opens into.
    val marked = markedDays(remember(pages, inMonth, pager.currentPage, selected) { pages.monthsToMark(pager.currentPage, inMonth, selected) })

    BoxWithConstraints(modifier.widenedTo(DayCellMinWidth * 7, into = ScreenPadding)) {
        val weekdays = remember(pages, format) { CalendarWeek.days(pages.weekStart(today)).map(format::shortWeekday) }
        // All seven in one size, in which the widest of them is whole.
        val labelWidth = dayLabelWidth(maxWidth)
        val weekdayStyle = fittedTextStyle(weekdays, MaterialTheme.typography.labelSmall, labelWidth)
        val numberStyle = fittedTextStyle(DayNumbers, MaterialTheme.typography.titleMedium, labelWidth)
        val rowHeight = dayRowHeight(numberStyle)
        val rowPx = with(density) { rowHeight.toPx() }
        // How far a pull goes: from the selected week alone to every week of its month.
        val travel by rememberUpdatedState((pages.weeksIn(YearMonth.from(selected)) - 1) * rowPx)

        fun settle(toMonth: Boolean, velocity: Float = 0f) {
            monthShown = toMonth
            settling?.cancel()
            settling = scope.launch {
                animate(opened, if (toMonth) 1f else 0f, velocity / travel, OpenSpring) { value, _ -> opened = value }
            }
        }

        Column(
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A month that is opening or closing is caught by the finger where it is.
                    val caught = !atRest
                    if (caught) {
                        settling?.cancel()
                    } else if (currentPager.isScrollInProgress) {
                        // While a week or a month slides in, it is not pulled open or closed under it.
                        return@awaitEachGesture
                    }
                    // Only a pull that has somewhere to go is the calendar's: up on the week and down on the open month, the list
                    // scrolls and is pulled to refresh as before.
                    val start = if (caught) {
                        down
                    } else {
                        awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            if ((over > 0f && opened < 1f) || (over < 0f && opened > 0f)) {
                                change.consume()
                                opened = (opened + over / travel).coerceIn(0f, 1f)
                            }
                        }
                    } ?: return@awaitEachGesture
                    val velocity = VelocityTracker().apply { addPointerInputChange(start) }

                    pulling = true
                    try {
                        verticalDrag(start.id) { change ->
                            velocity.addPointerInputChange(change)
                            opened = (opened + change.positionChange().y / travel).coerceIn(0f, 1f)
                            change.consume()
                        }
                    } finally {
                        pulling = false
                        val speed = velocity.calculateVelocity().y
                        val fast = OpenVelocity.toPx()

                        settle(
                            toMonth = when {
                                speed > fast -> true
                                speed < -fast -> false
                                else -> opened > 0.5f
                            },
                            velocity = speed,
                        )
                    }
                }
            },
        ) {
            // The weekdays are named once, above every week. Each day says its own weekday to TalkBack.
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).clearAndSetSemantics {}) {
                weekdays.forEach { weekday ->
                    Text(
                        weekday,
                        modifier = Modifier.weight(1f),
                        style = weekdayStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))

                        if (!inMonth) {
                            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        // Between two months of different lengths, the height follows the swipe from the one to the other.
                        val swipedBy = pager.currentPageOffsetFraction
                        val next = (pager.currentPage + sign(swipedBy).toInt()).coerceIn(0, pages.monthCount - 1)
                        val weeks = lerp(pages.weeksIn(pages.monthOf(pager.currentPage)).toFloat(), pages.weeksIn(pages.monthOf(next)).toFloat(), abs(swipedBy))
                        val height = lerp(rowPx, weeks * rowPx, opened)
                        // The selected week stays under the finger: the weeks before it come in from above as the month opens.
                        val offset = -pages.weekOfMonth(selected) * rowPx * (1f - opened)

                        layout(placeable.width, height.roundToInt()) { placeable.place(0, offset.roundToInt()) }
                    },
            ) {
                if (inMonth) {
                    MonthPager(pager, pages, selected, today, marked, rowHeight, numberStyle, atRest, opened = { opened }, onSelect = select)
                } else {
                    WeekPager(pager, pages, selected, today, marked, rowHeight, numberStyle, atRest, onSelect = select)
                }
            }

            CalendarHandle(
                monthShown = monthShown,
                onClick = {
                    if (!pager.isScrollInProgress) {
                        settle(toMonth = !monthShown)
                    }
                },
            )
        }
    }
}

@Composable
private fun WeekPager(
    pager: PagerState,
    pages: CalendarPages,
    selected: LocalDate,
    today: LocalDate,
    marked: Set<LocalDate>,
    rowHeight: Dp,
    numberStyle: TextStyle,
    atRest: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    // The weeks on both sides are there before they are swiped to.
    HorizontalPager(pager, beyondViewportPageCount = 1, userScrollEnabled = atRest) { page ->
        val days = remember(pages, page) { CalendarWeek.days(pages.weekOf(page)) }

        WeekRow(days, pages.dayOnWeekPage(selected, page), today, marked, rowHeight, numberStyle, onSelect)
    }
}

@Composable
private fun MonthPager(
    pager: PagerState,
    pages: CalendarPages,
    selected: LocalDate,
    today: LocalDate,
    marked: Set<LocalDate>,
    rowHeight: Dp,
    numberStyle: TextStyle,
    atRest: Boolean,
    opened: () -> Float,
    onSelect: (LocalDate) -> Unit,
) {
    HorizontalPager(pager, beyondViewportPageCount = 1, userScrollEnabled = atRest, verticalAlignment = Alignment.Top) { page ->
        val month = pages.monthOf(page)
        val weeks = remember(pages, month) { pages.monthGrid(month) }
        val ownMonth = YearMonth.from(selected) == month
        // A day of the month next to this one that was tapped here is selected here too, while its own month slides in.
        val highlighted = if (ownMonth || (page == pager.settledPage && weeks.any { selected in it })) selected else pages.dayOnMonthPage(selected, page)
        val selectedWeek = if (ownMonth) pages.weekOfMonth(selected) else -1

        // As tall as the longest month, so that every page measures the same; the calendar shows as much of it as the month has weeks.
        Column(Modifier.height(rowHeight * MaxWeeks)) {
            weeks.forEachIndexed { index, week ->
                WeekRow(
                    week,
                    highlighted,
                    today,
                    marked,
                    rowHeight,
                    numberStyle,
                    onSelect,
                    // The other weeks appear as the month opens; the selected one is there all along.
                    modifier = if (index == selectedWeek) Modifier else Modifier.graphicsLayer { alpha = opened() },
                    // The days of the months before and after are faint in the month, and as they are in the week while it is a week.
                    faint = { day -> YearMonth.from(day) != month },
                    faintness = { 1f - (1f - FaintAlpha) * opened() },
                )
            }
        }
    }
}

@Composable
private fun WeekRow(
    days: List<LocalDate>,
    highlighted: LocalDate,
    today: LocalDate,
    marked: Set<LocalDate>,
    height: Dp,
    numberStyle: TextStyle,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    faint: (LocalDate) -> Boolean = { false },
    faintness: () -> Float = { 1f },
) {
    val format = LocalTimeFormat.current
    val hasAppointments = stringResource(R.string.calendar_day_has_appointments)

    Row(modifier.fillMaxWidth().height(height).selectableGroup()) {
        days.forEach { day ->
            DayCell(
                dayOfMonth = day.dayOfMonth.toString(),
                numberStyle = numberStyle,
                description = format.longDay(day) + if (day in marked) ", $hasAppointments" else "",
                selected = day == highlighted,
                today = day == today,
                marked = day in marked,
                onClick = { onSelect(day) },
                modifier = Modifier.weight(1f).then(if (faint(day)) Modifier.graphicsLayer { alpha = faintness() } else Modifier),
            )
        }
    }
}

/**
 * The days of [months] that have an Appointment that is not canceled ([monthAppointmentsState]), read
 * once for every page of the calendar: the week and its month mark a day alike, and a month read
 * before is marked from the first frame. A month that stays in [months] is not read again.
 */
@Composable
private fun markedDays(months: List<YearMonth>): Set<LocalDate> {
    val zone = LocalTimeFormat.current.zone
    val reads = months.map { month -> key(month) { monthAppointmentsState(month) } }
    val appointments = reads.flatMap { it.value.data.orEmpty() }

    return remember(appointments, zone) { CalendarWeek.daysWithAppointments(appointments, zone) }
}

/**
 * The handle under the calendar: it shows that the week pulls down into the month. A tap opens or
 * closes the month as a pull does, for whoever does not swipe (TalkBack names what it does).
 */
@Composable
private fun CalendarHandle(monthShown: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (monthShown) R.string.calendar_show_week else R.string.calendar_show_month)

    Box(
        Modifier
            .fillMaxWidth()
            .height(HandleHeight)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
    }
}

/** The least a day of the calendar is wide where the screen has the room, and the least a week is tall: the size of a touch target. */
private val DayCellMinWidth = 48.dp

/** The dot under the number of a day that has Appointments. */
private val DayDotSize = 5.dp

/** What is left clear between the drawn cells of two days. Their touch targets meet. */
private val DayCellGap = 4.dp

/** Above the number of a day and below its dot, inside its cell. */
private val DayCellPadding = 8.dp

private val DayDotGap = 4.dp

private val HandleHeight = 28.dp

/** The most weeks a month view has. */
private const val MaxWeeks = 6

/** How faint the days of the months before and after are in the month view. */
private const val FaintAlpha = 0.38f

/** A pull let go at this speed or faster goes on in its direction, however far it got. */
private val OpenVelocity = 400.dp

private val OpenSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The day numbers a calendar shows, to fit them all in one size. */
private val DayNumbers = (1..31).map(Int::toString)

/** The height of a week of the calendar for days numbered in [numberStyle]: the number and its dot, padded, and never less than a touch target. */
@Composable
private fun dayRowHeight(numberStyle: TextStyle): Dp {
    val number = with(LocalDensity.current) {
        (if (numberStyle.lineHeight.isSp) numberStyle.lineHeight else numberStyle.fontSize * 1.4f).toDp()
    }

    return maxOf(DayCellMinWidth, DayCellGap + DayCellPadding * 2 + number + DayDotGap + DayDotSize)
}

/**
 * The width a weekday or a day number has in a calendar [stripWidth] wide: a seventh of the strip
 * less the gap, and one more dp for the rounding to whole pixels. The row gives some of its seven
 * days the seventh rounded down and a cell rounds the gap at each of its sides, so the place can be
 * a pixel narrower than the division says, and a text fitted to that pixel is cut.
 */
internal fun dayLabelWidth(stripWidth: Dp): Dp = stripWidth / 7 - DayCellGap - 1.dp

/**
 * How much wider than the list's own [rowWidth] the calendar is laid out, so that its seven days
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
            .fillMaxHeight()
            .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(DayCellGap / 2),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (selected) HcmColors.Ink else Color.Transparent)
                .then(if (today && !selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .indication(interactions, LocalIndication.current),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(dayOfMonth, style = numberStyle, color = content, maxLines = 1, softWrap = false)
            Spacer(Modifier.height(DayDotGap))
            // The place of the dot is there on every day, so that a week does not change height when the marks arrive.
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
