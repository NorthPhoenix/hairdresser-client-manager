package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry

/**
 * The money of a visit in a Client Profile's history. What the history leads with is the price of
 * the Services ([servicesPriceCents]): the custom total when the Stylist set one, the sum of the
 * Service prices otherwise. It is what the Client was charged and what the Stylist quotes again;
 * the Tip is the Client's own, on top of it. Of a group Appointment it is the whole visit's, as its
 * custom total and Tip are, though the history lists only this Client's Services.
 *
 * It adds up: this Client's Services and the other Clients' Services of a group Appointment
 * ([othersCents]) come to the sum ([serviceSumCents]); the difference the custom total makes
 * ([customPriceDifferenceCents]) to the price of the Services; and the Tip to [totalCents].
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

    /**
     * Whether the price at the top is of the whole of a group visit: its custom total is, and so is
     * the sum once the server says what the whole visit's Services come to (an older one does not,
     * and the sum is then of this Client's Services alone).
     */
    fun priceIsWholeVisit(entry: ClientHistoryEntry): Boolean =
        isWholeVisit(entry) && (entry.serviceTotalCents != null || entry.finalTotalCentsOverride != null)

    /** The Tip on top of the price, when there is one: 0 is none, as everywhere in the app. */
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
     * Whether the row leads with the price of the Services: a visit that was or will be charged,
     * once its Services have a price, the Stylist's own or theirs. Services all at $0 and none set,
     * or a Tip alone, have no price to lead with.
     */
    fun showsPrice(entry: ClientHistoryEntry): Boolean =
        wasCharged(entry) && (entry.finalTotalCentsOverride != null || serviceSumCents(entry) > 0)

    /** Whether the Stylist made the Services free: a custom total of 0 for Services that have a price. */
    fun isFree(entry: ClientHistoryEntry): Boolean = entry.finalTotalCentsOverride == 0L && serviceSumCents(entry) > 0

    /**
     * Whether a Service's own price would only say the price at the top again: the visit's one
     * Service, at its own price, with no other Client's Services beside it.
     */
    fun servicePriceRepeatsPrice(entry: ClientHistoryEntry): Boolean =
        showsPrice(entry) && entry.services.size == 1 && entry.finalTotalCentsOverride == null && othersCents(entry) == null
}
