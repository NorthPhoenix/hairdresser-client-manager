package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The week strip of the Calendar View: its seven days, and which of them are marked as having
 * Appointments. The date picker marks a month's days the same way, and the form that books or
 * moves an Appointment lists what its day already has.
 */
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

    /**
     * What [day] already has, for the form that books or moves an Appointment: those of [appointments]
     * that start on it in [zone] and are not canceled, as for the marks, by their start. Without
     * [excluding], the Appointment being moved, which is not in its own way.
     */
    fun bookedOn(appointments: List<Appointment>, day: LocalDate, zone: ZoneId, excluding: String? = null): List<Appointment> =
        appointments
            .filter { it.status != AppointmentStatus.CANCELED && it.id != excluding && Instant.parse(it.startsAt).atZone(zone).toLocalDate() == day }
            .sortedBy { Instant.parse(it.startsAt) }
}
