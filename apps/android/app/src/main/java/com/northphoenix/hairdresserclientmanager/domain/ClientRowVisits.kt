package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ClientVisitDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The line a Client's row in the Clients View gives their visits: their next Appointment when
 * they have one ahead, else how long ago their last visit was, else that they have had none. As
 * the Client Profile counts them: a visit is a Completed Appointment, and the next Appointment is
 * the first Scheduled one that has not started. Tested in `ClientRowVisitsTest`.
 */
object ClientRowVisits {
    enum class Day { TODAY, TOMORROW, LATER }

    sealed interface Line {
        /** The next Appointment starts at [startsAt], on [date] in the Stylist's timezone. */
        data class Next(val day: Day, val date: LocalDate, val startsAt: String) : Line

        /** The last visit was [ago] (0 days: today, 1 day: yesterday). */
        data class Last(val ago: ClientVisits.Span) : Line

        /** No visit yet and nothing ahead. */
        data object None : Line
    }

    /** The dates of every Client who has a visit or an Appointment ahead, by their id. */
    fun byClient(dates: List<ClientVisitDates>): Map<String, ClientVisitDates> = dates.associateBy { it.clientId }

    /**
     * The line for a Client with these [dates] (null: the server listed none for them), at [now] in
     * [zone]. An Appointment ahead that has started since the server answered is no longer ahead.
     */
    fun line(dates: ClientVisitDates?, now: Instant, zone: ZoneId): Line {
        val today = now.atZone(zone).toLocalDate()

        dates?.nextAppointmentAt?.let(::parse)?.takeIf { it.isAfter(now) }?.let { start ->
            val date = start.atZone(zone).toLocalDate()
            val day = when (date) {
                today -> Day.TODAY
                today.plusDays(1) -> Day.TOMORROW
                else -> Day.LATER
            }

            return Line.Next(day, date, dates.nextAppointmentAt)
        }

        return dates?.lastVisitAt?.let(::parse)
            ?.let { Line.Last(ClientVisits.ago(it.atZone(zone).toLocalDate(), today)) }
            ?: Line.None
    }

    private fun parse(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()
}
