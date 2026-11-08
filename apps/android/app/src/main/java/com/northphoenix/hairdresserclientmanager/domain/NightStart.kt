package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A start at night, from 21:00 to before 6:00 in the Stylist's timezone, is most likely AM and PM
 * mixed up. The form opens at the next full hour, so on a 12-hour clock a booking made in the
 * afternoon opens the time picker on PM, and a tap on "11" makes it 23:00; one made in the morning
 * turns "5" into 5:00 AM. The Appointment form says so under the start time, and a Scheduled
 * Appointment at night asks once on its screen, both offering the same time on the other half of
 * the day. A Stylist who does work at night keeps the time with one tap. A Client Reminder would
 * send that time to the Client, so the Home View's tomorrow card asks too, and 'Remind all' starts
 * with it unchecked. Tested in `NightStartTest`.
 */
object NightStart {
    private const val NIGHT_FROM_HOUR = 21
    private const val DAY_FROM_HOUR = 6

    fun isAtNight(time: LocalTime): Boolean = time.hour >= NIGHT_FROM_HOUR || time.hour < DAY_FROM_HOUR

    /** The time offered for a [start] at night: the same time 12 hours away, 11:00 for 23:00 and 13:00 for 1:00. Null in the daytime. */
    fun dayTime(start: LocalTime): LocalTime? = start.plusHours(12).takeIf { isAtNight(start) }

    /** The times of an Appointment moved to [dayTime] on the same day. */
    data class Moved(val start: LocalTime, val end: LocalTime?)

    /**
     * [start] moved to its [dayTime], and the [end] time, when there is one, by as much, so that the
     * Appointment keeps its length: an end picked on the same wrong half of the day moves with it.
     * Null for a start in the daytime.
     */
    fun moved(start: LocalTime, end: LocalTime?): Moved? =
        dayTime(start)?.let { Moved(it, end?.plusHours(12)) }

    /**
     * Whether the screen of [appointment] asks about its start time: a Scheduled Appointment that
     * starts at night ([startTime], in the Stylist's timezone) at a moment the Stylist has not kept
     * ([kept] holds [keptKey]s). One with an outcome has happened, and its time is not asked about.
     */
    fun asks(appointment: Appointment, startTime: LocalTime, kept: Set<String>): Boolean =
        appointment.status == AppointmentStatus.SCHEDULED &&
            isAtNight(startTime) &&
            keptKey(appointment.id, Instant.parse(appointment.startsAt)) !in kept

    /** [asks], with the start time of [appointment] read in [zone], the Stylist's timezone. */
    fun asks(appointment: Appointment, zone: ZoneId, kept: Set<String>): Boolean =
        asks(appointment, Instant.parse(appointment.startsAt).atZone(zone).toLocalTime(), kept)

    /**
     * What the device keeps when the Stylist keeps a start at night: the Appointment at that
     * moment, so that one moved to another time at night is asked about again.
     */
    fun keptKey(appointmentId: String, startsAt: Instant): String = "$appointmentId@${startsAt.epochSecond}"
}
