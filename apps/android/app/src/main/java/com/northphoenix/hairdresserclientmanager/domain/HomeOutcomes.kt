package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Quick outcomes on the Home View: the rules of the outcome chips on a card, of 'All completed'
 * for a day, and of undoing either from the notice.
 */
object HomeOutcomes {
    /** The outcomes a card offers, in this order. Scheduled is not one of them: going back to it is the undo. */
    val choices: List<AppointmentStatus> = listOf(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW)

    /** A day needs a row that completes all of its unresolved Appointments at once when it has at least this many. */
    const val CLOSE_ALL_FROM = 2

    /**
     * Whether the card of [appointment] offers the outcome chips at [now]: a Scheduled Appointment
     * whose start time has come. A future one, or one of today that has not started, has none.
     */
    fun needsOutcome(appointment: Appointment, now: Instant): Boolean =
        appointment.status == AppointmentStatus.SCHEDULED && !Instant.parse(appointment.startsAt).isAfter(now)

    /** The unresolved Appointments of one day, in the order the list has them. */
    data class UnresolvedDay(val day: LocalDate, val appointments: List<Appointment>) {
        /** Whether the day gets a row that completes all of them: with one there is nothing to save. */
        val closesAll: Boolean get() = appointments.size >= CLOSE_ALL_FROM
    }

    /** [unresolved] by the day each starts on in [zone], the days in the order of their first Appointment. */
    fun byDay(unresolved: List<Appointment>, zone: ZoneId): List<UnresolvedDay> =
        unresolved
            .groupBy { Instant.parse(it.startsAt).atZone(zone).toLocalDate() }
            .map { (day, appointments) -> UnresolvedDay(day, appointments) }

    /** What 'Undo' does for an Appointment that was given an outcome from its card. */
    enum class Undo {
        /** Set it back to Scheduled. */
        REVERT,

        /** It is Scheduled already: nothing to do. */
        NOTHING,

        /** It has another outcome by now, set elsewhere: that one stays. */
        CHANGED_ELSEWHERE,
    }

    /**
     * What 'Undo' does for an Appointment that was set to [outcome], and is [shown] now. Null when
     * no screen has it any more (it has left the Home View): the outcome is then the last the app
     * knows of, and is undone.
     */
    fun undo(shown: AppointmentStatus?, outcome: AppointmentStatus): Undo =
        when (shown) {
            null, outcome -> Undo.REVERT
            AppointmentStatus.SCHEDULED -> Undo.NOTHING
            else -> Undo.CHANGED_ELSEWHERE
        }

    /**
     * Today's [list] with [card] back on it as Scheduled, where the list has it by its start time,
     * as the server would answer once it is Scheduled again: an overdue Scheduled Appointment is
     * always on it. A list that has it already is left as it is.
     */
    fun withCardBack(list: List<Appointment>, card: Appointment): List<Appointment> =
        if (list.any { it.id == card.id }) {
            list
        } else {
            val start = Instant.parse(card.startsAt)
            val at = list.indexOfFirst { Instant.parse(it.startsAt).isAfter(start) }.let { if (it < 0) list.size else it }

            list.take(at) + card.copy(status = AppointmentStatus.SCHEDULED) + list.drop(at)
        }
}
