package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment

/**
 * What an Appointment comes to, with its Tip. The price of the Services is the custom total when
 * the Stylist set one, the sum of the Service prices otherwise; the Tip is apart from it, and the
 * total is the two together. Every amount the app shows for an Appointment is [totalCents], so a
 * card and the Appointment screen never disagree.
 */
object AppointmentTotals {
    /** The tips offered as one tap, in cents: $5, $10, $15, $20. Any other amount is typed. */
    val TIP_CHOICES: List<Long> = listOf(500L, 1000L, 1500L, 2000L)

    /** The price of the Services: the custom total when there is one, their sum otherwise. */
    fun servicesPriceCents(appointment: Appointment): Long = appointment.finalTotalCentsOverride ?: appointment.serviceTotalCents

    /** The Tip, 0 when none is recorded. */
    fun tipCents(appointment: Appointment): Long = appointment.tipCents ?: 0L

    /** What the visit comes to: the price of the Services and the Tip. */
    fun totalCents(appointment: Appointment): Long = servicesPriceCents(appointment) + tipCents(appointment)

    /** Whether a list shows an amount for it: once it has a Service, a price or a Tip. */
    fun hasAmount(appointment: Appointment): Boolean = appointment.services.isNotEmpty() || totalCents(appointment) > 0

    /** Whether [tipCents] is one of the [TIP_CHOICES]. No tip is not: it has a chip of its own. */
    fun isTipChoice(tipCents: Long?): Boolean = tipCents != null && tipCents in TIP_CHOICES

    /**
     * The Appointment with [tipCents] as its Tip; null or 0 records none, so "no tip" is one value
     * on every screen and in the database.
     */
    fun withTip(appointment: Appointment, tipCents: Long?): Appointment = appointment.copy(tipCents = tipCents?.takeIf { it > 0 })

    /**
     * The difference a custom total above the Services comes to, offered to the Stylist as the Tip
     * it most likely is: before there were tips she recorded them that way. Null when there is
     * nothing to offer: no custom total, one at or below the sum, no Services to be above (then
     * the custom total is the whole price), or a Tip that is recorded already.
     */
    fun tipSuggestionCents(appointment: Appointment): Long? {
        val override = appointment.finalTotalCentsOverride ?: return null

        if (appointment.tipCents != null || appointment.services.isEmpty() || appointment.serviceTotalCents <= 0) {
            return null
        }

        return (override - appointment.serviceTotalCents).takeIf { it > 0 }
    }

    /**
     * What "Yes" to [tipSuggestionCents] makes of the Appointment: the difference is the Tip and
     * the custom total is gone, so the Services are at their own prices and the total is the same
     * as before. Unchanged when there is nothing to offer.
     */
    fun withSuggestedTip(appointment: Appointment): Appointment {
        val tip = tipSuggestionCents(appointment) ?: return appointment

        return appointment.copy(finalTotalCentsOverride = null, finalTotalCents = appointment.serviceTotalCents, tipCents = tip)
    }
}
