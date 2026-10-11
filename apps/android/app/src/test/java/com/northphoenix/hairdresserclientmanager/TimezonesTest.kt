package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.Timezones
import com.northphoenix.hairdresserclientmanager.domain.ZoneNames
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimezonesTest {
    private val ru = Locale.forLanguageTag("ru")
    private val en = Locale.ENGLISH
    private val winter = Instant.parse("2026-01-15T12:00:00Z")
    private val summer = Instant.parse("2026-07-15T12:00:00Z")

    /**
     * A few timezones as a device knows them. As on a device, a timezone is named under the id it
     * is listed by only: "Asia/Calcutta" has the city, "Asia/Kolkata" is another name of it.
     */
    private object Zones : ZoneNames {
        private class Zone(
            val id: String,
            val cityRu: String? = null,
            val cityEn: String? = null,
            val nameRu: String? = null,
            val nameEn: String? = null,
            val otherIds: List<String> = emptyList(),
            val ofAPlace: Boolean = true,
        )

        private val zones = listOf(
            Zone("Europe/Moscow", "Москва", "Moscow", "Москва, стандартное время", "Moscow Standard Time", listOf("W-SU")),
            Zone("Asia/Novosibirsk", "Новосибирск", "Novosibirsk", "Новосибирск, стандартное время", "Novosibirsk Standard Time"),
            Zone("America/New_York", "Нью-Йорк", "New York", "Восточная Америка, стандартное время", "Eastern Standard Time", listOf("US/Eastern")),
            Zone("Europe/Kiev", "Киев", "Kyiv", "Восточная Европа, стандартное время", "Eastern European Standard Time", listOf("Europe/Kyiv")),
            Zone("Asia/Calcutta", "Калькутта", "Kolkata", "Индия, стандартное время", "India Standard Time", listOf("Asia/Kolkata")),
            Zone("Asia/Vladivostok", "Владивосток", "Vladivostok", "Владивосток, стандартное время", "Vladivostok Standard Time"),
            Zone("America/St_Johns", "Сент-Джонс", "St. John’s", "Ньюфаундленд, стандартное время", "Newfoundland Standard Time", listOf("Canada/Newfoundland")),
            Zone("Etc/UTC", nameRu ="Всемирное координированное время", nameEn = "Coordinated Universal Time", otherIds = listOf("UTC", "Zulu"), ofAPlace = false),
            Zone("Etc/GMT+5", ofAPlace = false),
        )

        private fun zone(id: String) = zones.firstOrNull { it.id == id }

        override fun listed() = zones.filter { it.ofAPlace }.map { it.id }

        override fun all() = zones.flatMap { listOf(it.id) + it.otherIds }

        override fun listedId(id: String) = zones.firstOrNull { id == it.id || id in it.otherIds }?.id ?: id

        override fun city(id: String, locale: Locale) = zone(id)?.let { if (locale.language == "ru") it.cityRu else it.cityEn }

        override fun name(id: String, locale: Locale, at: Instant) = zone(id)?.let { if (locale.language == "ru") it.nameRu else it.nameEn }
    }

    private fun options(shown: Locale = ru, selected: String = "Europe/Moscow", now: Instant = winter) =
        Timezones.options(Zones, shown, listOf(ru, en), selected, now)

    private fun found(query: String, shown: Locale = ru, selected: String = "Europe/Moscow") =
        Timezones.search(options(shown, selected), query).map { it.id }

    @Test
    fun `timezones are listed by their city in the language of the screen, UTC first`() {
        assertEquals(
            listOf("Всемирное координированное время", "Владивосток", "Калькутта", "Киев", "Москва", "Новосибирск", "Нью-Йорк", "Сент-Джонс"),
            options(ru).map { it.title },
        )
        assertEquals(
            listOf("Coordinated Universal Time", "Kolkata", "Kyiv", "Moscow", "New York", "Novosibirsk", "St. John’s", "Vladivostok"),
            options(en).map { it.title },
        )
    }

    @Test
    fun `a timezone has one row, whatever names it had before`() {
        assertEquals(
            listOf("Etc/UTC", "Asia/Vladivostok", "Asia/Calcutta", "Europe/Kiev", "Europe/Moscow", "Asia/Novosibirsk", "America/New_York", "America/St_Johns"),
            options().map { it.id },
        )
    }

    @Test
    fun `a row has the city, what the timezone is called and how far it is from UTC now`() {
        val moscow = options().first { it.id == "Europe/Moscow" }

        assertEquals("Москва", moscow.title)
        assertEquals("Москва, стандартное время · UTC+03:00", moscow.detail)
        assertEquals("Moscow Standard Time · UTC+03:00", options(en).first { it.id == "Europe/Moscow" }.detail)

        assertEquals("UTC-05:00", options(now = winter).first { it.id == "America/New_York" }.offset)
        assertEquals("UTC-04:00", options(now = summer).first { it.id == "America/New_York" }.offset)
        assertEquals("UTC+05:30", options().first { it.id == "Asia/Calcutta" }.offset)
    }

    @Test
    fun `UTC has no city and goes by its name, which is not said twice`() {
        val utc = options().first()

        assertEquals("Всемирное координированное время", utc.title)
        assertEquals("UTC+00:00", utc.detail)
    }

    @Test
    fun `the chosen timezone has a row under the id it is saved with`() {
        // The default of a new account, in both of its spellings.
        assertEquals("Etc/UTC", options(selected = "Etc/UTC").first().id)
        assertEquals("UTC", options(selected = "UTC").first().id)
        assertEquals(8, options(selected = "UTC").size)

        // Another name of a listed timezone: its row, not a second one.
        val kolkata = options(selected = "Asia/Kolkata")

        assertEquals(8, kolkata.size)
        assertEquals("Калькутта", kolkata.first { it.id == "Asia/Kolkata" }.title)
        assertEquals(emptyList<String>(), kolkata.filter { it.id == "Asia/Calcutta" })
    }

    @Test
    fun `a chosen timezone that is not listed has a row of its own`() {
        val fixedOffset = options(selected = "Etc/GMT+5")

        assertEquals(9, fixedOffset.size)
        assertEquals("GMT+5" to "UTC-05:00", fixedOffset.first { it.id == "Etc/GMT+5" }.let { it.title to it.detail })

        // An id this device does not know: it is shown as it is, with no offset to give.
        val unknown = options(selected = "Mars/Phobos").first { it.id == "Mars/Phobos" }

        assertEquals("Phobos", unknown.title)
        assertEquals("", unknown.detail)
        assertEquals("Phobos", unknown.label)

        assertEquals(8, options(selected = "").size)
    }

    @Test
    fun `a timezone is found in Russian and in English, whatever language the screen is in`() {
        assertEquals(listOf("Europe/Moscow"), found("москва", shown = en))
        assertEquals(listOf("Europe/Moscow"), found("moscow", shown = ru))
        assertEquals(listOf("Asia/Novosibirsk"), found("НОВОСИБИРСК"))
        assertEquals(listOf("Asia/Novosibirsk"), found("  novosib "))

        for (search in listOf("нью-йорк", "Нью Йорк", "new york", "new_york", "America/New_York")) {
            assertEquals(search, listOf("America/New_York"), found(search))
        }

        for (search in listOf("st. john's", "St. John’s", "st johns", "сент джонс")) {
            assertEquals(search, listOf("America/St_Johns"), found(search))
        }

        // By what the timezone is called, too. The rows keep the order of the list.
        assertEquals(listOf("Europe/Kiev", "America/New_York"), found("eastern"))
        assertEquals(listOf("Europe/Kiev"), found("european time"))
        assertEquals(listOf("Europe/Kiev", "America/New_York"), found("Eastern Time"))
        assertEquals(listOf("Asia/Calcutta"), found("индия"))
    }

    @Test
    fun `a timezone is found by every id it has or had`() {
        assertEquals(listOf("Europe/Kiev"), found("kiev"))
        assertEquals(listOf("Europe/Kiev"), found("europe/kyiv"))
        assertEquals(listOf("America/New_York"), found("us/eastern"))
        assertEquals(listOf("Asia/Calcutta"), found("calcutta"))
        assertEquals(listOf("Europe/Moscow"), found("w-su"))
    }

    @Test
    fun `utc and etc find UTC, not every timezone by the UTC its offset is written with`() {
        assertEquals(listOf("Etc/UTC"), found("utc"))
        assertEquals(listOf("Etc/UTC"), found("Etc"))
        assertEquals(listOf("Etc/UTC"), found("etc/utc"))
        assertEquals(listOf("UTC"), found("etc", selected = "UTC"))
        assertEquals(listOf("Etc/UTC"), found("координированное"))
    }

    @Test
    fun `an offset is found the way it is typed`() {
        for (search in listOf("+3", "+03", "+03:00", "+0300", "utc+3", "UTC +3", "gmt+03")) {
            assertEquals(search, listOf("Europe/Moscow"), found(search))
        }
        for (search in listOf("-5", "−5", "utc-5", "-05:00")) {
            assertEquals(search, listOf("America/New_York"), found(search))
        }
        for (search in listOf("+5", "+5:30", "+05:30", "+530", "+0530")) {
            assertEquals(search, listOf("Asia/Calcutta"), found(search))
        }
        for (search in listOf("-3", "-3:30", "utc-0330")) {
            assertEquals(search, listOf("America/St_Johns"), found(search))
        }

        // One digit is an hour, not the start of two.
        assertEquals(emptyList<String>(), found("+1"))
        assertEquals(listOf("Asia/Vladivostok"), found("+10"))
        assertEquals(listOf("Etc/UTC"), found("+0"))
        assertEquals(emptyList<String>(), found("+5:45"))

        // A sign alone, on the way to an offset.
        assertEquals(listOf("America/New_York", "America/St_Johns"), found("-"))
        assertEquals(6, found("utc+").size)
    }

    @Test
    fun `a search finds nothing when nothing matches and everything when it is empty`() {
        assertEquals(emptyList<String>(), found("zzzz"))
        assertEquals(emptyList<String>(), found("moskva"))
        assertEquals(emptyList<String>(), found("3"))
        assertEquals(8, found("").size)
        assertEquals(8, found("   ").size)
    }

    @Test
    fun `the Settings field names the timezone and not its id`() {
        assertEquals("Москва (UTC+03:00)", Timezones.option(Zones, ru, "Europe/Moscow", winter)?.label)
        assertEquals("Moscow (UTC+03:00)", Timezones.option(Zones, en, "Europe/Moscow", winter)?.label)
        assertEquals("New York (UTC-04:00)", Timezones.option(Zones, en, "America/New_York", summer)?.label)
        assertEquals("Калькутта (UTC+05:30)", Timezones.option(Zones, ru, "Asia/Kolkata", winter)?.label)
        assertEquals("Всемирное координированное время (UTC+00:00)", Timezones.option(Zones, ru, "Etc/UTC", winter)?.label)
        assertEquals("Coordinated Universal Time (UTC+00:00)", Timezones.option(Zones, en, "UTC", winter)?.label)
        assertNull(Timezones.option(Zones, ru, "", winter))
    }
}
