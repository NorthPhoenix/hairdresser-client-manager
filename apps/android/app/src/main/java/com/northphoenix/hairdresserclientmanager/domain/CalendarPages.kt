package com.northphoenix.hairdresserclientmanager.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The pages the Calendar View swipes through: weeks while it shows a week, months while it shows
 * the month. A page is numbered from the first week or month of [FIRST_MONTH] on, up to the last
 * of [LAST_MONTH], the years the date picker offers.
 *
 * Swiping to another page selects a day on it: the same weekday of another week, the same day of
 * another month (or its last day, for a month that is shorter). The day the Stylist has selected
 * stays selected on its own page, so swiping back and forth does not move it.
 */
class CalendarPages(val firstDayOfWeek: DayOfWeek) {
    private val firstWeek = FIRST_MONTH.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    val weekCount: Int = ChronoUnit.WEEKS.between(firstWeek, weekStart(LAST_MONTH.atEndOfMonth())).toInt() + 1

    val monthCount: Int = ChronoUnit.MONTHS.between(FIRST_MONTH, LAST_MONTH).toInt() + 1

    /** The first day of the week [day] is in. */
    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    /** The page of the week [day] is in. A day outside the pages is on the first or the last one. */
    fun weekPage(day: LocalDate): Int = ChronoUnit.WEEKS.between(firstWeek, weekStart(day)).toInt().coerceIn(0, weekCount - 1)

    /** The first day of the week on [page]. */
    fun weekOf(page: Int): LocalDate = firstWeek.plusWeeks(page.toLong())

    /** The page of the month [day] is in. */
    fun monthPage(day: LocalDate): Int = ChronoUnit.MONTHS.between(FIRST_MONTH, YearMonth.from(day)).toInt().coerceIn(0, monthCount - 1)

    /** The month on [page]. */
    fun monthOf(page: Int): YearMonth = FIRST_MONTH.plusMonths(page.toLong())

    /** The day that is selected once the week on [page] is swiped to, with [selected] selected now. */
    fun dayOnWeekPage(selected: LocalDate, page: Int): LocalDate = selected.plusWeeks((page - weekPage(selected)).toLong())

    /** The day that is selected once the month on [page] is swiped to, with [selected] selected now. */
    fun dayOnMonthPage(selected: LocalDate, page: Int): LocalDate =
        if (YearMonth.from(selected) == monthOf(page)) selected else selected.plusMonths((page - monthPage(selected)).toLong())

    /**
     * The days of [month] in whole weeks, as the month view shows it: the first week begins with
     * the days of the month before that share it, and the last one ends with those of the month after.
     */
    fun monthGrid(month: YearMonth): List<List<LocalDate>> {
        val first = weekStart(month.atDay(1))

        return (0 until weeksIn(month)).map { week -> CalendarWeek.days(first.plusWeeks(week.toLong())) }
    }

    /** How many weeks the month view of [month] has: four to six. */
    fun weeksIn(month: YearMonth): Int = ChronoUnit.WEEKS.between(weekStart(month.atDay(1)), weekStart(month.atEndOfMonth())).toInt() + 1

    /** Which week of its month's view [day] is in, from 0. */
    fun weekOfMonth(day: LocalDate): Int = ChronoUnit.WEEKS.between(weekStart(YearMonth.from(day).atDay(1)), weekStart(day)).toInt()

    companion object {
        val FIRST_MONTH: YearMonth = YearMonth.of(1900, 1)
        val LAST_MONTH: YearMonth = YearMonth.of(2100, 12)
    }
}
