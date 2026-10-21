package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.LocationType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * What the Client Profile says about a Client's visits, worked out from their Appointment history:
 * how many there were, when the last one was, how often they come, the next Appointment, and
 * where to book the next one. A visit is a Completed Appointment: canceled ones, no-shows and
 * Scheduled ones are not visits. Tested in `ClientVisitsTest`.
 */
object ClientVisits {
    enum class SpanUnit { DAYS, WEEKS, MONTHS, YEARS }

    /** A length of time as the profile says it: "7 weeks", "3 months". */
    data class Span(val unit: SpanUnit, val count: Int)

    data class Summary(
        /** The number of Completed Appointments. */
        val visits: Int,
        /** The day of the latest Completed Appointment in the Stylist's timezone. Null: none yet. */
        val lastVisit: LocalDate?,
        /** How long the Client usually goes between visits. Null with fewer than two visit days. */
        val usualInterval: Span?,
        /** The first Scheduled Appointment that has not started yet. */
        val next: ClientHistoryEntry?,
    )

    fun summary(entries: List<ClientHistoryEntry>, now: Instant, zone: ZoneId): Summary {
        val completed = entries.filter { it.status == AppointmentStatus.COMPLETED }
        // Two Appointments on one day (a family, say) are one gap of no length, not how often the Client comes.
        val days = completed.map { Instant.parse(it.startsAt).atZone(zone).toLocalDate() }.distinct().sorted()

        return Summary(
            visits = completed.size,
            lastVisit = days.lastOrNull(),
            usualInterval = median(days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) })?.let(::interval),
            next = entries
                .filter { it.status == AppointmentStatus.SCHEDULED && Instant.parse(it.startsAt).isAfter(now) }
                .minByOrNull { Instant.parse(it.startsAt) },
        )
    }

    /**
     * How long ago [lastVisit] was on [today]: in days up to two weeks (0 is today, 1 yesterday),
     * then in whole weeks up to nine weeks, then in whole months, then in whole years. A visit
     * after today (one marked completed early) is today.
     */
    fun ago(lastVisit: LocalDate, today: LocalDate): Span {
        val days = ChronoUnit.DAYS.between(lastVisit, today).coerceAtLeast(0)

        return when {
            days < 14 -> Span(SpanUnit.DAYS, days.toInt())
            days < 63 -> Span(SpanUnit.WEEKS, (days / 7).toInt())
            days < 365 -> Span(SpanUnit.MONTHS, ChronoUnit.MONTHS.between(lastVisit, today).toInt().coerceIn(2, 11))
            else -> Span(SpanUnit.YEARS, ChronoUnit.YEARS.between(lastVisit, today).toInt().coerceAtLeast(1))
        }
    }

    /**
     * A usual gap of [days] between visits, rounded to what one would say: days under two weeks,
     * weeks under thirteen weeks, months under a year and a half, years after that.
     */
    fun interval(days: Double): Span = when {
        days < 14 -> Span(SpanUnit.DAYS, days.roundToInt().coerceAtLeast(1))
        days < 91 -> Span(SpanUnit.WEEKS, (days / 7).roundToInt())
        days < 548 -> Span(SpanUnit.MONTHS, (days / DAYS_PER_MONTH).roundToInt())
        else -> Span(SpanUnit.YEARS, (days / DAYS_PER_YEAR).roundToInt())
    }

    /**
     * Where "Book again" opens the next Appointment: at the place of the Client's last visit. An
     * at-home visit at an address the Stylist typed is booked there again; one at the Client's own
     * address takes the address the Client has now (null). One where another Client was the primary
     * Client (at their address) is booked at that address, typed. Null when there is no visit to
     * go by: the form opens as it always does.
     */
    data class Rebooking(val locationType: LocationType, val address: String?)

    fun rebooking(entries: List<ClientHistoryEntry>, clientId: String): Rebooking? {
        val last = entries.filter { it.status == AppointmentStatus.COMPLETED }.maxByOrNull { Instant.parse(it.startsAt) } ?: return null

        return when (last.locationType ?: return null) {
            LocationType.IN_SALON -> Rebooking(LocationType.IN_SALON, null)
            LocationType.AT_HOME -> Rebooking(
                LocationType.AT_HOME,
                when {
                    last.customLocationAddress.isNotBlank() -> last.customLocationAddress
                    last.primaryClientId == null || last.primaryClientId == clientId -> null
                    else -> last.locationAddress.takeIf { it.isNotBlank() }
                },
            )
        }
    }

    private fun median(values: List<Long>): Double? {
        if (values.isEmpty()) {
            return null
        }

        val sorted = values.sorted()
        val middle = sorted.size / 2

        return if (sorted.size % 2 == 1) sorted[middle].toDouble() else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private const val DAYS_PER_MONTH = 30.44
    private const val DAYS_PER_YEAR = 365.25
}
