package com.northphoenix.hairdresserclientmanager.domain

import java.text.Normalizer

/**
 * A device contact that can become a Client in the import of several contacts: one that has a phone number.
 * [name] is the name it is saved under, or its phone number when it has none ([nameless]).
 * [phone] is the first number; [phones] are all of them. Every text is as long as the server takes.
 */
data class ImportCandidate(
    val id: Long,
    val name: String,
    val phone: String,
    val phones: List<String>,
    val address: String = "",
    val note: String = "",
    val nameless: Boolean = false,
)

/** A candidate in the list: [alreadyClient] when a Client already has one of its numbers, and then it cannot be picked. */
data class ImportRow(val candidate: ImportCandidate, val alreadyClient: Boolean)

/** What the list shows, in order: a letter, then the contacts under it. */
sealed interface ImportItem {
    data class Header(val letter: String) : ImportItem

    data class Entry(val row: ImportRow) : ImportItem
}

/** The list of device contacts that can be imported as Clients: which are in it, in which order, which a search finds. */
object ContactImportList {
    private const val OTHER_LETTER = "#"

    /** The contacts that have a phone number, ready to become Clients. A contact without a number is not in the list. */
    fun candidates(contacts: List<DeviceContact>): List<ImportCandidate> =
        contacts.mapNotNull { contact ->
            val id = contact.id ?: return@mapNotNull null
            val phones = contact.phones
                .mapNotNull { phone -> phone?.let(OneLine::clean)?.takeIf { it.any(Char::isDigit) } }
                .distinctBy { PhoneDigits.key(it) ?: PhoneDigits.digits(it) }
                .map { cut(it, TextLimits.PHONE) }

            if (phones.isEmpty()) {
                return@mapNotNull null
            }

            val imported = ContactImport.normalize(contact)
            val name = imported.name?.let { cut(it, TextLimits.NAME) }

            ImportCandidate(
                id = id,
                name = name ?: cut(phones.first(), TextLimits.NAME),
                phone = phones.first(),
                phones = phones,
                address = cut(imported.address.orEmpty(), TextLimits.ADDRESS),
                note = cut(contact.note?.trim().orEmpty(), TextLimits.NOTE),
                nameless = name == null,
            )
        }

    /**
     * The rows of the list, in order: by name, Cyrillic before Latin when [cyrillicFirst] (a Stylist who writes Russian),
     * a name that starts with no letter last. A contact is [ImportRow.alreadyClient] when any of its numbers is one of [clientPhones].
     */
    fun rows(candidates: List<ImportCandidate>, clientPhones: Collection<String>, cyrillicFirst: Boolean): List<ImportRow> {
        val taken = clientPhones.mapNotNull(PhoneDigits::key).toSet()

        return candidates
            .map { candidate -> ImportRow(candidate, candidate.phones.any { PhoneDigits.key(it) in taken }) }
            .sortedWith(compareBy<ImportRow>({ bucket(it.candidate.name, cyrillicFirst) }, { fold(it.candidate.name) }, { it.candidate.id }))
    }

    /** The rows that a search finds: by name, or, when the search is a phone number, by the digits of any number. */
    fun filter(rows: List<ImportRow>, search: String): List<ImportRow> {
        val query = fold(search.trim())

        if (query.isEmpty()) {
            return rows
        }

        val digits = if (isPhoneLike(query)) PhoneDigits.digits(query) else ""

        return rows.filter { row ->
            fold(row.candidate.name).contains(query) ||
                (digits.isNotEmpty() && row.candidate.phones.any { PhoneDigits.digits(it).contains(digits) }) ||
                row.candidate.phones.any { fold(it).contains(query) }
        }
    }

    /** [rows] with a letter above each run of names that start with it. */
    fun items(rows: List<ImportRow>): List<ImportItem> {
        val items = ArrayList<ImportItem>(rows.size + 28)
        var letter: String? = null

        for (row in rows) {
            val next = letterOf(row.candidate.name)

            if (next != letter) {
                items += ImportItem.Header(next)
                letter = next
            }
            items += ImportItem.Entry(row)
        }
        return items
    }

    /** The letter a name is listed under: its first letter in capitals ("ё" is "Е"), or "#". */
    fun letterOf(name: String): String {
        val first = fold(name).firstOrNull() ?: return OTHER_LETTER

        return if (first.isLetter()) first.uppercase() else OTHER_LETTER
    }

    /** The ids that can be picked among [rows]: those that are not Clients yet. */
    fun selectable(rows: List<ImportRow>): Set<Long> = rows.filterNot { it.alreadyClient }.mapTo(HashSet()) { it.candidate.id }

    private fun bucket(name: String, cyrillicFirst: Boolean): Int {
        val first = fold(name).firstOrNull()

        return when {
            first == null || !first.isLetter() -> 3
            Character.UnicodeScript.of(first.code) == Character.UnicodeScript.CYRILLIC -> if (cyrillicFirst) 0 else 1
            Character.UnicodeScript.of(first.code) == Character.UnicodeScript.LATIN -> if (cyrillicFirst) 1 else 0
            else -> 2
        }
    }

    /** Upper and lower case are the same, and so are "ё" and "е". Leading spaces and marks do not count. */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).trim().lowercase().replace('ё', 'е').filterNot(Char::isISOControl)

    /** A search that can only be a phone number: it has a digit, and nothing but digits, spaces and phone punctuation. */
    private fun isPhoneLike(query: String): Boolean =
        query.any(Char::isDigit) &&
            query.all { it.isDigit() || it.isWhitespace() || it in "+()./" || it.category == CharCategory.DASH_PUNCTUATION }

    /** At most [limit] characters, never half of a pair. */
    private fun cut(text: String, limit: Int): String {
        if (text.length <= limit) {
            return text
        }

        val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit

        return text.substring(0, end).trimEnd()
    }
}
