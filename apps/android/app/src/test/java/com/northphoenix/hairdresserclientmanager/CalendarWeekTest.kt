package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.CalendarWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The days of the Calendar's week strip and of the date picker that are marked as having Appointments, and what a day already has. */
class CalendarWeekTest {
    private val chicago = ZoneId.of("America/Chicago")

    private fun appointment(startsAt: String, status: AppointmentStatus = AppointmentStatus.SCHEDULED, id: String = startsAt) =
        Appointment(id = id, primaryClientId = "c1", primaryClientName = "Anna", startsAt = startsAt, status = status, locationType = LocationType.IN_SALON)

    @Test
    fun `a week is seven days from its first`() {
        val days = CalendarWeek.days(LocalDate.of(2026, 10, 26))

        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 11, 1), days.last())
    }

    @Test
    fun `a day is marked by any Appointment that is not canceled`() {
        val marked = CalendarWeek.daysWithAppointments(
            listOf(
                appointment("2026-10-19T15:00:00Z"),
                appointment("2026-10-20T15:00:00Z", AppointmentStatus.CANCELED),
                appointment("2026-10-21T15:00:00Z", AppointmentStatus.COMPLETED),
                appointment("2026-10-22T15:00:00Z", AppointmentStatus.NO_SHOW),
                // Canceled, but another one that day is not.
                appointment("2026-10-23T15:00:00Z", AppointmentStatus.CANCELED),
                appointment("2026-10-23T18:00:00Z"),
            ),
            chicago,
        )

        assertEquals(setOf(19, 21, 22, 23).map { LocalDate.of(2026, 10, it) }.toSet(), marked)
    }

    @Test
    fun `the day is the one in the Stylist's timezone`() {
        // 6 p.m. on Saturday in Austin is already Sunday in UTC.
        val marked = CalendarWeek.daysWithAppointments(listOf(appointment("2026-10-25T00:00:00Z")), chicago)

        assertEquals(setOf(LocalDate.of(2026, 10, 24)), marked)
    }

    @Test
    fun `what a day already has is what starts on it and is not canceled, by its start`() {
        val day = LocalDate.of(2026, 11, 16)
        val booked = CalendarWeek.bookedOn(
            listOf(
                appointment("2026-11-16T21:30:00Z"),
                appointment("2026-11-16T17:00:00Z", AppointmentStatus.CANCELED),
                appointment("2026-11-16T15:00:00Z", AppointmentStatus.NO_SHOW),
                appointment("2026-11-16T19:00:00Z", AppointmentStatus.COMPLETED),
                // The 17th in Austin; then the 16th in Austin, though the 17th in UTC.
                appointment("2026-11-17T08:00:00Z"),
                appointment("2026-11-17T02:00:00Z"),
            ),
            day,
            chicago,
        )

        assertEquals(
            listOf("2026-11-16T15:00:00Z", "2026-11-16T19:00:00Z", "2026-11-16T21:30:00Z", "2026-11-17T02:00:00Z"),
            booked.map { it.startsAt },
        )
    }

    @Test
    fun `the Appointment being moved is not in its own way`() {
        val booked = CalendarWeek.bookedOn(
            listOf(appointment("2026-11-16T17:00:00Z", id = "moving"), appointment("2026-11-16T21:30:00Z", id = "other")),
            LocalDate.of(2026, 11, 16),
            chicago,
            excluding = "moving",
        )

        assertEquals(listOf("other"), booked.map { it.id })
    }
}
