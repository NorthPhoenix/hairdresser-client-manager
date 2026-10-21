package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import java.text.BreakIterator
import java.text.Normalizer

object ClientRules {
    fun searchablePhone(phone: String): String = phone.filter(Char::isDigit)

    /**
     * The number a phone is compared by: its ten digits for a number written with or without its country code,
     * a US one ("+1 512 ...", "(512) ...") or a Russian mobile ("+7 916 ...", "8 916 ..." or "916 ..."), and
     * all its digits for any other number, which is not rewritten. Eleven digits that start with 8 and another
     * digit than 9 are a full number of some other country, not a Russian one written with 8.
     */
    fun comparablePhone(phone: String): String {
        val digits = searchablePhone(phone)

        return when {
            digits.length == 11 && digits[0] == '1' -> digits.drop(1)
            else -> russianMobile(digits) ?: digits
        }
    }

    /**
     * The other Clients who may be the same person as [client]: their phone numbers are one number by
     * [comparablePhone]. A Client the Stylist has said is someone else ([ClientProfile.distinctFromIds], on
     * either side) is not one, and neither is anyone for a Client without a number. In the order of [clients].
     */
    fun possibleDuplicates(client: ClientProfile, clients: List<ClientProfile>): List<ClientProfile> {
        val number = comparablePhone(client.phone)

        if (number.isEmpty()) {
            return emptyList()
        }

        return clients.filter { other ->
            other.id != client.id &&
                comparablePhone(other.phone) == number &&
                other.id !in client.distinctFromIds.orEmpty() &&
                client.id !in other.distinctFromIds.orEmpty()
        }
    }

    /** The ids of the Clients in [clients] who have a possible duplicate among them (see [possibleDuplicates]). */
    fun withPossibleDuplicates(clients: List<ClientProfile>): Set<String> =
        clients
            .groupBy { comparablePhone(it.phone) }
            .filterKeys { it.isNotEmpty() }
            .values
            .filter { it.size > 1 }
            .flatMap { group -> group.filter { possibleDuplicates(it, group).isNotEmpty() } }
            .map { it.id }
            .toSet()

    /**
     * Matches by name or by phone. Upper and lower case are the same, and so are "ё" and "е".
     * Only a search that is itself a phone number is matched through its digits; anything else
     * has to be found as typed, in the name or in the phone.
     */
    fun filter(clients: List<ClientProfile>, search: String): List<ClientProfile> {
        val query = searchFold(search.trim())

        if (query.isEmpty()) {
            return clients
        }

        val phoneQuery = if (isPhoneLike(query)) searchablePhone(query) else ""

        return clients.filter { client ->
            searchFold(client.name).contains(query) ||
                (phoneQuery.isNotEmpty() && phoneForms(client.phone).any { it.contains(phoneQuery) }) ||
                searchFold(client.phone).contains(query)
        }
    }

    /**
     * True when another Client already uses this phone number. Saving is still allowed after a warning.
     * Numbers are compared by [comparablePhone]. A phone without a digit is no number and never a duplicate.
     */
    fun isDuplicatePhone(clients: List<ClientProfile>, editingClientId: String?, phone: String): Boolean {
        val number = comparablePhone(phone)

        if (number.isEmpty()) {
            return false
        }

        return clients.any { it.id != editingClientId && comparablePhone(it.phone) == number }
    }

    /**
     * Whether a save takes this email: none, or one address with a domain, such as name@example.com.
     * The server's own rule (`emailText` in packages/api/src/input.ts).
     */
    fun isEmail(email: String): Boolean {
        val address = email.trim()
        val at = address.indexOf('@')

        if (address.isEmpty()) {
            return true
        }

        if (at < 1 || at != address.lastIndexOf('@') || address.any(Char::isWhitespace)) {
            return false
        }

        val domain = address.substring(at + 1).split('.')

        return domain.size > 1 && domain.none(String::isEmpty)
    }

    /**
     * The initials of an avatar: the first character of each of the first two words, in upper case.
     * A character is all of what is drawn as one (an emoji with its modifiers, a letter with its accent), never a part of it.
     */
    fun initials(name: String): String {
        val characters = BreakIterator.getCharacterInstance()

        return name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { word ->
            characters.setText(word)
            word.substring(0, characters.next()).uppercase()
        }.ifEmpty { "·" }
    }

    /**
     * Text as a search compares it: one spelling of every character, lower case, and "ё" (U+0451) as "е" (U+0435).
     * Control characters, which a name saved long ago may hold and nobody sees, are left out: the name is found as it reads.
     */
    private fun searchFold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().replace('ё', 'е').filterNot(Char::isISOControl)

    /** A search that can only be a phone number: it has a digit, and nothing but digits, spaces and phone punctuation. */
    private fun isPhoneLike(query: String): Boolean =
        query.any(Char::isDigit) &&
            query.all { it.isDigit() || it.isWhitespace() || it in "+()./" || it.category == CharCategory.DASH_PUNCTUATION }

    /** The digits a search may give for this phone: as saved, and for a Russian mobile also after 7 and after 8. */
    private fun phoneForms(phone: String): List<String> {
        val digits = searchablePhone(phone)
        val mobile = russianMobile(digits) ?: return listOf(digits)

        return listOf(digits, "7$mobile", "8$mobile")
    }

    /**
     * The ten digits of a Russian mobile number written as "+7 9..", "8 9.." or with neither; null for any other number.
     * Only mobiles, on purpose: eleven digits that start with 8 and another digit are a full number of some other country.
     */
    private fun russianMobile(digits: String): String? = when {
        digits.length == 11 && (digits[0] == '7' || digits[0] == '8') && digits[1] == '9' -> digits.drop(1)
        digits.length == 10 && digits[0] == '9' -> digits
        else -> null
    }
}
