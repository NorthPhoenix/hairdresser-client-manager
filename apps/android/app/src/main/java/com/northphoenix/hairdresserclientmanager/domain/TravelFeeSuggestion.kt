package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem

/**
 * "Is “Выезд на дом” your travel fee?" on an Appointment at home. A Stylist who has a Service Menu
 * Item for going to a Client but has not marked it as the Travel Fee adds it by hand to every
 * Appointment at home. When she has just done that, the Appointment asks whether that item is the
 * Travel Fee; "Yes" marks it, as the item's editor in the Service Menu does, and from then on it is
 * added by itself.
 *
 * Only an item named like a travel fee ([looksLikeTravelFee]) is offered: a haircut done at home is
 * not one. Never while an item of the menu is the Travel Fee, nor for an item she said "No" to.
 */
object TravelFeeSuggestion {
    /**
     * Whole words, compared without letter case and the difference of "ё" and "е", that name a
     * charge for going to a Client: "Выезд на дом", "Оплата выезда", "Travel fee", "Trip charge".
     * Whole words, so that "Выездная стрижка" (a haircut at home) and "Stripping" are not.
     */
    private val travelWords = setOf(
        "выезд", "выезда", "выезду", "выездом", "выезде", "дорога", "транспорт",
        "travel", "traveling", "travelling", "trip", "mileage", "callout",
    )

    /** Words are the runs of letters and digits; everything else separates them. */
    private val separator = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The menu item to ask about on [appointment], or null for none: one of [menu] that a Service
     * of the Appointment was started from, named like a travel fee, and not among [refused] (the ids
     * of the items she said "No" to). Null for an Appointment in the salon and while any item of the
     * menu is the Travel Fee. The first such Service's item when there are several.
     */
    fun item(appointment: Appointment, menu: List<ServiceMenuItem>, refused: Set<String>): ServiceMenuItem? {
        if (appointment.locationType != LocationType.AT_HOME || menu.any { it.travelFee }) {
            return null
        }

        val byId = menu.associateBy { it.id }

        return appointment.services
            .asSequence()
            .mapNotNull { service -> service.menuItemId?.let(byId::get) }
            .firstOrNull { it.id !in refused && looksLikeTravelFee(it.name) }
    }

    fun looksLikeTravelFee(name: String): Boolean =
        name.lowercase().replace('ё', 'е').split(separator).any { it in travelWords }
}
