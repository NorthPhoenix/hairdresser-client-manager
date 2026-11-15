package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.CalendarPages
import com.northphoenix.hairdresserclientmanager.domain.CalendarWeek
import com.northphoenix.hairdresserclientmanager.ui.screens.opensOnRelease
import com.northphoenix.hairdresserclientmanager.ui.screens.pullsCalendar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weeks and months the Calendar View swipes through, the day a swipe selects, and when a pull opens the month. */
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
    fun `the list under the calendar has a page for every day`() {
        val page = CalendarPages.dayPage(day)

        assertEquals(day, CalendarPages.dayOf(page))
        assertEquals(LocalDate.of(2026, 11, 15), CalendarPages.dayOf(page + 1))
        assertEquals(LocalDate.of(2026, 11, 1), CalendarPages.dayOf(page - 13))
        assertEquals(0, CalendarPages.dayPage(LocalDate.of(1850, 1, 1)))
        assertEquals(LocalDate.of(2100, 12, 31), CalendarPages.dayOf(CalendarPages.dayCount - 1))
        assertEquals(CalendarPages.dayCount - 1, CalendarPages.dayPage(LocalDate.of(2200, 1, 1)))
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
    fun `the months read for the marks hold every day of the weeks around the selected one and of its month`() {
        assertEquals((10..12).map { YearMonth.of(2026, it) }, CalendarPages.monthsAround(day))

        for (pages in listOf(mondayFirst, sundayFirst)) {
            var selected = LocalDate.of(2026, 1, 1)
            while (selected.year == 2026) {
                val read = CalendarPages.monthsAround(selected)
                val week = pages.weekPage(selected)
                val shown = (week - 1..week + 1).flatMap { CalendarWeek.days(pages.weekOf(it)) } + pages.monthGrid(YearMonth.from(selected)).flatten()
                // The months next to it come in with their own days marked.
                val swipedTo = listOf(-1L, 1L).map { YearMonth.from(selected).plusMonths(it) }

                assertEquals(emptyList<LocalDate>(), shown.filter { YearMonth.from(it) !in read })
                assertTrue(read.containsAll(swipedTo))
                selected = selected.plusDays(1)
            }
        }
    }

    @Test
    fun `a vertical drag is the calendar's only when the month can open or close with it`() {
        assertTrue(pullsCalendar(moved = 12f, opened = 0f, listAtTop = true))
        assertTrue(pullsCalendar(moved = -12f, opened = 1f, listAtTop = true))
        assertTrue(pullsCalendar(moved = -12f, opened = 0.4f, listAtTop = true))
        // Up on the week and down on the open month, the list scrolls and refreshes.
        assertFalse(pullsCalendar(moved = -12f, opened = 0f, listAtTop = true))
        assertFalse(pullsCalendar(moved = 12f, opened = 1f, listAtTop = true))
        // With the list scrolled down, a pull down scrolls it back up; the open month still closes.
        assertFalse(pullsCalendar(moved = 12f, opened = 0f, listAtTop = false))
        assertTrue(pullsCalendar(moved = -12f, opened = 1f, listAtTop = false))
    }

    @Test
    fun `a pull let go fast goes on in its direction, a slow one to whichever is nearer`() {
        assertTrue(opensOnRelease(opened = 0.1f, speed = 1200f, fastSpeed = 1000f))
        assertFalse(opensOnRelease(opened = 0.9f, speed = -1200f, fastSpeed = 1000f))
        assertTrue(opensOnRelease(opened = 0.6f, speed = -300f, fastSpeed = 1000f))
        assertFalse(opensOnRelease(opened = 0.4f, speed = 300f, fastSpeed = 1000f))
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
