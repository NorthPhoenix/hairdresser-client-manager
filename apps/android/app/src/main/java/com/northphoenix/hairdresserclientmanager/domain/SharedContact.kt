package com.northphoenix.hairdresserclientmanager.domain

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * A contact shared to the app from another one, such as WhatsApp: a contact card (vCard), or a text
 * with a phone number or an email address in it. WhatsApp and Messenger let no other app read the
 * people in them, so what they share is all there is to import.
 */
object SharedContact {
    /** The texts that are read: a contact card is small, and only a photo in it makes it larger. */
    const val MAX_LENGTH = 1 shl 20

    /** The details of what was shared, as Contact Import copies them into a new Client. */
    fun read(text: String): ImportedContact {
        val shared = text.take(MAX_LENGTH)
        val contact = if (VCard.isCard(shared)) VCard.first(shared) else SharedText.read(shared)

        return contact?.let(ContactImport::normalize) ?: ImportedContact()
    }
}

/**
 * The first contact of a contact card, in any of its versions: 2.1 (Android's own and older phones,
 * with names in quoted-printable), 3.0 (WhatsApp, iPhone) and 4.0. Only what a Client Profile has is
 * read: the name, phone numbers, email addresses and postal addresses. Those marked as preferred come first.
 */
object VCard {
    private const val BEGIN = "BEGIN:VCARD"
    private const val END = "END:VCARD"

    fun isCard(text: String): Boolean = text.contains(BEGIN, ignoreCase = true)

    fun first(text: String): DeviceContact? {
        val lines = unfold(text)
        val start = lines.indexOfFirst { it.trim().equals(BEGIN, ignoreCase = true) }.takeIf { it >= 0 } ?: return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().equals(END, ignoreCase = true) } ?: lines.size
        var fullName: String? = null
        var givenName: String? = null
        var familyName: String? = null
        val phones = mutableListOf<Pair<String, Boolean>>()
        val emails = mutableListOf<Pair<String, Boolean>>()
        val addresses = mutableListOf<Pair<DeviceContact.PostalAddress, Boolean>>()

        for (line in lines.subList(start + 1, end)) {
            val property = Property.parse(line) ?: continue

            when (property.name) {
                "FN" -> fullName = fullName ?: unescape(property.value)
                "N" -> {
                    // Family name; given name; additional names; prefixes; suffixes.
                    val parts = components(property.value)
                    familyName = familyName ?: parts.getOrNull(0)
                    givenName = givenName ?: parts.getOrNull(1)
                }
                "TEL" -> phones += unescape(property.value).withoutPrefix("tel:") to property.preferred
                "EMAIL" -> emails += unescape(property.value).withoutPrefix("mailto:") to property.preferred
                "ADR" -> {
                    // Post office box; extended address (an apartment); street; city; region; postcode; country.
                    val parts = components(property.value)
                    val street = listOfNotNull(parts.getOrNull(2), parts.getOrNull(1)).map(String::trim).filter(String::isNotEmpty)

                    addresses += DeviceContact.PostalAddress(
                        street = street.joinToString(", ").ifEmpty { null },
                        city = parts.getOrNull(3),
                        region = parts.getOrNull(4),
                        postcode = parts.getOrNull(5),
                        country = parts.getOrNull(6),
                    ) to property.preferred
                }
            }
        }

        return DeviceContact(
            fullName = fullName,
            givenName = givenName,
            familyName = familyName,
            phones = preferredFirst(phones),
            emails = preferredFirst(emails),
            addresses = preferredFirst(addresses),
        )
    }

    private fun <T> preferredFirst(values: List<Pair<T, Boolean>>): List<T> = values.sortedByDescending { it.second }.map { it.first }

    /**
     * The card's lines as they are meant: a line that starts with a space or a tab goes on the one
     * before it, and so does the line after a quoted-printable value that ends in "=".
     */
    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()

        for (line in text.split("\r\n", "\n", "\r")) {
            val last = lines.lastOrNull()

            when {
                last != null && (line.startsWith(' ') || line.startsWith('\t')) -> lines[lines.lastIndex] = last + line.substring(1)
                last != null && last.endsWith('=') && Property.isQuotedPrintable(last) -> lines[lines.lastIndex] = last.dropLast(1) + line
                else -> lines += line
            }
        }
        return lines
    }

    /** The parts of a value made of parts, such as a name or an address, each as it is meant. */
    private fun components(value: String): List<String> {
        val parts = mutableListOf<String>()
        val part = StringBuilder()
        var escaped = false

        for (char in value) {
            when {
                escaped -> {
                    part.append('\\').append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == ';' -> {
                    parts += unescape(part.toString())
                    part.clear()
                }
                else -> part.append(char)
            }
        }
        parts += unescape(part.toString())
        return parts
    }

    /** A value as it is meant: "\n" is a line break, and "\,", "\;" and "\\" are the characters themselves. */
    private fun unescape(value: String): String {
        val text = StringBuilder(value.length)
        var escaped = false

        for (char in value) {
            when {
                escaped -> {
                    text.append(if (char == 'n' || char == 'N') '\n' else char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                else -> text.append(char)
            }
        }
        return text.toString()
    }

    /** A number in 4.0 is a link, "tel:+1-555-0100". */
    private fun String.withoutPrefix(prefix: String): String = if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    /** One line of a card: "item1.TEL;TYPE=CELL,PREF:+7 900 123-45-67", without its group ("item1."). */
    private class Property(val name: String, val parameters: List<String>, val value: String) {
        /** TYPE=PREF (3.0), PREF=1 (4.0), or PREF alone (2.1). */
        val preferred: Boolean
            get() = parameters.any { parameter ->
                val (key, values) = parameter.split('=', limit = 2).let { it[0].uppercase() to it.getOrNull(1).orEmpty() }

                key == "PREF" || (key == "TYPE" && values.split(',').any { it.trim('"').equals("PREF", ignoreCase = true) })
            }

        companion object {
            fun parse(line: String): Property? {
                val colon = colonOf(line) ?: return null
                val head = line.substring(0, colon).split(';')
                val name = head[0].substringAfterLast('.').trim().uppercase()
                val parameters = head.drop(1).map(String::trim)
                val raw = line.substring(colon + 1)
                val value = if (isQuotedPrintable(line)) decodeQuotedPrintable(raw, charsetOf(parameters)) else raw

                return Property(name, parameters, value)
            }

            fun isQuotedPrintable(line: String): Boolean =
                colonOf(line)?.let { line.substring(0, it).split(';').drop(1) }.orEmpty()
                    .any { it.trim().equals("QUOTED-PRINTABLE", ignoreCase = true) || it.trim().equals("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) }

            /** Where the value starts: at the first colon that is not inside a quoted parameter. */
            private fun colonOf(line: String): Int? {
                var quoted = false

                line.forEachIndexed { index, char ->
                    when {
                        char == '"' -> quoted = !quoted
                        char == ':' && !quoted -> return index
                    }
                }
                return null
            }

            private fun charsetOf(parameters: List<String>): Charset {
                val name = parameters.firstOrNull { it.startsWith("CHARSET=", ignoreCase = true) }?.substringAfter('=')?.trim('"')

                return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            }

            /** "=D0=90=D0=BD=D0=BD=D0=B0" is "Анна" in UTF-8. */
            private fun decodeQuotedPrintable(value: String, charset: Charset): String {
                val bytes = ByteArrayOutputStream(value.length)
                val plain = StringBuilder()
                var index = 0

                while (index < value.length) {
                    val hex = value.substring(index + 1, minOf(index + 3, value.length))

                    if (value[index] == '=' && hex.length == 2 && hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
                        bytes.write(plain.toString().toByteArray(charset))
                        plain.clear()
                        bytes.write(hex.toInt(16))
                        index += 3
                    } else {
                        plain.append(value[index])
                        index += 1
                    }
                }
                bytes.write(plain.toString().toByteArray(charset))
                return bytes.toByteArray().toString(charset)
            }
        }
    }
}

/**
 * The contact in a shared text: the first phone number and email address in it, and a name when a
 * line of its own is one. A link to a WhatsApp chat (wa.me) holds the phone number it opens.
 * Without a number or an address the text is a message, not a contact, and has nothing in it.
 */
object SharedText {
    private val WHATSAPP_LINK = Regex("""(?i)(?:wa\.me/|whatsapp\.com/send/?\?(?:[^\s#]*&)?phone=)\+?(\d{7,15})""")
    private val LINK = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val EMAIL = Regex("""[\p{L}\p{N}._%+-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.\p{L}{2,}""")

    // Digits with the spaces, dashes, dots and brackets a phone number is written with, on one line.
    // Not a part of a word, a time ("10:30") or a longer number.
    private val PHONE = Regex("""(?<![\p{L}\d:+])\+?[\d(][\d \t().\-‐-―]{5,}\d(?![\d:])""")

    // A name is letters, with the spaces, hyphens, apostrophes and dots between them that names have.
    private val NAME = Regex("""\p{L}[\p{L}\p{M}'’.\-]*(?:\s+\p{L}[\p{L}\p{M}'’.\-]*){0,3}""")

    fun read(text: String): DeviceContact? {
        val whatsapp = WHATSAPP_LINK.find(text)?.groupValues?.get(1)?.let { "+$it" }
        val withoutLinks = LINK.replace(text, " ")
        val email = EMAIL.find(withoutLinks)?.value
        val phone = whatsapp ?: PHONE.findAll(EMAIL.replace(withoutLinks, " ")).map { it.value.trim() }.firstOrNull(::isPhone)

        if (phone == null && email == null) {
            return null
        }

        val name = text.lines().map(String::trim).firstOrNull(String::isNotEmpty)
            ?.takeIf { it.length <= TextLimits.NAME && NAME.matches(it) }

        return DeviceContact(fullName = name, phones = listOfNotNull(phone), emails = listOfNotNull(email))
    }

    /**
     * Ten to fifteen digits, or seven and more behind a "+": a date ("27.10.2026") and a sum of
     * money are fewer, a card number is more.
     */
    private fun isPhone(candidate: String): Boolean {
        val digits = candidate.count(Char::isDigit)

        return digits in 10..15 || (candidate.startsWith('+') && digits in 7..15)
    }
}
