package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.MenuItemValues
import com.northphoenix.hairdresserclientmanager.domain.MenuPicker
import com.northphoenix.hairdresserclientmanager.domain.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Service Menu where the Stylist picks from it and where she keeps it: groups, variant labels, search, most used. */
class MenuPickerTest {
    private var nextId = 0

    private fun item(name: String, group: String? = null, price: Long = 5_000, uses: Int = 0) =
        ServiceMenuItem(id = "item_${++nextId}", name = name, defaultPriceCents = price, group = group, useCount = uses)

    @Test
    fun `groups keep the menu's order, each where its oldest item is, and the items without a group come after them`() {
        val cut = item("Стрижка женская", "Стрижки")
        val wash = item("Мытьё головы")
        val oneColor = item("Покраска в один цвет короткие", "Покраска в один цвет")
        val cutMen = item("Стрижка мужская", "Стрижки")
        val travel = item("Выезд на дом")

        val layout = MenuPicker.layout(listOf(cut, wash, oneColor, cutMen, travel))

        assertTrue(layout.hasGroups)
        assertEquals(listOf("Стрижки", "Покраска в один цвет"), layout.groups.map { it.name })
        assertEquals(listOf(cut, cutMen), layout.groups[0].items)
        assertEquals(listOf(wash, travel), layout.ungrouped)
    }

    @Test
    fun `one group is one group however its name was typed`() {
        val first = item("Обесцвечивание корни", "Обесцвечивание")
        val second = item("Обесцвечивание длинные", "  обесцвечивание ")
        val third = item("Мелирование 1", "Мелирование,  колпачков объёма")
        val fourth = item("Мелирование 2", "мелирование, колпачков объема")

        val layout = MenuPicker.layout(listOf(first, second, third, fourth))

        // The name of a group is as its oldest item has it.
        assertEquals(listOf("Обесцвечивание", "Мелирование, колпачков объёма"), layout.groups.map { it.name })
        assertEquals(listOf(listOf(first, second), listOf(third, fourth)), layout.groups.map { it.items })
    }

    @Test
    fun `a menu without groups is one plain list, and an empty group is no group`() {
        val items = listOf(item("Стрижка"), item("Укладка", group = "   "), item("Балаяж", group = ""))
        val layout = MenuPicker.layout(items)

        assertFalse(layout.hasGroups)
        assertEquals(items, layout.ungrouped)
        assertTrue(MenuPicker.layout(emptyList()).isEmpty)
    }

    @Test
    fun `a group's price range is from its cheapest to its dearest item`() {
        val group = MenuPicker.layout(listOf(item("A x", "A", 7_000), item("A y", "A", 5_000), item("A z", "A", 8_500))).groups.single()

        assertEquals("$50–85", Money.formatRange(group.lowestPriceCents, group.highestPriceCents))
        assertEquals("$50", Money.formatRange(5_000, 5_000))
        assertEquals("$5.50–20", Money.formatRange(550, 2_000))
    }

    @Test
    fun `a price among many is short when it has no cents`() {
        assertEquals("$85", Money.formatShort(8_500))
        assertEquals("$85.50", Money.formatShort(8_550))
        assertEquals("$0", Money.formatShort(0))
    }

    @Test
    fun `inside its group an item is named by what it adds to the group's name`() {
        assertEquals("Короткие", MenuPicker.variantLabel(item("Покраска в один цвет короткие", "Покраска в один цвет")))
        // Letter case and repeated spaces do not count.
        assertEquals("Густые, супер длинные", MenuPicker.variantLabel(item("покраска  в один цвет густые, супер длинные", "Покраска в один цвет")))
        assertEquals("1", MenuPicker.variantLabel(item("Мелирование, колпачков объёма 1", "Мелирование, колпачков объёма")))
        // Neither do "ё" and "е".
        assertEquals("3", MenuPicker.variantLabel(item("Мелирование, колпачков объема 3", "Мелирование, колпачков объёма")))
        // What parts the group's name from the rest goes with it.
        assertEquals("Мужская", MenuPicker.variantLabel(item("Стрижка, мужская", "Стрижка")))
        assertEquals("Детская", MenuPicker.variantLabel(item("Стрижка – детская", "Стрижка")))
    }

    @Test
    fun `an item whose name does not start with its group's name keeps its whole name`() {
        assertEquals("Мужская стрижка", MenuPicker.variantLabel(item("Мужская стрижка", "Стрижки")))
        // Only a whole word of it: "Мелирование" does not start with the group "Мел".
        assertEquals("Мелирование 1", MenuPicker.variantLabel(item("Мелирование 1", "Мел")))
        // A name that is the group's name and nothing more.
        assertEquals("Обесцвечивание", MenuPicker.variantLabel(item("Обесцвечивание", "обесцвечивание")))
        assertEquals("Стрижка", MenuPicker.variantLabel(item("Стрижка", "Стрижка, ")))
        assertEquals("Укладка", MenuPicker.variantLabel(item("Укладка")))
    }

    @Test
    fun `search finds the name and the group without regard to letter case or to ё`() {
        val short = item("Короткие", "Покраска в один цвет")
        val highlights = item("Мелирование 3", "Мелирование, колпачков объёма")
        val roots = item("Покраска корней своей краской", "Покраска корней")
        val wash = item("Мытьё головы")
        val menu = listOf(short, highlights, roots, wash)

        assertEquals(listOf(highlights), MenuPicker.search(menu, "МЕЛ").groups.flatMap { it.items })
        assertEquals(listOf(highlights), MenuPicker.search(menu, "объем").groups.flatMap { it.items })
        assertEquals(listOf(wash), MenuPicker.search(menu, "мыть").ungrouped)
        // Every word counts, wherever it is.
        assertEquals(listOf(short), MenuPicker.search(menu, "  цвет  корот ").groups.flatMap { it.items })
        assertEquals(listOf(short, roots), MenuPicker.search(menu, "покраска").groups.flatMap { it.items })
        assertTrue(MenuPicker.search(menu, "балаяж").isEmpty)
        assertEquals(menu.size, MenuPicker.search(menu, " ").let { layout -> layout.groups.sumOf { it.items.size } + layout.ungrouped.size })
    }

    @Test
    fun `the most used items are up to four that were used, most used first and the older of two equal ones first`() {
        val never = item("Never", uses = 0)
        val a = item("A", uses = 3)
        val b = item("B", uses = 9)
        val c = item("C", uses = 3)
        val d = item("D", uses = 1)
        val e = item("E", uses = 5)

        assertEquals(listOf(b, e, a, c), MenuPicker.frequent(listOf(never, a, b, c, d, e)))
        assertEquals(emptyList<ServiceMenuItem>(), MenuPicker.frequent(listOf(never)))
        // The Travel Fee is added by itself: however often, it is not one of her picks.
        assertEquals(listOf(b, e, a, c), MenuPicker.frequent(listOf(never, a, b, c, d, e, b.copy(id = "fee", useCount = 99, travelFee = true))))
    }

    @Test
    fun `picks are in the order of the taps, a second tap takes one back, and an item that left the menu is not picked`() {
        val a = item("A")
        val b = item("B")
        val c = item("C")
        var picked = emptyList<String>()

        for (id in listOf(c.id, a.id, b.id, a.id)) {
            picked = MenuPicker.toggle(picked, id)
        }

        assertEquals(listOf(c.id, b.id), picked)
        assertEquals(listOf(c, b), MenuPicker.picked(listOf(a, b, c), picked))
        assertEquals(listOf(b), MenuPicker.picked(listOf(a, b), picked))
    }

    @Test
    fun `the item editor offers the other groups that contain what is typed`() {
        val menu = listOf(
            item("Стрижка женская", "Стрижки"),
            item("Покраска корней своей краской", "Покраска корней"),
            item("Покраска в один цвет короткие", "Покраска в один цвет"),
            item("Стрижка мужская", "стрижки"),
        )

        assertEquals(listOf("Стрижки", "Покраска корней", "Покраска в один цвет"), MenuPicker.groupSuggestions(menu, ""))
        assertEquals(listOf("Покраска корней", "Покраска в один цвет"), MenuPicker.groupSuggestions(menu, "покр"))
        // The group that is typed already is not offered again.
        assertEquals(emptyList<String>(), MenuPicker.groupSuggestions(menu, "СТРИЖКИ "))
    }

    @Test
    fun `the item editor saves its name and its group as one line each, and an empty group as none`() {
        assertEquals(MenuItemValues("Короткие", 5_000, "Покраска в один цвет"), MenuItemValues.of(" Короткие ", 5_000, "  Покраска в один цвет\n"))
        assertEquals("", MenuItemValues.of("Стрижка", 3_000, "   ")?.group)
        assertNull(MenuItemValues.of("   ", 3_000, "Стрижки"))
    }
}
