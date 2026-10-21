package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The week strip of the Calendar View: its seven days, and which of them are marked as having Appointments. */
object CalendarWeek {
    const val LENGTH = 7L

    /** The seven days of the week that starts on [firstDay]. */
    fun days(firstDay: LocalDate): List<LocalDate> = (0 until LENGTH).map(firstDay::plusDays)

    /**
     * The days, in [zone], on which at least one of [appointments] starts that is not canceled: a
     * canceled Appointment is no work on that day. A no-show still marks its day, as it is still
     * in the day's list as something that was booked.
     */
    fun daysWithAppointments(appointments: List<Appointment>, zone: ZoneId): Set<LocalDate> =
        appointments
            .filter { it.status != AppointmentStatus.CANCELED }
            .mapTo(mutableSetOf()) { Instant.parse(it.startsAt).atZone(zone).toLocalDate() }
}
