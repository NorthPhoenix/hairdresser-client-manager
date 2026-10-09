package com.northphoenix.hairdresserclientmanager.domain

import java.util.Locale
import kotlin.math.roundToInt

object Money {
    /** "85.00" for an editable field. */
    fun centsToPrice(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)

    /** "$85.00" for display. */
    fun format(cents: Int): String = "$" + centsToPrice(cents)

    /** Accepts "85", "85.5" or "85,50". Anything unparseable or negative counts as zero. */
    fun priceToCents(price: String): Int {
        val parsed = price.replace(",", ".").trim().ifEmpty { "0" }.toDoubleOrNull() ?: return 0

        if (!parsed.isFinite() || parsed < 0) {
            return 0
        }

        return (parsed * 100).roundToInt()
    }
}
