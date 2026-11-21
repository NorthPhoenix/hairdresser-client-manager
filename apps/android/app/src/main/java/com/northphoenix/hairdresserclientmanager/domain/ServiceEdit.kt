package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem

/**
 * An edit of a Service on an Appointment. It is a save of the whole form: the name, the price and
 * the note go to the server together, so each has to be one the server takes. A Service started
 * from an item of a group can also be switched to another variant of that group (see
 * [MenuPicker.variants]): it keeps its note and Color Formulas, and counts as started from the new item.
 */
object ServiceEdit {
    /**
     * What a save sends: the name as one line, the price in whole cents and the note, which is
     * empty to clear it. [menuItemId] is the Service Menu Item the Service is switched to, or null
     * when it keeps its own.
     */
    data class Values(val name: String, val priceCents: Long, val note: String, val menuItemId: String? = null)

    /** The server's `Name is required.` rule. */
    fun isNameRefused(name: String): Boolean = !OneLine.hasVisibleCharacter(name)

    /** A Service records its actual price, so an edit has to state one, as an ad hoc Service does. Zero is a price. */
    fun isPriceRefused(price: String): Boolean = Money.parse(price).isRefused()

    /**
     * What a save of the form sends, or null while its name or its price is refused. [pickedItemId]
     * is the variant picked in the form and [ownItemId] the Service's own item: only another one is sent.
     */
    fun values(name: String, price: String, note: String, pickedItemId: String? = null, ownItemId: String? = null): Values? {
        val amount = Money.parse(price) as? Amount.Valid

        return if (amount == null || isNameRefused(name)) {
            null
        } else {
            Values(OneLine.clean(name), amount.cents, note.trim(), pickedItemId?.takeIf { it != ownItemId })
        }
    }

    /** What the form holds once the variant [item] is picked: its name and its default price, as the price field writes it. */
    fun picked(item: ServiceMenuItem): Pair<String, String> = item.name to Money.centsToPrice(item.defaultPriceCents)
}
