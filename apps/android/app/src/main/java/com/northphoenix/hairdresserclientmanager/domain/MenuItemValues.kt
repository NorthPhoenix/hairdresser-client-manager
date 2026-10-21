package com.northphoenix.hairdresserclientmanager.domain

/**
 * What a save of the Service Menu Item editor sends: the whole editor at once. [name] and [group]
 * are one line each, and an empty [group] is no group. [travelFee] makes the item the Travel Fee,
 * and any other item of the Stylist stops being it.
 */
data class MenuItemValues(val name: String, val defaultPriceCents: Long, val group: String, val travelFee: Boolean = false) {
    companion object {
        /** The editor's fields as they are saved, or null while the name has nothing to show. The price is read by the editor. */
        fun of(name: String, defaultPriceCents: Long, group: String, travelFee: Boolean = false): MenuItemValues? =
            if (OneLine.hasVisibleCharacter(name)) MenuItemValues(OneLine.clean(name), defaultPriceCents, OneLine.clean(group), travelFee) else null
    }
}
