package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The week strip of the Calendar View: its seven days, and which of them are marked as having
 * Appointments. The date picker marks a month's days by the same weeks, and the form that books or
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
     * The first days of the weeks that a month's grid shows ([weeksOf]): the week that has the 1st
     * of [month], and each week after it up to the one that has its last day. The date picker marks
     * the month's days by these weeks, which are the weeks of the strip.
     */
    fun firstDaysOf(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> =
        generateSequence(month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))) { it.plusDays(LENGTH) }
            .takeWhile { !it.isAfter(month.atEndOfMonth()) }
            .toList()

    /**
     * What a day already has, for the form that books or moves an Appointment: [appointments] of the
     * day that are not canceled, as for the marks, by their start. Without [excluding], the
     * Appointment being moved, which is not in its own way.
     */
    fun bookedOn(appointments: List<Appointment>, excluding: String? = null): List<Appointment> =
        appointments
            .filter { it.status != AppointmentStatus.CANCELED && it.id != excluding }
            .sortedBy { Instant.parse(it.startsAt) }
}
