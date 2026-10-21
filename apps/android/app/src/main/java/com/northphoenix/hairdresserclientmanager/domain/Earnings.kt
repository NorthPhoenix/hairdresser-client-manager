package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
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

    /** Whether the period holds nothing at all: no outcome of any kind, and nothing booked. */
    fun isEmpty(summary: EarningsSummary): Boolean =
        summary.visits == 0 && summary.canceled == 0 && summary.noShow == 0 && (summary.upcoming?.count ?: 0) == 0
}
