package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.state.CalendarDay
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.ui.screens.DayPageList
import com.northphoenix.hairdresserclientmanager.ui.screens.MonthRead
import com.northphoenix.hairdresserclientmanager.ui.screens.dayPageList
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each day of the Calendar's swiped list shows: the selected day's own read, or its month's list. */
class CalendarDayPagerTest {
    private val friday = LocalDate.of(2026, 11, 13)
    private val saturday = LocalDate.of(2026, 11, 14)

    private fun appointment(id: String, startsAt: String) =
        Appointment(id = id, primaryClientId = "c1", primaryClientName = "Anna", startsAt = startsAt, status = AppointmentStatus.SCHEDULED, locationType = LocationType.IN_SALON)

    private val own = listOf(appointment("a1", "2026-11-14T15:00:00Z"))
    private val fromMonth = listOf(appointment("a1", "2026-11-14T15:00:00Z"), appointment("a2", "2026-11-14T18:00:00Z"))

    @Test
    fun `the selected day shows its own read once it has one`() {
        assertEquals(DayPageList(own, LoadState.Loaded, own = true), dayPageList(saturday, CalendarDay(saturday, own, LoadState.Loaded), fromMonth, fromMonth))
        // Refreshed behind what it shows: still its own.
        assertEquals(DayPageList(own, LoadState.Loading, own = true), dayPageList(saturday, CalendarDay(saturday, own, LoadState.Loading), fromMonth, fromMonth))
        // Its own answer says the day is empty: that is what it shows, whatever the month had.
        assertEquals(DayPageList(emptyList(), LoadState.Loaded, own = true), dayPageList(saturday, CalendarDay(saturday, emptyList(), LoadState.Loaded), fromMonth, fromMonth))
    }

    @Test
    fun `a selected day with nothing of its own yet shows its month's current answer, never a kept one`() {
        val loading = CalendarDay(saturday, emptyList(), LoadState.Loading)
        val failed = CalendarDay(saturday, emptyList(), LoadState.Failed(R.string.app_name))

        assertEquals(DayPageList(fromMonth, LoadState.Loaded, own = false), dayPageList(saturday, loading, current = fromMonth, kept = fromMonth))
        assertEquals(DayPageList(fromMonth, LoadState.Loaded, own = false), dayPageList(saturday, failed, current = fromMonth, kept = fromMonth))
        // The month only kept its list from before: the day's own state is shown, with its retry.
        assertEquals(DayPageList(emptyList(), LoadState.Loading, own = true), dayPageList(saturday, loading, current = null, kept = fromMonth))
        assertEquals(DayPageList(emptyList(), failed.load, own = true), dayPageList(saturday, failed, current = null, kept = fromMonth))
    }

    @Test
    fun `a day next to the selected one shows its month's list, or loading`() {
        val selected = CalendarDay(saturday, own, LoadState.Loaded)
        val fridays = listOf(appointment("f1", "2026-11-13T16:00:00Z"))

        assertEquals(DayPageList(fridays, LoadState.Loaded, own = false), dayPageList(friday, selected, current = null, kept = fridays))
        assertEquals(DayPageList(emptyList(), LoadState.Loaded, own = false), dayPageList(friday, selected, current = emptyList(), kept = emptyList()))
        assertEquals(DayPageList(emptyList(), LoadState.Loading, own = false), dayPageList(friday, selected, current = null, kept = null))
    }

    @Test
    fun `a month is current while its own answer is shown, and not while it is read anew`() {
        val answer = Read(LoadState.Loaded, fromMonth)
        val shown = MonthRead.of(answer)

        assertTrue(shown.current)
        // Read anew after a change: the list stays on screen, and is no longer current.
        val again = shown.then(Read(LoadState.Loading))
        assertEquals(answer, again.shown)
        assertFalse(again.current)
        // Answered: current again.
        val answered = again.then(Read(LoadState.Loaded, own))
        assertEquals(own, answered.shown.data)
        assertTrue(answered.current)
        // Nothing yet at all.
        assertFalse(MonthRead.of(Read(LoadState.Loading)).current)
    }
}
