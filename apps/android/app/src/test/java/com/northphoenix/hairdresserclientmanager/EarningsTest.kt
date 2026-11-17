package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import com.northphoenix.hairdresserclientmanager.data.EarningsSummary.TopService
import com.northphoenix.hairdresserclientmanager.data.EarningsTrend
import com.northphoenix.hairdresserclientmanager.domain.Earnings
import com.northphoenix.hairdresserclientmanager.domain.EarningsPeriod
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.state.TrendShown
import com.northphoenix.hairdresserclientmanager.state.trendShown
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
    fun aPeriodWithOnlyAVisitWaitingForAnOutcomeIsNotEmpty() {
        val waiting = EarningsSummary(firstDay = "a", lastDay = "b", unresolved = EarningsSummary.Unresolved(1, 12_000))

        assertFalse(Earnings.isEmpty(waiting))
        assertEquals(1, Earnings.unresolvedCount(waiting))
        assertTrue(Earnings.isEmpty(EarningsSummary(firstDay = "a", lastDay = "b", unresolved = EarningsSummary.Unresolved(0, 0))))
        // A server before it was sent: none known.
        assertEquals(0, Earnings.unresolvedCount(EarningsSummary(firstDay = "a", lastDay = "b")))
    }

    @Test
    fun theServerAnswerIsReadWithItsExtraFieldsIgnored() {
        val json = hcmJson
        val summary = json.decodeFromString<EarningsSummary>(
            """{"period":"month","firstDay":"2026-10-01","lastDay":"2026-10-31","startsAt":"2026-10-01T05:00:00.000Z",
            "endsAt":"2026-11-01T05:00:00.000Z","today":"2026-10-21","isCurrent":true,
            "earned":{"totalCents":37000,"servicesCents":33500,"tipsCents":3500},"visits":4,"averageCents":9250,
            "atHomeVisits":3,"canceled":0,"noShow":1,"upcoming":{"count":8,"totalCents":64500},
            "unresolved":{"count":2,"totalCents":20500},"topServices":[{"name":"Стрижки","revenueCents":19000,"count":4}]}""",
        )

        assertEquals(37_000L, summary.earned.totalCents)
        assertEquals(3_500L, summary.earned.tipsCents)
        assertEquals(8, summary.upcoming?.count)
        assertEquals(EarningsSummary.Unresolved(2, 20_500), summary.unresolved)
        assertEquals("Стрижки", summary.topServices.single().name)
        // Over: no `upcoming`.
        assertEquals(null, json.decodeFromString<EarningsSummary>("""{"firstDay":"a","lastDay":"b","upcoming":null}""").upcoming)
        // From a server before `unresolved` was sent.
        assertEquals(null, json.decodeFromString<EarningsSummary>("""{"firstDay":"a","lastDay":"b"}""").unresolved)
    }

    @Test
    fun theBarsEndWithTodaysPeriodAndStayWhileTheSelectedOneIsAmongThem() {
        val week = EarningsPeriod.WEEK
        // Wednesday 21 Oct: the week of the 19th.
        val thisWeek = LocalDate.of(2026, 10, 19)

        assertEquals(thisWeek, Earnings.trendNewest(week, today, today, shownNewest = null))
        // Three weeks back, five weeks back: still among the six bars, which do not move.
        assertEquals(thisWeek, Earnings.trendNewest(week, LocalDate.of(2026, 9, 30), today, thisWeek))
        assertEquals(thisWeek, Earnings.trendNewest(week, LocalDate.of(2026, 9, 14), today, thisWeek))
        // One more step back moves them by one week, so the selected one is the oldest bar.
        assertEquals(LocalDate.of(2026, 10, 12), Earnings.trendNewest(week, LocalDate.of(2026, 9, 7), today, thisWeek))
        // From there, stepping forward keeps them until it passes their newest.
        assertEquals(LocalDate.of(2026, 10, 12), Earnings.trendNewest(week, LocalDate.of(2026, 10, 12), today, LocalDate.of(2026, 10, 12)))
        assertEquals(thisWeek, Earnings.trendNewest(week, LocalDate.of(2026, 10, 25), today, LocalDate.of(2026, 10, 12)))
        // A week to come is the newest bar.
        assertEquals(LocalDate.of(2026, 10, 26), Earnings.trendNewest(week, LocalDate.of(2026, 10, 28), today, thisWeek))
        // Six weeks ahead and back to this week: the bars end with this week again, not with weeks to come.
        assertEquals(LocalDate.of(2026, 11, 30), Earnings.trendNewest(week, LocalDate.of(2026, 12, 2), today, LocalDate.of(2026, 11, 23)))
        assertEquals(thisWeek, Earnings.trendNewest(week, today, today, LocalDate.of(2026, 11, 30)))
        // A week to come among the bars, stepped back from: they stay put, as for any period among them.
        assertEquals(LocalDate.of(2026, 11, 2), Earnings.trendNewest(week, LocalDate.of(2026, 10, 26), today, LocalDate.of(2026, 11, 2)))
        // Bars from 26 Oct to 30 Nov (reaching into weeks to come), an arrow back past their oldest: they end with this
        // week, not five weeks after the selected one.
        assertEquals(thisWeek, Earnings.trendNewest(week, LocalDate.of(2026, 10, 19), today, LocalDate.of(2026, 11, 30)))
        // Shown first on a period long ago, the bars end six periods after it, so it is the oldest one.
        assertEquals(LocalDate.of(2026, 6, 1), Earnings.trendNewest(EarningsPeriod.MONTH, LocalDate.of(2026, 1, 20), today, null))
        assertEquals(LocalDate.of(2026, 10, 1), Earnings.trendNewest(EarningsPeriod.MONTH, LocalDate.of(2026, 5, 20), today, null))
    }

    @Test
    fun tappingABarSelectsItsPeriodTodayForTodaysAndNothingForTheSelectedOne() {
        val month = EarningsPeriod.MONTH

        assertEquals(LocalDate.of(2026, 8, 1), Earnings.anchorForBar(month, LocalDate.of(2026, 8, 1), today, today))
        // Back to this month: today, so Неделя then opens on this week, not the month's first.
        assertEquals(today, Earnings.anchorForBar(month, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 8, 1), today))
        // The bar already selected changes nothing, whatever day of it is shown.
        assertEquals(null, Earnings.anchorForBar(month, LocalDate.of(2026, 10, 1), today, today))
        assertEquals(null, Earnings.anchorForBar(month, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 17), today))
    }

    @Test
    fun barsOnScreenStayWhileTheNextOnesAreReadAndHiddenOnesNeverComeBack() {
        fun bars(vararg months: Int) =
            EarningsTrend(months.map { EarningsTrend.Period(LocalDate.of(2026, it, 1).toString(), earnedCents = it * 100L) })
        val august = LocalDate.of(2026, 8, 1)
        val shown = bars(8, 9, 10, 11)

        // The next bars have nothing yet: the ones on screen stay, and the summary need not wait.
        val onScreen = TrendShown(shown, settled = true)
        assertEquals(onScreen, trendShown(onScreen, Read(), LocalDate.of(2026, 3, 1)))
        // They come: shown when the selected period is among them, else none.
        assertEquals(TrendShown(bars(7, 8), settled = true), trendShown(onScreen, Read(LoadState.Loaded, bars(7, 8)), august))
        assertEquals(
            TrendShown(null, settled = true, answeredNone = true),
            trendShown(onScreen, Read(LoadState.Loaded, bars(8, 9)), LocalDate.of(2026, 5, 1)),
        )
        // They fail: none, quietly.
        assertEquals(TrendShown(null, settled = true, answeredNone = true), trendShown(onScreen, Read(LoadState.Failed(0)), august))
        // Nothing shown yet: the summary waits a moment for the bars.
        assertEquals(TrendShown(null, settled = false), trendShown(null, Read(), LocalDate.of(2026, 4, 1)))
        // The last answer had no bars to show (May, before the first Appointment) or failed: nothing comes back while
        // the next ones load, and the summary does not wait for them.
        val none = trendShown(TrendShown(shown, settled = true), Read(LoadState.Loaded, bars(8, 9)), LocalDate.of(2026, 5, 1))
        assertEquals(TrendShown(null, settled = true, answeredNone = true), none)
        assertEquals(TrendShown(null, settled = true, answeredNone = true), trendShown(none, Read(), LocalDate.of(2026, 4, 1)))
        assertEquals(
            TrendShown(null, settled = true, answeredNone = true),
            trendShown(trendShown(null, Read(LoadState.Failed(0)), august), Read(), august),
        )
        // Bars again: they are waited for again next time.
        assertEquals(TrendShown(bars(8, 9), settled = true), trendShown(none, Read(LoadState.Loaded, bars(8, 9)), august))
    }

    @Test
    fun theTallestBarIsThePeriodThatEarnedMostAndNothingEarnedHasNoBar() {
        fun bars(vararg cents: Long) = cents.map { EarningsTrend.Period(firstDay = "2026-10-01", earnedCents = it) }

        assertEquals(listOf(0.5f, 1f, 0f, 0.03f), Earnings.trendFractions(bars(30_000, 60_000, 0, 500)))
        assertEquals(listOf(0f, 0f), Earnings.trendFractions(bars(0, 0)))
        assertEquals(emptyList<Float>(), Earnings.trendFractions(emptyList()))
    }

    @Test
    fun theBarsShowOnlyWhenThereIsSomethingToCompareAndTheSelectedPeriodIsOne() {
        val october = LocalDate.of(2026, 10, 1)
        fun trend(vararg cents: Long) =
            EarningsTrend(cents.mapIndexed { index, it -> EarningsTrend.Period(firstDay = october.plusMonths(index.toLong()).toString(), earnedCents = it) })

        assertTrue(Earnings.showsTrend(trend(0, 12_000), october))
        assertTrue(Earnings.showsTrend(trend(0, 12_000), october.plusMonths(1)))
        // One period (a year, or a Stylist's first month), or nothing earned in any.
        assertFalse(Earnings.showsTrend(trend(12_000), october))
        assertFalse(Earnings.showsTrend(trend(0, 0, 0), october))
        assertFalse(Earnings.showsTrend(EarningsTrend(), october))
        // A period before the Stylist's first Appointment has no bar of its own: the bars after it are not shown.
        assertFalse(Earnings.showsTrend(trend(0, 12_000), october.minusMonths(1)))
    }

    @Test
    fun aBarSaysItsWeeksFirstDayWithTheMonthWhereItChangesItsMonthOrItsYear() {
        val mondays = (0L..3L).map { LocalDate.of(2026, 10, 19).plusWeeks(it) }

        assertEquals(listOf("19 окт.", "26", "2 нояб.", "9"), Earnings.barLabels(EarningsPeriod.WEEK, mondays, ru))
        assertEquals(listOf("Oct 19", "26", "Nov 2", "9"), Earnings.barLabels(EarningsPeriod.WEEK, mondays, en))
        assertEquals(
            listOf("дек.", "янв."),
            Earnings.barLabels(EarningsPeriod.MONTH, listOf(LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1)), ru),
        )
        assertEquals(listOf("Nov"), Earnings.barLabels(EarningsPeriod.MONTH, listOf(LocalDate.of(2026, 11, 1)), en))
        assertEquals(listOf("2026", "2027"), Earnings.barLabels(EarningsPeriod.YEAR, listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)), ru))
    }

    @Test
    fun theBarsAreReadWithoutFieldsTheAppDoesNotKnow() {
        val trend = hcmJson.decodeFromString<EarningsTrend>(
            """{"period":"week","periods":[{"firstDay":"2026-10-12","lastDay":"2026-10-18","earnedCents":26500,"visits":5,"extra":1}]}""",
        )

        assertEquals(EarningsTrend.Period("2026-10-12", "2026-10-18", 26_500, 5), trend.periods.single())
    }
}
