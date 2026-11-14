package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.CalendarPages
import com.northphoenix.hairdresserclientmanager.domain.CalendarWeek
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/** The weeks and months the Calendar View swipes through, and the day a swipe selects. */
class CalendarPagesTest {
    private val mondayFirst = CalendarPages(DayOfWeek.MONDAY)
    private val sundayFirst = CalendarPages(DayOfWeek.SUNDAY)

    // A Saturday.
    private val day = LocalDate.of(2026, 11, 14)

    @Test
    fun `a week and a month are found on their pages`() {
        for (pages in listOf(mondayFirst, sundayFirst)) {
            val week = pages.weekPage(day)
            val month = pages.monthPage(day)

            assertEquals(pages.weekStart(day), pages.weekOf(week))
            assertEquals(YearMonth.of(2026, 11), pages.monthOf(month))
            assertEquals(pages.weekStart(day).plusWeeks(1), pages.weekOf(week + 1))
            assertEquals(YearMonth.of(2027, 1), pages.monthOf(month + 2))
        }
        assertEquals(LocalDate.of(2026, 11, 9), mondayFirst.weekStart(day))
        assertEquals(LocalDate.of(2026, 11, 8), sundayFirst.weekStart(day))
    }

    @Test
    fun `a day outside the pages is on the first or the last of them`() {
        assertEquals(0, mondayFirst.weekPage(LocalDate.of(1850, 6, 1)))
        assertEquals(mondayFirst.weekCount - 1, mondayFirst.weekPage(LocalDate.of(2200, 6, 1)))
        assertEquals(0, mondayFirst.monthPage(LocalDate.of(1850, 6, 1)))
        assertEquals(mondayFirst.monthCount - 1, mondayFirst.monthPage(LocalDate.of(2200, 6, 1)))
        assertEquals(YearMonth.of(2100, 12), mondayFirst.monthOf(mondayFirst.monthCount - 1))
    }

    @Test
    fun `a swipe to another week selects the same weekday there`() {
        val page = mondayFirst.weekPage(day)

        assertEquals(day, mondayFirst.dayOnWeekPage(day, page))
        assertEquals(LocalDate.of(2026, 11, 21), mondayFirst.dayOnWeekPage(day, page + 1))
        assertEquals(LocalDate.of(2026, 10, 31), mondayFirst.dayOnWeekPage(day, page - 2))
    }

    @Test
    fun `a swipe to another month selects the same day there, or the month's last`() {
        val january = LocalDate.of(2026, 1, 31)
        val page = mondayFirst.monthPage(january)

        assertEquals(LocalDate.of(2026, 2, 28), mondayFirst.dayOnMonthPage(january, page + 1))
        assertEquals(LocalDate.of(2026, 3, 31), mondayFirst.dayOnMonthPage(january, page + 2))
        assertEquals(LocalDate.of(2025, 12, 31), mondayFirst.dayOnMonthPage(january, page - 1))
        assertEquals(LocalDate.of(2028, 2, 29), mondayFirst.dayOnMonthPage(LocalDate.of(2028, 1, 31), mondayFirst.monthPage(LocalDate.of(2028, 2, 1))))
    }

    @Test
    fun `a day of the next month tapped in this one stays selected in its own month`() {
        // 3 December is in the last week of November's view, and in December's own.
        val tapped = LocalDate.of(2026, 12, 3)

        assertEquals(tapped, mondayFirst.dayOnMonthPage(tapped, mondayFirst.monthPage(tapped)))
        assertEquals(LocalDate.of(2026, 11, 3), mondayFirst.dayOnMonthPage(tapped, mondayFirst.monthPage(tapped) - 1))
    }

    @Test
    fun `a month view is whole weeks, with the days of the months around it`() {
        // 1 November 2026 is a Sunday, 30 November a Monday.
        val november = YearMonth.of(2026, 11)
        val fromMonday = mondayFirst.monthGrid(november)
        val fromSunday = sundayFirst.monthGrid(november)

        assertEquals(6, fromMonday.size)
        assertEquals(LocalDate.of(2026, 10, 26), fromMonday.first().first())
        assertEquals(LocalDate.of(2026, 12, 6), fromMonday.last().last())
        assertEquals(5, fromSunday.size)
        assertEquals(LocalDate.of(2026, 11, 1), fromSunday.first().first())
        assertEquals(LocalDate.of(2026, 12, 5), fromSunday.last().last())
        assertEquals(4, sundayFirst.weeksIn(YearMonth.of(2026, 2)))
        assertEquals(6, mondayFirst.weeksIn(YearMonth.of(2026, 8)))
    }

    @Test
    fun `the months read for the marks cover the pages in view, those next to them and the month the week opens into`() {
        val week = mondayFirst.weekPage(day)
        // The week of 7 December has the week of 30 November, which starts in November, before it.
        val december = LocalDate.of(2026, 12, 7)

        assertEquals((10..12).map { YearMonth.of(2026, it) }, mondayFirst.monthsToMark(week, inMonth = false, selected = day))
        assertEquals(
            listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1)),
            mondayFirst.monthsToMark(mondayFirst.weekPage(december), inMonth = false, selected = december),
        )
        assertEquals(
            (9..12).map { YearMonth.of(2026, it) } + YearMonth.of(2027, 1),
            mondayFirst.monthsToMark(mondayFirst.monthPage(day), inMonth = true, selected = day),
        )
        // Every day a week or a month on screen or next to it shows is in a month that is read.
        for (selected in listOf(day, LocalDate.of(2026, 11, 30), LocalDate.of(2027, 1, 1))) {
            val weekPage = mondayFirst.weekPage(selected)
            val read = mondayFirst.monthsToMark(weekPage, inMonth = false, selected = selected)
            val shown = (weekPage - 1..weekPage + 1).flatMap { CalendarWeek.days(mondayFirst.weekOf(it)) } +
                mondayFirst.monthGrid(YearMonth.from(selected)).flatten()
            assertEquals(emptyList<LocalDate>(), shown.filter { YearMonth.from(it) !in read })

            val monthPage = mondayFirst.monthPage(selected)
            val readInMonth = mondayFirst.monthsToMark(monthPage, inMonth = true, selected = selected)
            val shownInMonth = (monthPage - 1..monthPage + 1).flatMap { mondayFirst.monthGrid(mondayFirst.monthOf(it)).flatten() }
            assertEquals(emptyList<LocalDate>(), shownInMonth.filter { YearMonth.from(it) !in readInMonth })
        }
    }

    @Test
    fun `the week a day is in looks the same in the week and in its month`() {
        // What the Calendar shows does not change as the week opens into the month or closes again.
        for (pages in listOf(mondayFirst, sundayFirst)) {
            var shown = LocalDate.of(2026, 1, 1)
            while (shown.year == 2026) {
                val week = CalendarWeek.days(pages.weekOf(pages.weekPage(shown)))

                assertEquals(week, pages.monthGrid(YearMonth.from(shown))[pages.weekOfMonth(shown)])
                shown = shown.plusDays(1)
            }
        }
        assertEquals(2, mondayFirst.weekOfMonth(day))
        assertEquals(1, sundayFirst.weekOfMonth(day))
    }
}
