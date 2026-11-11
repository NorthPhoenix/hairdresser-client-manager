package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry

/**
 * The money of a visit in a Client Profile's history, as a receipt: what the visit came to
 * ([totalCents]) is the price of the Services (the custom total when the Stylist set one, the sum
 * of the Service prices otherwise) and the Tip, as every amount the app shows for an Appointment.
 * Of a group Appointment it is the whole visit's, as its custom total and Tip are, though the
 * history lists only this Client's Services.
 *
 * The receipt adds up: this Client's Services, the other Clients' Services of a group Appointment
 * ([othersCents]), the difference the custom total makes ([customPriceDifferenceCents]) and the
 * Tip ([tipCents]) come to [totalCents].
 */
object HistoryTotals {
    /** The sum of the Service prices of the whole visit; from an older server, of the Services listed. */
    fun serviceSumCents(entry: ClientHistoryEntry): Long = entry.serviceTotalCents ?: entry.services.sumOf { it.priceCents }

    /** The price of the Services: the custom total when there is one, their sum otherwise. */
    fun servicesPriceCents(entry: ClientHistoryEntry): Long = entry.finalTotalCentsOverride ?: serviceSumCents(entry)

    /** What the visit came to: the price of the Services and the Tip. */
    fun totalCents(entry: ClientHistoryEntry): Long = servicesPriceCents(entry) + (tipCents(entry) ?: 0L)

    /** Whether the visit had other Clients too: an Appointment with several Clients. */
    fun isWholeVisit(entry: ClientHistoryEntry): Boolean = entry.participantClientIds.size > 1

    /** The Tip, when there is one: 0 is none, as everywhere in the app. */
    fun tipCents(entry: ClientHistoryEntry): Long? = entry.tipCents?.takeIf { it > 0 }

    /** How many other Clients a group Appointment has; 0 for one with this Client alone. */
    fun otherClients(entry: ClientHistoryEntry): Int = (entry.participantClientIds.size - 1).coerceAtLeast(0)

    /**
     * What the other Clients' Services of a group Appointment come to, at their own prices; null
     * when there is nothing of theirs, or the server does not say (an older one).
     */
    fun othersCents(entry: ClientHistoryEntry): Long? {
        val wholeVisit = entry.serviceTotalCents ?: return null

        if (!isWholeVisit(entry)) return null

        return (wholeVisit - entry.services.sumOf { it.priceCents }).takeIf { it > 0 }
    }

    /**
     * What the custom total takes off the sum of the Service prices (negative) or adds to it
     * (positive). Null when there is no custom total, it is the sum itself, there is no sum to
     * compare it with (no Services yet), or the sum of a group Appointment is not known (an older
     * server).
     */
    fun customPriceDifferenceCents(entry: ClientHistoryEntry): Long? {
        val customPrice = entry.finalTotalCentsOverride ?: return null

        if (isWholeVisit(entry) && entry.serviceTotalCents == null) return null
        if (serviceSumCents(entry) == 0L) return null

        return (customPrice - serviceSumCents(entry)).takeIf { it != 0L }
    }

    /** Whether the visit was charged: a canceled or no-show visit's amount is only what it would have come to. */
    fun wasCharged(entry: ClientHistoryEntry): Boolean = entry.status != AppointmentStatus.CANCELED && entry.status != AppointmentStatus.NO_SHOW

    /** Whether the visit is still to come: booked, not yet charged. */
    fun isUpcoming(entry: ClientHistoryEntry): Boolean = entry.status == AppointmentStatus.SCHEDULED

    /**
     * Whether the receipt ends in a total: a visit that was or will be charged, once it has
     * something to pay, a price of the Services (the Stylist's own or theirs) or a Tip. Services
     * all at no price, with nothing set, have nothing to add up.
     */
    fun showsTotal(entry: ClientHistoryEntry): Boolean =
        wasCharged(entry) && (entry.finalTotalCentsOverride != null || serviceSumCents(entry) > 0 || tipCents(entry) != null)
}
