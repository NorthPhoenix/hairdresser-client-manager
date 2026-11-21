package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppointmentService
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
     * What a save of the form sends, or null while its name or its price is refused. [switchTo] is
     * the variant the Service is switched to (see [switchTo]), null to keep its own item.
     */
    fun values(name: String, price: String, note: String, switchTo: String? = null): Values? {
        val amount = Money.parse(price) as? Amount.Valid

        return if (amount == null || isNameRefused(name)) null else Values(OneLine.clean(name), amount.cents, note.trim(), switchTo)
    }

    /**
     * The item a save switches [service] to: [pickedItemId], the variant picked in the form, when
     * it is another than the Service's own and still one of its [variants]. A variant that left the
     * menu while the form was open is not sent, so the form can still be saved.
     */
    fun switchTo(pickedItemId: String?, service: AppointmentService, variants: List<ServiceMenuItem>): String? =
        pickedItemId?.takeIf { id -> id != service.menuItemId && variants.any { it.id == id } }

    /**
     * What the form holds, as name and price text, once the variant [item] is picked: its name and
     * its default price. The Service's own item puts back the Service's own name and price, so
     * picking it again undoes another pick and never loses a price that was set by hand.
     */
    fun picked(item: ServiceMenuItem, service: AppointmentService): Pair<String, String> =
        if (item.id == service.menuItemId) {
            service.name to Money.centsToPrice(service.priceCents)
        } else {
            item.name to Money.centsToPrice(item.defaultPriceCents)
        }
}
