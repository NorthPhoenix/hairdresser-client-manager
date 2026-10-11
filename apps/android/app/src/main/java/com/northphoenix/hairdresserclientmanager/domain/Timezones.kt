package com.northphoenix.hairdresserclientmanager.domain

import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a device knows about timezones. `DeviceZoneNames` asks Android; tests have their own. */
interface ZoneNames {
    /** The timezones places use today, each under one id: none of the names a timezone had before. */
    fun listed(): Collection<String>

    /** Every id there is, the earlier names of a timezone among them ("Europe/Kiev", "US/Central"). */
    fun all(): Collection<String>

    /** The id [listed] has for the timezone [id] names, when [id] is another name of it; otherwise [id]. */
    fun listedId(id: String): String

    /** The city the timezone is known by in the language of [locale] ("Нью-Йорк"). Null when it has none, as UTC. */
    fun city(id: String, locale: Locale): String?

    /** What the timezone is called at [at] in the language of [locale] ("Восточная Америка, летнее время"), or null. */
    fun name(id: String, locale: Locale, at: Instant): String?
}

/**
 * A timezone as the Stylist reads it. [title] is its city, [name] what the timezone is called and
 * [offset] how far it is from UTC now ("UTC+03:00"). [id] is what is saved. [found] is every text
 * a search finds it by, as [Timezones.search] compares text.
 */
data class ZoneOption(val id: String, val title: String, val name: String?, val offset: String?, val found: List<String>) {
    /** The line under the title in the list. The offset stays with its dot when the line is broken. */
    val detail: String get() = listOfNotNull(name, offset).joinToString(" · ")

    /** The timezone on one line, as the Settings field shows it. */
    val label: String get() = if (offset == null) title else "$title ($offset)"
}

object Timezones {
    private const val UTC = "Etc/UTC"
    private const val OFFSET_PREFIX = "UTC"
    private val offsetFormat = DateTimeFormatter.ofPattern("xxx", Locale.ROOT)

    // "+3", "+03", "+03:00", "-0330", "utc+3", and "+" alone while it is being typed.
    private val offsetSearch = Regex("""(?:utc|gmt)?\s*([+\-−–])\s*(\d{1,2})?(?::?(\d{2}))?""")

    /**
     * The timezones to choose from, named in the language of [shown]: UTC first, then every
     * timezone a place uses, by city. Each is found by its names in every language of [searched]
     * and by every id it has or had. [selected] always has a row: the one of its timezone when it
     * is another name of a listed one (that row then keeps the id as [selected] has it, so
     * choosing it again changes nothing), and a row of its own when it is not listed at all.
     */
    fun options(names: ZoneNames, shown: Locale, searched: List<Locale>, selected: String, now: Instant): List<ZoneOption> {
        val selectedListed = if (selected.isBlank()) null else names.listedId(selected)
        val otherIds = names.all().groupBy(names::listedId)

        fun option(listedId: String) =
            option(names, if (listedId == selectedListed) selected else listedId, shown, searched, otherIds[listedId].orEmpty(), now)

        val collator = Collator.getInstance(shown)
        val places = (names.listed() + listOfNotNull(selectedListed)).distinct().filter { it != UTC }.map(::option)

        return (listOf(option(UTC)) + places.sortedWith(compareBy<ZoneOption, String>(collator) { it.title }.thenBy { it.id })).distinctBy { it.id }
    }

    /** The one timezone [id] named in the language of [shown], for a field that shows it. Null when there is none. */
    fun option(names: ZoneNames, shown: Locale, id: String, now: Instant): ZoneOption? =
        if (id.isBlank()) null else option(names, id, shown, emptyList(), emptyList(), now)

    /**
     * The [options] a search is after, in their order. Its words are looked for in the city, in
     * the name and in the ids, whatever their case, with "ё" as "е" and a dash or "_" as a space;
     * one of these has to hold them all ("eastern time" finds "Eastern Daylight Time"). A search
     * that is an offset ("+3", "utc-5", "+5:30") finds the timezones that are that far from UTC
     * now, and nothing else: the "UTC" every offset is written with is not text to be found.
     */
    fun search(options: List<ZoneOption>, query: String): List<ZoneOption> {
        offsetStart(query)?.let { start ->
            return options.filter { it.offset?.removePrefix(OFFSET_PREFIX)?.startsWith(start) == true }
        }

        val words = fold(query).split(' ').filter(String::isNotEmpty)

        return options.filter { option -> option.found.any { text -> words.all { it in text } } }
    }

    private fun option(names: ZoneNames, id: String, shown: Locale, searched: List<Locale>, otherIds: List<String>, now: Instant): ZoneOption {
        val listedId = names.listedId(id)
        val name = names.name(listedId, shown, now)
        val title = names.city(listedId, shown) ?: name ?: id.substringAfterLast('/').replace('_', ' ')
        val inOtherLanguages = searched.flatMap { listOfNotNull(names.city(listedId, it), names.name(listedId, it, now)) }

        return ZoneOption(
            id = id,
            title = title,
            name = name.takeUnless { it == title },
            offset = offsetOf(id, now),
            found = (listOfNotNull(title, name) + inOtherLanguages + id + listedId + otherIds).map(::fold).distinct(),
        )
    }

    /** "UTC+03:00" for a timezone the device knows, and null for an id it does not. */
    private fun offsetOf(id: String, now: Instant): String? =
        try {
            OFFSET_PREFIX + offsetFormat.format(ZoneId.of(id).rules.getOffset(now))
        } catch (_: Exception) {
            null
        }

    /** What the offset a search asks for starts with, as [offsetOf] writes one ("+3" is "+03"), or null when the search is text. */
    private fun offsetStart(query: String): String? {
        val (sign, hours, minutes) = offsetSearch.matchEntire(query.trim().lowercase())?.destructured ?: return null

        return (if (sign == "+") "+" else "-") + when {
            hours.isEmpty() -> ""
            minutes.isEmpty() -> hours.padStart(2, '0')
            else -> hours.padStart(2, '0') + ":" + minutes
        }
    }

    /** Text as a search compares it. A city is written "St. John’s" and "Нью-Йорк"; it is typed "st. john's" and "нью йорк". */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().replace('ё', 'е').replace('’', '\'')
            .map { if (it == '_' || it.category == CharCategory.DASH_PUNCTUATION) ' ' else it }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")
}
