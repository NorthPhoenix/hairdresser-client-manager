package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import com.northphoenix.hairdresserclientmanager.data.EarningsSummary.TopService
import com.northphoenix.hairdresserclientmanager.domain.Earnings
import com.northphoenix.hairdresserclientmanager.domain.EarningsPeriod
import com.northphoenix.hairdresserclientmanager.data.hcmJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** The periods of Итоги: which days they cover, how they are stepped through and named. */
class EarningsTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val ru = Locale.forLanguageTag("ru")
    private val en = Locale.forLanguageTag("en")

    @Test
    fun aWeekRunsFromMondayToSunday() {
        val wednesday = Earnings.span(EarningsPeriod.WEEK, today)

        assertEquals(LocalDate.of(2026, 10, 19), wednesday.first)
        assertEquals(LocalDate.of(2026, 10, 25), wednesday.last)
        // Monday and Sunday are in the same week, and the next Monday starts another.
        assertEquals(wednesday, Earnings.span(EarningsPeriod.WEEK, LocalDate.of(2026, 10, 19)))
        assertEquals(wednesday, Earnings.span(EarningsPeriod.WEEK, LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2026, 10, 26), Earnings.span(EarningsPeriod.WEEK, LocalDate.of(2026, 10, 26)).first)
    }

    @Test
    fun aMonthAndAYearAreTheCalendarOnes() {
        assertEquals(Earnings.Span(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), Earnings.span(EarningsPeriod.MONTH, today))
        assertEquals(LocalDate.of(2028, 2, 29), Earnings.span(EarningsPeriod.MONTH, LocalDate.of(2028, 2, 10)).last)
        assertEquals(Earnings.Span(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), Earnings.span(EarningsPeriod.YEAR, today))
    }

    @Test
    fun steppingLandsOnTheFirstDayOfTheNeighbouringPeriod() {
        assertEquals(LocalDate.of(2026, 10, 12), Earnings.step(EarningsPeriod.WEEK, today, -1))
        assertEquals(LocalDate.of(2026, 10, 26), Earnings.step(EarningsPeriod.WEEK, today, 1))
        // The 31st of a month does not skip a month with fewer days.
        assertEquals(LocalDate.of(2026, 11, 1), Earnings.step(EarningsPeriod.MONTH, LocalDate.of(2026, 10, 31), 1))
        assertEquals(LocalDate.of(2026, 2, 1), Earnings.step(EarningsPeriod.MONTH, LocalDate.of(2026, 3, 31), -1))
        assertEquals(LocalDate.of(2025, 12, 1), Earnings.step(EarningsPeriod.MONTH, LocalDate.of(2026, 1, 15), -1))
        assertEquals(LocalDate.of(2025, 1, 1), Earnings.step(EarningsPeriod.YEAR, today, -1))
    }

    @Test
    fun theWayIntoThePastAndTheFutureHasAnEnd() {
        assertTrue(Earnings.hasPrevious(EarningsPeriod.MONTH, today))
        assertFalse(Earnings.hasPrevious(EarningsPeriod.MONTH, LocalDate.of(2000, 1, 20)))
        assertTrue(Earnings.hasNext(EarningsPeriod.MONTH, today, today))
        assertFalse(Earnings.hasNext(EarningsPeriod.MONTH, LocalDate.of(2027, 10, 5), today))
    }

    @Test
    fun aPeriodIsNamedInTheLanguageAndSaysItsYearOnlyWhenItIsAnotherOne() {
        assertEquals("Октябрь", Earnings.title(EarningsPeriod.MONTH, today, today, ru))
        assertEquals("October", Earnings.title(EarningsPeriod.MONTH, today, today, en))
        assertEquals("Октябрь 2025", Earnings.title(EarningsPeriod.MONTH, LocalDate.of(2025, 10, 3), today, ru))
        assertEquals("2026", Earnings.title(EarningsPeriod.YEAR, today, today, ru))
        assertEquals("2025", Earnings.title(EarningsPeriod.YEAR, LocalDate.of(2025, 6, 1), today, en))
    }

    @Test
    fun aWeekIsNamedByItsDays() {
        assertEquals("19–25 окт.", Earnings.title(EarningsPeriod.WEEK, today, today, ru))
        assertEquals("Oct 19–25", Earnings.title(EarningsPeriod.WEEK, today, today, en))
        // Over the end of a month, and of a year.
        assertEquals("26 окт. – 1 нояб.", Earnings.title(EarningsPeriod.WEEK, LocalDate.of(2026, 10, 28), today, ru))
        assertEquals("Oct 26 – Nov 1", Earnings.title(EarningsPeriod.WEEK, LocalDate.of(2026, 10, 28), today, en))
        assertEquals("Dec 29 2025 – Jan 4 2026", Earnings.title(EarningsPeriod.WEEK, LocalDate.of(2025, 12, 31), today, en))
        assertEquals("29 дек. 2025 – 4 янв. 2026", Earnings.title(EarningsPeriod.WEEK, LocalDate.of(2025, 12, 31), today, ru))
    }

    @Test
    fun theBiggestBarTakesTheWholeWidthAndASmallOneCanStillBeSeen() {
        val services = listOf(TopService("A", 19_000, 4), TopService("B", 9_500, 2), TopService("C", 1, 1))
        val bars = Earnings.barFractions(services)

        assertEquals(1f, bars[0], 0.001f)
        assertEquals(0.5f, bars[1], 0.001f)
        assertEquals(0.04f, bars[2], 0.001f)
        assertEquals(emptyList<Float>(), Earnings.barFractions(emptyList()))
        // Nothing earned: no division by zero.
        assertEquals(listOf(0.04f), Earnings.barFractions(listOf(TopService("Free", 0, 1))))
    }

    @Test
    fun aPeriodWithNoOutcomeAndNothingBookedIsEmpty() {
        assertTrue(Earnings.isEmpty(EarningsSummary(firstDay = "2026-10-01", lastDay = "2026-10-31")))
        assertTrue(Earnings.isEmpty(EarningsSummary(firstDay = "a", lastDay = "b", upcoming = EarningsSummary.Upcoming(0, 0))))
        assertFalse(Earnings.isEmpty(EarningsSummary(firstDay = "a", lastDay = "b", visits = 1)))
        assertFalse(Earnings.isEmpty(EarningsSummary(firstDay = "a", lastDay = "b", canceled = 1)))
        assertFalse(Earnings.isEmpty(EarningsSummary(firstDay = "a", lastDay = "b", noShow = 1)))
        assertFalse(Earnings.isEmpty(EarningsSummary(firstDay = "a", lastDay = "b", upcoming = EarningsSummary.Upcoming(2, 5_000))))
    }

    @Test
    fun theServerAnswerIsReadWithItsExtraFieldsIgnored() {
        val json = hcmJson
        val summary = json.decodeFromString<EarningsSummary>(
            """{"period":"month","firstDay":"2026-10-01","lastDay":"2026-10-31","startsAt":"2026-10-01T05:00:00.000Z",
            "endsAt":"2026-11-01T05:00:00.000Z","today":"2026-10-21","isCurrent":true,
            "earned":{"totalCents":37000,"servicesCents":33500,"tipsCents":3500},"visits":4,"averageCents":9250,
            "atHomeVisits":3,"canceled":0,"noShow":1,"upcoming":{"count":8,"totalCents":64500},
            "topServices":[{"name":"Стрижки","revenueCents":19000,"count":4}]}""",
        )

        assertEquals(37_000L, summary.earned.totalCents)
        assertEquals(3_500L, summary.earned.tipsCents)
        assertEquals(8, summary.upcoming?.count)
        assertEquals("Стрижки", summary.topServices.single().name)
        // Over: no `upcoming`.
        assertEquals(null, json.decodeFromString<EarningsSummary>("""{"firstDay":"a","lastDay":"b","upcoming":null}""").upcoming)
    }
}
