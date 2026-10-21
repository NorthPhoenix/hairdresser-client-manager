package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem

/**
 * How the Service Menu is laid out where the Stylist picks from it (the add-service sheet) and
 * where she keeps it (Settings → Service menu): its groups, the label of an item inside its group,
 * search, and the items she uses most.
 *
 * The menu comes from the server in the order its items were created, oldest first, and keeps that
 * order here: within a group, among the groups (each where its oldest item is), and among the items
 * without a group.
 */
object MenuPicker {
    /** How many items the row of the most used ones holds. */
    const val FREQUENT_LIMIT = 4

    /** A group of the menu: [name] as the oldest of its [items] has it. */
    data class Group(val key: String, val name: String, val items: List<ServiceMenuItem>) {
        val lowestPriceCents: Long get() = items.minOf { it.defaultPriceCents }
        val highestPriceCents: Long get() = items.maxOf { it.defaultPriceCents }
    }

    /**
     * The menu in groups. [ungrouped] are the items without a group, after the groups. A menu
     * without any group has only [ungrouped], which is then shown as a plain list.
     */
    data class Layout(val groups: List<Group>, val ungrouped: List<ServiceMenuItem>) {
        val hasGroups: Boolean get() = groups.isNotEmpty()

        val isEmpty: Boolean get() = groups.isEmpty() && ungrouped.isEmpty()
    }

    fun layout(items: List<ServiceMenuItem>): Layout {
        val grouped = items.filter { groupKey(it.group) != null }.groupBy { groupKey(it.group)!! }

        return Layout(
            groups = grouped.map { (key, members) -> Group(key, members.first().group!!.trim().collapseSpaces(), members) },
            ungrouped = items.filter { groupKey(it.group) == null },
        )
    }

    /**
     * What makes two groups one: their names without the difference of letter case, of "ё" and
     * "е", and of repeated spaces. Null for an item without a group.
     */
    fun groupKey(group: String?): String? = group?.let(::fold)?.takeIf { it.isNotEmpty() }

    /**
     * What a chip shows for [item] inside its group: its name without the group's name at the
     * start, with a capital first letter, so "Покраска в один цвет короткие" in the group "Покраска
     * в один цвет" is "Короткие". The full name when the name does not start with the group's name
     * as a whole word, or is nothing more than it.
     */
    fun variantLabel(item: ServiceMenuItem): String {
        val name = item.name.trim().collapseSpaces()
        val group = item.group?.trim()?.collapseSpaces().orEmpty()

        if (group.isEmpty() || name.length <= group.length) {
            return name
        }

        val startsWithGroup = name.indices.take(group.length).all { foldChar(name[it]) == foldChar(group[it]) }
        // "Мелирование 1" is not a variant of "Мел": the group's name has to end where a word ends.
        if (!startsWithGroup || name[group.length - 1].isLetterOrDigit() && name[group.length].isLetterOrDigit()) {
            return name
        }

        val rest = name.substring(group.length).trimStart { it.isWhitespace() || it in SEPARATORS }

        return if (rest.isEmpty()) name else rest.replaceFirstChar { it.titlecase() }
    }

    /**
     * Whether [item] is found by [query]: each word of it is in the item's name or its group's name,
     * without the difference of letter case or of "ё" and "е". An empty query finds everything.
     */
    fun matches(item: ServiceMenuItem, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        val text = fold(listOfNotNull(item.group, item.name).joinToString(" "))

        return words.all { it in text }
    }

    /** [layout] of the items that [matches] finds. */
    fun search(items: List<ServiceMenuItem>, query: String): Layout = layout(items.filter { matches(it, query) })

    /**
     * The items the Stylist has used most, most used first: up to [FREQUENT_LIMIT] of those she
     * has started a Service from at all. Of two used as often, the older one comes first. The
     * Travel Fee is not among them: it is added by itself, so its uses say nothing of her picks.
     */
    fun frequent(items: List<ServiceMenuItem>): List<ServiceMenuItem> =
        items.filter { it.useCount > 0 && !it.travelFee }.sortedByDescending { it.useCount }.take(FREQUENT_LIMIT)

    /**
     * The groups of the menu to offer while a group is typed in the item editor: each once, in the
     * menu's order, those that contain what is typed, but not the one that is typed exactly.
     */
    fun groupSuggestions(items: List<ServiceMenuItem>, typed: String): List<String> {
        val typedKey = fold(typed)

        return layout(items).groups.map { it.name }.filter { fold(it) != typedKey && typedKey in fold(it) }
    }

    /** The picked items, in the order picked, with their total at their default prices. Items no longer in the menu are left out. */
    fun picked(menu: List<ServiceMenuItem>, pickedIds: List<String>): List<ServiceMenuItem> =
        pickedIds.mapNotNull { id -> menu.firstOrNull { it.id == id } }

    /** [pickedIds] after a tap on the chip of [id]: picked when it was not, no longer picked when it was. */
    fun toggle(pickedIds: List<String>, id: String): List<String> = if (id in pickedIds) pickedIds - id else pickedIds + id

    // Characters that part a group's name from what follows it: "Стрижка, мужская", "Стрижка – детская".
    private const val SEPARATORS = ",.;:-–—·/|"

    private fun String.collapseSpaces() = split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun fold(text: String) = text.trim().collapseSpaces().map(::foldChar).joinToString("")

    private fun foldChar(char: Char) = when (val lower = char.lowercaseChar()) {
        'ё' -> 'е'
        else -> lower
    }
}
