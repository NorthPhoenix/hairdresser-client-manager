package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import com.northphoenix.hairdresserclientmanager.data.EarningsTrend
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.temporal.TemporalAdjusters

/** The length of the period Итоги adds up. [wire] is what `stats.summary` takes. */
enum class EarningsPeriod(val wire: String) {
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),
}

/**
 * The periods of Итоги on the Stylist's calendar, as the server draws them (`periodBounds` in
 * packages/api): a week starts on Monday, a month and a year are the calendar's. The server does
 * the adding up; this is for the title and for stepping from one period to the next.
 */
object Earnings {
    /** The first and the last day of a period, both included. */
    data class Span(val first: LocalDate, val last: LocalDate) {
        operator fun contains(date: LocalDate) = date >= first && date <= last
    }

    fun span(period: EarningsPeriod, date: LocalDate): Span =
        when (period) {
            EarningsPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { Span(it, it.plusDays(6)) }
            EarningsPeriod.MONTH -> date.withDayOfMonth(1).let { Span(it, it.plusMonths(1).minusDays(1)) }
            EarningsPeriod.YEAR -> date.withDayOfYear(1).let { Span(it, it.plusYears(1).minusDays(1)) }
        }

    /** A day of the period [steps] after this one (before, when negative): its first day. */
    fun step(period: EarningsPeriod, date: LocalDate, steps: Int): LocalDate {
        val first = span(period, date).first

        return when (period) {
            EarningsPeriod.WEEK -> first.plusWeeks(steps.toLong())
            EarningsPeriod.MONTH -> first.plusMonths(steps.toLong())
            EarningsPeriod.YEAR -> first.plusYears(steps.toLong())
        }
    }

    /** The server takes dates from 2000 to 2100. */
    private val earliest = LocalDate.of(2000, 1, 1)

    /** Whether the period before this one can be shown. */
    fun hasPrevious(period: EarningsPeriod, date: LocalDate) = span(period, date).first > earliest

    /**
     * Whether the period after this one can be shown: up to a year past [today], which is further
     * than anything is booked. Stepping into the future is for looking at what is booked.
     */
    fun hasNext(period: EarningsPeriod, date: LocalDate, today: LocalDate) =
        span(period, date).last < today.plusYears(1)

    /**
     * What the period is called: "Октябрь", "19–25 окт.", "2026". A period of another year than
     * [today]'s says the year.
     */
    fun title(period: EarningsPeriod, date: LocalDate, today: LocalDate, locale: Locale): String {
        val span = span(period, date)
        val otherYear = span.last.year != today.year

        return when (period) {
            EarningsPeriod.YEAR -> span.first.year.toString()
            EarningsPeriod.MONTH -> {
                val month = DateTimeFormatter.ofPattern("LLLL", locale).format(span.first).replaceFirstChar { it.titlecase(locale) }

                if (otherYear) "$month ${span.first.year}" else month
            }
            EarningsPeriod.WEEK -> {
                val inRussian = locale.language == "ru"
                val dayAndMonth = DateTimeFormatter.ofPattern(if (inRussian) "d MMM" else "MMM d", locale)
                val range =
                    when {
                        // Over New Year: each end says its year.
                        span.first.year != span.last.year ->
                            return "${dayAndMonth.format(span.first)} ${span.first.year} – ${dayAndMonth.format(span.last)} ${span.last.year}"
                        span.first.month == span.last.month ->
                            if (inRussian) {
                                "${span.first.dayOfMonth}–${dayAndMonth.format(span.last)}"
                            } else {
                                "${dayAndMonth.format(span.first)}–${span.last.dayOfMonth}"
                            }
                        else -> "${dayAndMonth.format(span.first)} – ${dayAndMonth.format(span.last)}"
                    }

                if (otherYear) "$range ${span.last.year}" else range
            }
        }
    }

    /**
     * The widths of the bars of the top services, 0 to 1: the biggest takes the whole width, the
     * others their share of it, and a small one is still a bar that can be seen.
     */
    fun barFractions(services: List<EarningsSummary.TopService>): List<Float> {
        val biggest = services.maxOfOrNull { it.revenueCents }?.takeIf { it > 0 } ?: return services.map { MIN_BAR }

        return services.map { (it.revenueCents.toFloat() / biggest).coerceIn(MIN_BAR, 1f) }
    }

    private const val MIN_BAR = 0.04f

    /** How many periods the bars of Итоги show side by side, the selected one among them. */
    const val TREND_PERIODS = 6

    /**
     * The first day of the newest period the bars show while [date]'s period is selected. They
     * stay where they were ([shownNewest], the newest they showed) while the selected period is
     * among them, and move just far enough to take it in when it is not, so an arrow past their
     * end moves them by one. Shown first, they end with [today]'s period. They never reach past
     * both the selected period and today's, since periods to come have earned nothing yet.
     */
    fun trendNewest(period: EarningsPeriod, date: LocalDate, today: LocalDate, shownNewest: LocalDate?): LocalDate {
        val selected = span(period, date).first
        val current = span(period, today).first
        val newest = span(period, shownNewest ?: today).first
        val moved = when {
            selected > newest -> selected
            selected < step(period, newest, -(TREND_PERIODS - 1)) -> step(period, selected, TREND_PERIODS - 1)
            else -> newest
        }

        return minOf(moved, maxOf(selected, current))
    }

    /**
     * Whether the bars have something to compare: two periods or more, some of them earned
     * something, and one of them is the [selected] period (its first day). Before the Stylist's
     * first Appointment there is no bar for it, so none are shown. [selected] is null while the
     * bars of other periods are kept until the next ones come.
     */
    fun showsTrend(trend: EarningsTrend, selected: LocalDate?): Boolean =
        trend.periods.size >= 2 &&
            trend.periods.any { it.earnedCents > 0 } &&
            (selected == null || trend.periods.any { it.firstDay == selected.toString() })

    /**
     * The heights of the bars, 0 to 1: the period that earned the most takes the whole height, the
     * others their share of it. A period that earned something is still a bar that can be seen; one
     * that earned nothing has none.
     */
    fun trendFractions(periods: List<EarningsTrend.Period>): List<Float> {
        val biggest = periods.maxOfOrNull { it.earnedCents }?.takeIf { it > 0 } ?: return periods.map { 0f }

        return periods.map { if (it.earnedCents <= 0) 0f else (it.earnedCents.toFloat() / biggest).coerceIn(MIN_TREND_BAR, 1f) }
    }

    private const val MIN_TREND_BAR = 0.03f

    /**
     * What each bar says under it, for the periods starting on [firsts] side by side: a week its
     * first day, with the month on the first bar and where the month changes ("12 окт.", "19", "26",
     * "2 нояб."), a month its name ("нояб.", "Nov"), a year its number.
     */
    fun barLabels(period: EarningsPeriod, firsts: List<LocalDate>, locale: Locale): List<String> {
        val dayAndMonth = DateTimeFormatter.ofPattern(if (locale.language == "ru") "d MMM" else "MMM d", locale)
        val month = DateTimeFormatter.ofPattern("LLL", locale)

        return firsts.mapIndexed { index, first ->
            when (period) {
                EarningsPeriod.WEEK ->
                    if (index == 0 || firsts[index - 1].month != first.month) dayAndMonth.format(first) else first.dayOfMonth.toString()
                EarningsPeriod.MONTH -> month.format(first)
                EarningsPeriod.YEAR -> first.year.toString()
            }
        }
    }

    /** Whether the period holds nothing at all: no outcome of any kind, nothing waiting for one, and nothing booked. */
    fun isEmpty(summary: EarningsSummary): Boolean =
        summary.visits == 0 && summary.canceled == 0 && summary.noShow == 0 && unresolvedCount(summary) == 0 &&
            (summary.upcoming?.count ?: 0) == 0

    /** How many Appointments of the period are waiting for an outcome; 0 from a server that does not say. */
    fun unresolvedCount(summary: EarningsSummary): Int = summary.unresolved?.count ?: 0
}
