package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A week of the Calendar View: its seven days, and which days are marked as having Appointments.
 * The Calendar's month and the date picker mark their days the same way, and the form that books
 * or moves an Appointment lists what its day already has.
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
     * The list of [day] in [zone] as `appointment.list` gives it for the day, out of [appointments]
     * read for a longer time: every one that starts on it, canceled ones too, in the order they came
     * (the server's: by start). The Calendar View shows the days next to the selected one from the
     * month it has read, so they are there as they are swiped in.
     */
    fun startingOn(appointments: List<Appointment>, day: LocalDate, zone: ZoneId): List<Appointment> =
        appointments.filter { Instant.parse(it.startsAt).atZone(zone).toLocalDate() == day }

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
