package com.northphoenix.hairdresserclientmanager.domain

/**
 * What a save of the Service Menu Item editor sends: the whole editor at once. [name] and [group]
 * are one line each, and an empty [group] is no group.
 */
data class MenuItemValues(val name: String, val defaultPriceCents: Long, val group: String) {
    companion object {
        /** The editor's fields as they are saved, or null while the name has nothing to show. The price is read by the editor. */
        fun of(name: String, defaultPriceCents: Long, group: String): MenuItemValues? =
            if (OneLine.hasVisibleCharacter(name)) MenuItemValues(OneLine.clean(name), defaultPriceCents, OneLine.clean(group)) else null
    }
}
