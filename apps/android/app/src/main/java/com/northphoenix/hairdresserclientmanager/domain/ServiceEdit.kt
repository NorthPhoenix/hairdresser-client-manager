package com.northphoenix.hairdresserclientmanager.domain

/**
 * An edit of a Service on an Appointment. It is a save of the whole form: the name, the price and
 * the note go to the server together, so each has to be one the server takes.
 */
object ServiceEdit {
    /** What a save sends: the name as one line, the price in whole cents and the note, which is empty to clear it. */
    data class Values(val name: String, val priceCents: Long, val note: String)

    /** The server's `Name is required.` rule. */
    fun isNameRefused(name: String): Boolean = !OneLine.hasVisibleCharacter(name)

    /** A Service records its actual price, so an edit has to state one, as an ad hoc Service does. Zero is a price. */
    fun isPriceRefused(price: String): Boolean = Money.parse(price).isRefused()

    /** What a save of the form sends, or null while its name or its price is refused. */
    fun values(name: String, price: String, note: String): Values? {
        val amount = Money.parse(price) as? Amount.Valid

        return if (amount == null || isNameRefused(name)) null else Values(OneLine.clean(name), amount.cents, note.trim())
    }
}
