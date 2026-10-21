package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import java.time.Duration
import java.time.Instant

/** A Client Reminder the Stylist sent (or opened in her SMS app) for an Appointment that started at [startsAt], at [at]. */
data class Reminded(val appointmentId: String, val startsAt: String, val at: Instant)

/**
 * Which Appointments were reminded. It is kept on the device only: the server does not know. A reminder
 * counts for the time the Appointment had when it was sent, so one moved to another time is not
 * "reminded" any more, and the new time has not been told to the Client.
 */
object ReminderLog {
    /** Reminders of Appointments this old are forgotten: she books at most about three weeks ahead. */
    private val keptFor: Duration = Duration.ofDays(45)

    fun encode(entry: Reminded): String = "${entry.appointmentId}|${entry.at.toEpochMilli()}|${entry.startsAt}"

    fun decode(raw: String): Reminded? {
        val (id, millis, startsAt) = raw.split('|', limit = 3).takeIf { it.size == 3 } ?: return null

        return millis.toLongOrNull()?.let { Reminded(id, startsAt, Instant.ofEpochMilli(it)) }
    }

    /** [entries] without those older than the log keeps. */
    fun pruned(entries: Map<String, Reminded>, now: Instant): Map<String, Reminded> =
        entries.filterValues { Duration.between(it.at, now) < keptFor }

    /** When [appointment] was reminded for its current time, or null when it was not. */
    fun remindedAt(log: Map<String, Reminded>, appointment: Appointment): Instant? =
        log[appointment.id]?.takeIf { it.startsAt == appointment.startsAt }?.at
}
