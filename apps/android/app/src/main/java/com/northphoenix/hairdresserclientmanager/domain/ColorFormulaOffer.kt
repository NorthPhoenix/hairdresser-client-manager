package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem

/**
 * How loudly a Service on an Appointment offers to add a Color Formula. A haircut or a travel fee
 * hardly ever has one, so only a Service that already has a Color Formula, or whose name looks like
 * colour work, gets the full button; every other Service keeps a quiet one, so that a formula can
 * still be added to any Service.
 *
 * The word list does not know every kind of colour work the Stylist may add, so the offer learns
 * from her own formulas ([Learned]): a Service started from a Service Menu Item that has had a
 * Color Formula on any Service, or an ad-hoc Service (no menu item) named like one that had a
 * formula, gets the full button too. Adding one formula to a Service is enough.
 */
object ColorFormulaOffer {
    /** Parts of words that name colour work, in Russian and English, compared without letter case. */
    private val colorWords = listOf(
        "краск", "окраш", "мелир", "обесцвеч", "тонир", "балаяж", "шатуш", "омбре", "колор",
        "colo", "highlight", "bleach", "toner", "balayage", "ombre", "dye",
    )

    /**
     * What the Stylist's own formulas say: the menu items that have had a Color Formula on a
     * Service ([menuItemIds], of the items in the menu, [menuIds]), and the names, folded by [fold], of Services that had one. Both come from the server
     * (`serviceMenu.list` and `serviceMenu.formulaNames`) and may be a little behind.
     */
    class Learned(private val menuItemIds: Set<String>, private val menuIds: Set<String>, private val names: Set<String>) {
        fun knows(service: AppointmentService): Boolean {
            val menuItemId = service.menuItemId

            // A Service from a menu item is learned by its item. A name is for one without an item, also
            // one whose item has been deleted from the menu (the Service keeps the id of its item).
            return if (menuItemId != null && menuItemId in menuIds) menuItemId in menuItemIds else fold(service.name) in names
        }

        companion object {
            val NONE = Learned(emptySet(), emptySet(), emptySet())

            fun of(menu: List<ServiceMenuItem>, formulaNames: List<String>): Learned = Learned(
                menuItemIds = menu.filter { it.formulaCount > 0 }.mapTo(HashSet()) { it.id },
                menuIds = menu.mapTo(HashSet()) { it.id },
                names = formulaNames.map(::fold).filter { it.isNotEmpty() }.toSet(),
            )
        }
    }

    fun isProminent(service: AppointmentService, learned: Learned = Learned.NONE): Boolean =
        service.colorFormulas.isNotEmpty() || looksLikeColor(service.name) || learned.knows(service)

    /** A name without the difference of letter case, of "ё" and "е", and of repeated or outer spaces. */
    fun fold(name: String): String = name.lowercase().replace('ё', 'е').trim().replace(Regex("\\s+"), " ")

    fun looksLikeColor(name: String): Boolean {
        val lower = name.lowercase().replace('ё', 'е')

        return colorWords.any { it in lower }
    }
}
