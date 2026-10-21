package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem

/**
 * What the add-service sheet has picked from the Service Menu, per person: the ids of the items
 * ticked for each Client, by their id, in the order they were tapped.
 *
 * Each person keeps their own ticks: the chip under "For whom" only decides whose ticks are shown
 * and changed, so switching it never moves or loses any. The sheet adds the ticks of everyone at
 * once ([toAdd]).
 */
object ServicePicks {
    /** [picks] with [itemId] ticked or unticked for [clientId]; the ticks of the others stay. An item that left [menu] is not picked any more. */
    fun toggle(picks: Map<String, List<String>>, menu: List<ServiceMenuItem>, clientId: String, itemId: String): Map<String, List<String>> {
        val ticked = MenuPicker.toggle(picks[clientId].orEmpty().filter { picked -> menu.any { it.id == picked } }, itemId)

        return if (ticked.isEmpty()) picks - clientId else picks + (clientId to ticked)
    }

    /** The items ticked for [clientId], in the order they were tapped, of those still in [menu]. */
    fun of(picks: Map<String, List<String>>, menu: List<ServiceMenuItem>, clientId: String): List<ServiceMenuItem> =
        MenuPicker.picked(menu, picks[clientId].orEmpty())

    /**
     * What to add: the people of [clientOrder] (the order of their chips) who have ticks, each with
     * their items in the order they were tapped. Ticks of someone who is no longer listed, and
     * items no longer in [menu], are left out.
     */
    fun toAdd(picks: Map<String, List<String>>, menu: List<ServiceMenuItem>, clientOrder: List<String>): List<ForClient> =
        clientOrder.distinct().mapNotNull { clientId -> of(picks, menu, clientId).takeIf { it.isNotEmpty() }?.let { ForClient(clientId, it) } }

    /** The Services to add for one Client. */
    data class ForClient(val clientId: String, val items: List<ServiceMenuItem>)
}
