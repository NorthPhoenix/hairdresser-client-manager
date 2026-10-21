package com.northphoenix.hairdresserclientmanager.domain

/**
 * Whether two phone numbers are the same number, by their digits. Kept apart from [ClientRules],
 * which has its own rules for Russian numbers, so that the two can be brought together later.
 */
object PhoneDigits {
    /** A number with fewer digits than this is no phone number to compare by: a short code or an extension. */
    private const val MIN_DIGITS = 7

    /** The digits that compare. A US number is 10 digits and may start with 1 or +1, so the last 10 are what count. */
    private const val COMPARED_DIGITS = 10

    fun digits(phone: String): String = phone.filter(Char::isDigit)

    /** What [phone] is compared by, however it was written: its last ten digits. Null when it has too few to be a number. */
    fun key(phone: String?): String? =
        phone?.let(::digits)?.takeIf { it.length >= MIN_DIGITS }?.takeLast(COMPARED_DIGITS)

    fun same(a: String?, b: String?): Boolean = key(a)?.let { it == key(b) } ?: false
}
