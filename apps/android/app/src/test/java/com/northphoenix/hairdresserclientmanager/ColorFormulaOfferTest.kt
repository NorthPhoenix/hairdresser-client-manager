package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.ColorFormula
import com.northphoenix.hairdresserclientmanager.domain.ColorFormulaOffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Services offer to add a Color Formula with the full button, and which with a quiet one. */
class ColorFormulaOfferTest {
    private fun service(name: String, formulas: List<ColorFormula> = emptyList()) =
        AppointmentService(id = "service_1", clientId = "client_1", name = name, priceCents = 5_000, colorFormulas = formulas)

    @Test
    fun `colour work in Russian or English gets the full button whatever the letter case`() {
        listOf(
            "Покраска в один цвет средний волос",
            "Окрашивание корней",
            "МЕЛИРОВАНИЕ",
            "Обесцвечивание длинные",
            "Тонирование",
            "Балаяж",
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
}
