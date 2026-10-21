package com.northphoenix.hairdresserclientmanager.domain

import java.math.BigDecimal

/** What the text of an amount field stands for. Only [Valid] is an amount that may be saved. */
sealed interface Amount {
    /** Whether a save has to refuse this. An empty field is taken only where no amount is [required]. */
    fun isRefused(required: Boolean = true): Boolean = this !is Valid && (required || this != Empty)

    data class Valid(val cents: Long) : Amount

    /** Nothing was typed. Whether that is an error is for the field to say. */
    data object Empty : Amount

    /** Not an amount, or text that could stand for more than one amount. */
    data object Unreadable : Amount

    data object Negative : Amount

    /** More than [Money.MAX_CENTS]. */
    data object TooLarge : Amount
}

/** Amounts are whole cents in a [Long]: the total of one Appointment can be more than an Int holds. */
object Money {
    /** The largest amount the server takes: $9,999,999.99. */
    const val MAX_CENTS = 999_999_999L

    // Digits with at most two more behind a decimal point or comma: "85", "85.5", "85,50", "85." and ".5".
    private val plain = Regex("""(\d*)(?:[.,](\d{0,2}))?""")

    // The same with thousands separators: "1,200", "1 234,56", "1.200,50". Every group behind the
    // first has exactly three digits and the same separator, and the decimals follow another one.
    private val grouped = Regex("""([1-9]\d{0,2}([., ])\d{3}(?:\2\d{3})*)(?:(?!\2)[.,](\d{0,2}))?""")

    /** "85.00" for an editable field. */
    fun centsToPrice(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** "$85.00" for display. */
    fun format(cents: Long): String = "$" + centsToPrice(cents)

    /** "$85", or "$85.50" when there are cents: for a price among many, such as a chip of the Service Menu. */
    fun formatShort(cents: Long): String = "$" + shortAmount(cents)

    /** "$50–85", or "$50" when both ends are the same. */
    fun formatRange(lowestCents: Long, highestCents: Long): String =
        if (lowestCents == highestCents) formatShort(lowestCents) else formatShort(lowestCents) + "–" + shortAmount(highestCents)

    private fun shortAmount(cents: Long): String = if (cents % 100 == 0L) (cents / 100).toString() else centsToPrice(cents)

    /**
     * Reads the text of an amount field, and only text that can be read in one way. Nothing is
     * rounded, capped or taken for zero: what is not [Amount.Valid] must be refused, not saved.
     *
     * A point and a comma both work as the decimal separator, with at most two digits behind it.
     * A point, a comma or a space followed by exactly three digits separates thousands, since
     * three digits cannot be cents: "1,200" is 1200.00, and so is "1.200".
     */
    fun parse(text: String): Amount {
        val typed = text.trim().map { if (it.isWhitespace()) ' ' else it }.joinToString("")

        if (typed.isEmpty()) {
            return Amount.Empty
        }
        if (typed.first() == '-' || typed.first() == '−') {
            val rest = parse(typed.drop(1))

            return if (rest is Amount.Valid || rest == Amount.TooLarge) Amount.Negative else Amount.Unreadable
        }

        val (whole, decimals) = plain.matchEntire(typed)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: grouped.matchEntire(typed)?.let { it.groupValues[1] to it.groupValues[3] }
            ?: return Amount.Unreadable
        val digits = whole.filter(Char::isDigit).trimStart('0')

        // A separator with no digit on either side.
        if (whole.isEmpty() && decimals.isEmpty()) {
            return Amount.Unreadable
        }
        // More digits than the maximum has. Refused before the arithmetic, which then stays far inside a Long.
        if (digits.length > MAX_CENTS.toString().length) {
            return Amount.TooLarge
        }

        val cents = digits.ifEmpty { "0" }.toLong() * 100 + decimals.padEnd(2, '0').toLong()

        return if (cents > MAX_CENTS) Amount.TooLarge else Amount.Valid(cents)
    }
}
