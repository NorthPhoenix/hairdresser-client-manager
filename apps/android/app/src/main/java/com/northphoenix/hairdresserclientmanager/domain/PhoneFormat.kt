package com.northphoenix.hairdresserclientmanager.domain

/**
 * How a phone number is shown: a US number as "(512) 917-4528", however it was typed ("+1 512-917-4528",
 * "5129174528", "+1(512)917 4528"), and any other number as it was typed. The stored text is not changed by
 * showing it; a call or a text goes to its digits ([ClientReminder.recipient]).
 */
object PhoneFormat {
    /** [phone] as it is shown everywhere in the app. */
    fun display(phone: String): String {
        val typed = phone.trim()
        val digits = usDigits(typed) ?: return typed

        return "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
    }

    /**
     * The ten digits of a US number: ten digits, or eleven that start with the country code 1, written with
     * nothing but digits, spaces and phone punctuation. A '+' may only stand in front, and only before a 1:
     * "+7 916 123 45 67" and "+44 20 7946 0958" are numbers of other countries and stay as typed, and so does
     * anything with letters in it, such as an extension.
     */
    fun usDigits(phone: String): String? {
        val typed = phone.trim()

        if (typed.isEmpty() || !typed.all { it in '0'..'9' || it == ' ' || it in "()./+" || it.category == CharCategory.DASH_PUNCTUATION }) {
            return null
        }

        val digits = typed.filter { it in '0'..'9' }
        val international = typed.startsWith('+')

        if (typed.lastIndexOf('+') > 0) {
            return null
        }

        return when {
            digits.length == 11 && digits[0] == '1' -> digits.drop(1)
            digits.length == 10 && !international -> digits
            else -> null
        }
    }
}
