package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * What Appointment Creation, and the edit of an Appointment's time and place, work out from what
 * the Stylist chose. Tested in `AppointmentDraftTest`.
 */
object AppointmentDraft {
    /**
     * The start the form opens with: the next full hour after [now] (the Stylist's clock), which
     * late in the evening is midnight of the next day. A [day] chosen in the Calendar that is not
     * today keeps its date and takes that time of day.
     */
    fun defaultStart(now: ZonedDateTime, day: LocalDate?): LocalDateTime {
        val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)

        if (day == null || day == now.toLocalDate()) {
            return nextHour.toLocalDateTime()
        }

        // A time of day the clock skips on that day is moved past the gap, which is right for a time nobody chose yet.
        return LocalDateTime.of(day, nextHour.toLocalTime()).atZone(now.zone).toLocalDateTime()
    }

    /**
     * The longest an Appointment can be when its end time is read as the next day. Half a day tells
     * an Appointment that runs past midnight from a mistake in the times: an end picked as AM for
     * PM, or a start moved past the end by less than that, would be longer.
     */
    val longestOvernight: Duration = Duration.ofHours(12)

    /**
     * The day the end time is on. An Appointment can end after midnight: an end time before the
     * start time is on the day after [date], as long as the Appointment is then no longer than
     * [longestOvernight]. Null when it would be longer, or when the end is the start time: that is
     * a mistake, not a day-long Appointment, and the times cannot be changed once it is created.
     */
    fun endDate(date: LocalDate, start: LocalTime, end: LocalTime): LocalDate? = when {
        end.isAfter(start) -> date
        Duration.between(start, end).plusDays(1) <= longestOvernight -> date.plusDays(1)
        else -> null
    }

    sealed interface Times {
        /** What is sent. [endsAt] is always after [startsAt]. */
        data class Valid(val startsAt: Instant, val endsAt: Instant?) : Times

        /** The clocks go forward over the start time on its day, so it cannot be saved as chosen. */
        data object StartSkipped : Times

        /** The same for the end time, on the day it falls on. */
        data object EndSkipped : Times

        /** The end time is on neither the day nor the next one: see [endDate]. */
        data object EndNotAfterStart : Times
    }

    /** The chosen day and times as instants in the Stylist's timezone, or what is wrong with the times. */
    fun times(date: LocalDate, start: LocalTime, end: LocalTime?, format: TimeFormat): Times {
        val startsAt = format.toInstant(date, start) ?: return Times.StartSkipped
        val endsAt = end?.let {
            val endDay = endDate(date, start, it) ?: return Times.EndNotAfterStart

            format.toInstant(endDay, it) ?: return Times.EndSkipped
        }

        return Times.Valid(startsAt, endsAt)
    }

    /**
     * The at-home address field shows the Stylist's own address once they have typed one, and
     * until then the address of the primary Client, whoever that is at the moment.
     */
    fun shownAddress(typed: String?, primaryClientAddress: String?): String = typed ?: primaryClientAddress.orEmpty()

    /**
     * The Stylist's own at-home address in what they [typed], or null when the Appointment takes
     * its primary Client's address: nothing was typed, the field was emptied, or it holds that
     * Client's address unchanged. Asked when the form is sent, and whenever the primary Client or
     * the location is changed, so that an address that was never the Stylist's own does not stay
     * for the next primary Client.
     */
    fun ownAddress(typed: String?, primaryClientAddress: String?): String? =
        typed?.trim()?.takeUnless { it.isEmpty() || it == primaryClientAddress.orEmpty().trim() }

    /**
     * The time and place an Appointment has, as the edit form opens with them: its day and times in
     * the Stylist's timezone. [ownAddress] is the at-home address the Stylist typed, null when the
     * Appointment is at its primary Client's address or in the salon. [hasOutcome] is true for an
     * Appointment that is Completed, Canceled or a No-show.
     */
    data class Saved(
        val date: LocalDate,
        val startTime: LocalTime,
        val endDate: LocalDate?,
        val endTime: LocalTime?,
        val locationType: LocationType,
        val ownAddress: String?,
        val hasOutcome: Boolean,
    )

    fun saved(appointment: Appointment, format: TimeFormat): Saved {
        val start = format.zoned(appointment.startsAt)
        val end = appointment.endsAt?.let(format::zoned)

        return Saved(
            date = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            locationType = appointment.locationType,
            ownAddress = appointment.customLocationAddress.takeIf { it.isNotEmpty() },
            hasOutcome = appointment.status != AppointmentStatus.SCHEDULED,
        )
    }

    /**
     * Whether an edit leaves the place of the Appointment as it is, so that it is not sent. An
     * Appointment with an outcome keeps the address it had until the Stylist changes the place in
     * the form: sent again, a place at the primary Client's address would become the address that
     * Client has today, with a save that was only about the time. A Scheduled Appointment sends its
     * place with every save, which brings it the address its salon or its primary Client has now.
     * [typed] is what the address field holds, as in [shownAddress].
     */
    fun keepsPlace(saved: Saved, locationType: LocationType, typed: String?): Boolean =
        saved.hasOutcome && locationType == saved.locationType && (locationType == LocationType.IN_SALON || typed == saved.ownAddress)

    private fun Saved.hasTimes(date: LocalDate, start: LocalTime, end: LocalTime?): Boolean =
        date == this.date && start == startTime && end == endTime

    /**
     * What an edit sends for the times, or what is wrong with them. Null while the day and both
     * times are the [saved] ones: they are then not sent, and the Appointment keeps exactly the
     * moments it has. Worked out again from the form they could come out as other moments: a start
     * in the hour the clocks repeat, or an end the form does not offer. Anything the Stylist
     * changed goes by the rules of Appointment Creation ([times]).
     */
    fun editedTimes(saved: Saved, date: LocalDate, start: LocalTime, end: LocalTime?, format: TimeFormat): Times? =
        if (saved.hasTimes(date, start, end)) null else times(date, start, end, format)

    /** The day the edit form names for the end time: the saved one while the times are the saved ones, else as in [endDate]. */
    fun editedEndDate(saved: Saved, date: LocalDate, start: LocalTime, end: LocalTime?): LocalDate? =
        if (saved.hasTimes(date, start, end)) saved.endDate else end?.let { endDate(date, start, it) }
}
