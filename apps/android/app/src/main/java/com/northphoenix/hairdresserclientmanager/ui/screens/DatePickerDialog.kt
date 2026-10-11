package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.domain.weeksOf
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.CircleIconButton
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

// The years the Material date picker offered.
private val Years = 1900..2100
private val FirstMonth = YearMonth.of(Years.first, 1)
private val LastMonth = YearMonth.of(Years.last, 12)

private val WeekdayRowHeight = 32.dp
// A day is a 40 dp circle; the row gives it the 48 dp of a touch target.
private val WeekRowHeight = 48.dp

// A month takes up to six weeks. Always that tall, so the dialog keeps its size from month to month.
private val MonthHeight = WeekdayRowHeight + WeekRowHeight * 6

/**
 * Picks a day. The month is drawn by the app itself: the Material date picker marks as today the
 * day it is on the device, and [today] is the day it is in the Stylist's timezone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HcmDatePickerDialog(initial: LocalDate, today: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val format = LocalTimeFormat.current
    var selected by rememberSaveable { mutableStateOf(initial) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(initial).coerceIn(FirstMonth, LastMonth)) }
    var choosingYear by rememberSaveable { mutableStateOf(false) }
    val showMonth = { shown: YearMonth -> month = shown.coerceIn(FirstMonth, LastMonth) }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text(stringResource(R.string.ok), style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            // The arrows are 44 dp circles in touch targets of 48 dp: 4 dp between the targets is 8 dp between the circles.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The month's name opens the list of years, and closes it again.
                TextButton(onClick = { choosingYear = !choosingYear }) {
                    Text(format.monthAndYear(month.atDay(1)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Icon(
                        if (choosingYear) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!choosingYear) {
                    CircleIconButton(
                        Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        stringResource(R.string.previous_month),
                        onClick = { showMonth(month.minusMonths(1)) },
                    )
                    CircleIconButton(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        stringResource(R.string.next_month),
                        onClick = { showMonth(month.plusMonths(1)) },
                    )
                }
            }

            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(MonthHeight)) {
                if (choosingYear) {
                    PickerYears(
                        shown = month.year,
                        thisYear = today.year,
                        onPick = { year ->
                            showMonth(month.withYear(year))
                            choosingYear = false
                        },
                    )
                } else {
                    PickerMonth(month, selected, today, onSelect = { selected = it }, onSwipe = { months -> showMonth(month.plusMonths(months)) })
                }
            }
        }
    }
}

/** One month as a grid of days under the names of the weekdays. A swipe to the side shows the month next to it. */
@Composable
private fun PickerMonth(month: YearMonth, selected: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit, onSwipe: (months: Long) -> Unit) {
    val format = LocalTimeFormat.current
    val weeks = remember(month, format.firstDayOfWeek) { weeksOf(month, format.firstDayOfWeek) }
    val firstWeekday = today.with(TemporalAdjusters.previousOrSame(format.firstDayOfWeek))
    val swipe by rememberUpdatedState(onSwipe)

    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            val farEnough = 48.dp.toPx()
            var dragged = 0f

            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = {
                    if (abs(dragged) > farEnough) {
                        swipe(if (dragged < 0) 1 else -1)
                    }
                },
            ) { _, amount -> dragged += amount }
        },
    ) {
        Row(Modifier.fillMaxWidth().height(WeekdayRowHeight), verticalAlignment = Alignment.CenterVertically) {
            (0L..6L).forEach { offset ->
                Text(
                    format.shortWeekday(firstWeekday.plusDays(offset)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }

        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().height(WeekRowHeight), verticalAlignment = Alignment.CenterVertically) {
                week.forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            PickerDay(day, selected = day == selected, today = day == today, onClick = { onSelect(day) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerDay(day: LocalDate, selected: Boolean, today: Boolean, onClick: () -> Unit) {
    val date = LocalTimeFormat.current.fullDate(day)
    val description = if (today) stringResource(R.string.date_picker_today, date) else date

    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
        shape = CircleShape,
        color = if (selected) HcmColors.Ink else Color.Transparent,
        border = if (today && !selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                day.dayOfMonth.toString(),
                // The description above already names the day.
                modifier = Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    selected -> HcmColors.Surface
                    today -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** The years to jump to, opened around the one that is shown. */
@Composable
private fun PickerYears(shown: Int, thisYear: Int, onPick: (Int) -> Unit) {
    val columns = 3
    // Two rows of earlier years above the row of the shown one.
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (shown - Years.first - 2 * columns).coerceAtLeast(0))

    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(Years.count(), key = { Years.first + it }) { index ->
            val year = Years.first + index
            val selected = year == shown

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    selected = selected,
                    onClick = { onPick(year) },
                    shape = CircleShape,
                    color = if (selected) HcmColors.Ink else Color.Transparent,
                    border = if (year == thisYear && !selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Text(
                        year.toString(),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            selected -> HcmColors.Surface
                            year == thisYear -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
