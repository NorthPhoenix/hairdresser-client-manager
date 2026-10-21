package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.ColorFormula
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.ColorFormulaOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Services offer to add a Color Formula with the full button, and which with a quiet one. */
class ColorFormulaOfferTest {
    private fun service(name: String, formulas: List<ColorFormula> = emptyList(), menuItemId: String? = null) =
        AppointmentService(id = "service_1", clientId = "client_1", menuItemId = menuItemId, name = name, priceCents = 5_000, colorFormulas = formulas)

    private fun item(id: String, name: String, formulaCount: Int = 0) =
        ServiceMenuItem(id = id, name = name, defaultPriceCents = 5_000, formulaCount = formulaCount)

    private val menu = listOf(item("lamination", "Ламинирование", formulaCount = 2), item("perm", "Химическая завивка"), item("cut", "Стрижка", formulaCount = 0))

    @Test
    fun `colour work in Russian or English gets the full button whatever the letter case`() {
        listOf(
            "Покраска в один цвет средний волос",
            "Окрашивание корней",
            "МЕЛИРОВАНИЕ",
            "Обесцвечивание длинные",
            "Тонирование",
            "Балаяж",
            "Колорирование в 2 и больше цветов длинный волос",
            "Колоррирование 2 цвета и больше",
            "Root color",
            "Colour refresh",
            "Highlights",
            "Bleach and tone",
            "Toner",
        ).forEach { assertTrue(it, ColorFormulaOffer.isProminent(service(it))) }
    }

    @Test
    fun `a haircut or a travel fee gets the quiet button`() {
        listOf("Стрижка мужская", "Выезд на дом к клиенту", "Укладка", "Haircut", "").forEach {
            assertFalse(it, ColorFormulaOffer.isProminent(service(it)))
        }
    }

    @Test
    fun `a Service that already has a Color Formula gets the full button whatever its name`() {
        val formula = ColorFormula(id = "formula_1", formula = "7N + 20 vol")

        assertTrue(ColorFormulaOffer.isProminent(service("Стрижка женская", listOf(formula))))
    }

    @Test
    fun `a menu item that has had a formula gets the full button on every Service started from it`() {
        val learned = ColorFormulaOffer.Learned.of(menu, emptyList())

        assertTrue(ColorFormulaOffer.isProminent(service("Ламинирование", menuItemId = "lamination"), learned))
        // Whatever the Service was renamed to after it was started from the item.
        assertTrue(ColorFormulaOffer.isProminent(service("Ламинирование Люкс", menuItemId = "lamination"), learned))
    }

    @Test
    fun `a menu item that never had a formula keeps the quiet button`() {
        val learned = ColorFormulaOffer.Learned.of(menu, emptyList())

        assertFalse(ColorFormulaOffer.isProminent(service("Химическая завивка", menuItemId = "perm"), learned))
        assertFalse(ColorFormulaOffer.isProminent(service("Стрижка", menuItemId = "cut"), learned))
    }

    @Test
    fun `a Service from a menu item is not learned by name`() {
        val learned = ColorFormulaOffer.Learned.of(menu, listOf("Химическая завивка"))

        assertFalse(ColorFormulaOffer.isProminent(service("Химическая завивка", menuItemId = "perm"), learned))
    }

    @Test
    fun `an ad-hoc Service is learned by the name of a Service that had a formula, whatever the letter case, yo, or spaces`() {
        val learned = ColorFormulaOffer.Learned.of(menu, listOf("Ёлочка  Укладка", "Ламинирование"))

        listOf("Ёлочка укладка", "елочка УКЛАДКА ", "  ламинирование", "Ламинирование").forEach {
            assertTrue(it, ColorFormulaOffer.isProminent(service(it), learned))
        }
        listOf("Ламинирование бровей", "Стрижка", "").forEach {
            assertFalse(it, ColorFormulaOffer.isProminent(service(it), learned))
        }
    }

    @Test
    fun `a Service whose menu item was deleted is learned by name`() {
        val learned = ColorFormulaOffer.Learned.of(menu, listOf("Глазирование"))

        assertTrue(ColorFormulaOffer.isProminent(service("Глазирование", menuItemId = "gone"), learned))
        assertFalse(ColorFormulaOffer.isProminent(service("Укладка", menuItemId = "gone"), learned))
    }

    @Test
    fun `without anything learned the rule is the word list and the Service's own formulas`() {
        assertFalse(ColorFormulaOffer.isProminent(service("Ламинирование", menuItemId = "lamination")))
        assertFalse(ColorFormulaOffer.isProminent(service("Ламинирование")))
        assertEquals("елочка укладка", ColorFormulaOffer.fold("  Ёлочка   УКЛАДКА "))
    }
}
