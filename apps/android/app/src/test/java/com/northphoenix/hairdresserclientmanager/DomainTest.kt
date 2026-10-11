package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.core.AppConfig
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.ContactImport
import com.northphoenix.hairdresserclientmanager.domain.DeviceContact
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.domain.weeksOf
import com.northphoenix.hairdresserclientmanager.i18n.normalizeLanguage
import com.northphoenix.hairdresserclientmanager.i18n.stringsLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class DomainTest {
    private fun client(id: String, name: String, phone: String = "") =
        ClientProfile(id = id, name = name, language = Language.RU, phone = phone)

    private fun assertAmount(cents: Long, text: String) = assertEquals(text, Amount.Valid(cents), Money.parse(text))

    private fun assertRefused(reason: Amount, vararg texts: String) = texts.forEach { assertEquals(it, reason, Money.parse(it)) }

    @Test
    fun `an amount is read with a decimal point or a decimal comma and at most two decimals`() {
        assertAmount(8500, "85")
        assertAmount(8550, " 85,5 ")
        assertAmount(22050, "220.50")
        assertAmount(22050, "220,50")
        assertAmount(0, "0")
        assertAmount(0, "0.00")
        assertAmount(8500, "085")
        // A separator with digits on one side only is still one amount.
        assertAmount(50, ".5")
        assertAmount(50, ",50")
        assertAmount(8500, "85.")
        assertAmount(120000, "1200")
    }

    @Test
    fun `a separator followed by exactly three digits separates thousands`() {
        // M21: this was saved as 1.20.
        assertAmount(120000, "1,200")
        assertAmount(120000, "1.200")
        assertAmount(120000, "1 200")
        assertAmount(120000, "1\u00A0200")
        assertAmount(123456, "1,234.56")
        assertAmount(123450, "1,234.50")
        assertAmount(120050, "1.200,50")
        assertAmount(150050, "1 500,50")
        assertAmount(150050, "1 500.50")
        assertAmount(150000, "1 500")
        assertAmount(123456789, "1,234,567.89")
        assertAmount(100000000, "1.000.000")
        // Three digits cannot be cents, so this is not 12.35 rounded.
        assertAmount(1234500, "12.345")
        assertAmount(9999900, "99.999")
    }

    @Test
    fun `text that is not one amount is refused, never read as zero`() {
        assertRefused(Amount.Empty, "", "   ")
        assertRefused(Amount.Unreadable, "abc", "12abc", "1e3", "$85", "+5", "12.50.5", ".", ",", "8 5", "85 .50")
        // More decimals than cents have, in a shape that cannot be thousands either.
        assertRefused(Amount.Unreadable, "1,2345", "0.125", "0,500", "1234,567", "12,34,567")
        // Two separators, each followed by three digits: either could be the decimal one.
        assertRefused(Amount.Unreadable, "1,000.000", "1.234.56", "1,234,56", "1 234,567", "1,200,")
        assertRefused(Amount.Negative, "-20", "-4", "-0.5", "\u22125", "-1,200", "-99999999999")
        assertRefused(Amount.Unreadable, "-", "-abc", "--5")
    }

    @Test
    fun `only an amount is saved, and an empty field only where none is required`() {
        val notAmounts = listOf(Amount.Unreadable, Amount.Negative, Amount.TooLarge)

        assertFalse(Amount.Valid(0).isRefused())
        assertFalse(Amount.Valid(8500).isRefused(required = false))
        // A Service Menu Item and an ad hoc Service state their price. A Service from the menu may leave it to the menu.
        assertTrue(Amount.Empty.isRefused())
        assertFalse(Amount.Empty.isRefused(required = false))
        assertTrue(notAmounts.all { it.isRefused() })
        assertTrue(notAmounts.all { it.isRefused(required = false) })
    }

    @Test
    fun `an amount above the maximum is refused, never capped`() {
        assertAmount(Money.MAX_CENTS, "9999999.99")
        assertAmount(Money.MAX_CENTS, "9,999,999.99")
        // H7: these were saved as 21474836.47, the most an Int holds.
        assertRefused(Amount.TooLarge, "10000000", "10000000.00", "99999999999", "89161234567", "21474836.47", "10,000,000", "9".repeat(40))
        assertAmount(999999900, "0009999999")
    }

    @Test
    fun `amounts are written out exactly, also above what an Int holds`() {
        assertEquals("85.00", Money.centsToPrice(8500))
        assertEquals("0.05", Money.centsToPrice(5))
        assertEquals("$220.50", Money.format(22050))
        // The Services total of an Appointment saved before amounts had a maximum.
        assertEquals("$42949672.94", Money.format(4294967294))
        assertEquals("$9999999.99", Money.format(Money.MAX_CENTS))
    }

    @Test
    fun `what the app writes into an amount field is read back as the same amount`() {
        listOf(0L, 5L, 50L, 8500L, 22050L, 120000L, Money.MAX_CENTS).forEach { cents ->
            assertEquals(Amount.Valid(cents), Money.parse(Money.centsToPrice(cents)))
        }
    }

    @Test
    fun `client search matches name, phone digits and phone as typed`() {
        val clients = listOf(client("1", "Анна Петрова", "+1 (512) 555-0100"), client("2", "Emily Carter", "+1 512 555 0102"))

        assertEquals(listOf("1"), ClientRules.filter(clients, "анна").map { it.id })
        assertEquals(listOf("2"), ClientRules.filter(clients, "5550102").map { it.id })
        assertEquals(listOf("1"), ClientRules.filter(clients, "(512) 555-0100").map { it.id })
        assertEquals(2, ClientRules.filter(clients, "  ").size)
        assertTrue(ClientRules.filter(clients, "nobody").isEmpty())
    }

    @Test
    fun `duplicate phone ignores formatting and the client being edited`() {
        val clients = listOf(client("1", "Anna", "+1 512 555 0100"))

        assertTrue(ClientRules.isDuplicatePhone(clients, editingClientId = null, phone = "+1 (512) 555-0100"))
        assertFalse(ClientRules.isDuplicatePhone(clients, editingClientId = "1", phone = "+1 512 555 0100"))
        assertFalse(ClientRules.isDuplicatePhone(clients, editingClientId = null, phone = "  "))
    }

    @Test
    fun `contact import takes the first non-blank value of each kind`() {
        val imported = ContactImport.normalize(
            DeviceContact(
                fullName = "  ",
                givenName = "Anna",
                familyName = "Petrova",
                phones = listOf(null, " ", "+1 555 0100", "+1 555 0199"),
                emails = listOf("", "anna@example.com"),
                addresses = listOf(
                    DeviceContact.PostalAddress(),
                    DeviceContact.PostalAddress(street = "1 Main St", city = "Austin", region = "TX", postcode = " ", country = "USA"),
                ),
            ),
        )

        assertEquals("Anna Petrova", imported.name)
        assertEquals("+1 555 0100", imported.phone)
        assertEquals("anna@example.com", imported.email)
        assertEquals("1 Main St, Austin, TX, USA", imported.address)
        assertTrue(imported.hasFields)
    }

    @Test
    fun `contact with nothing usable has no fields`() {
        val imported = ContactImport.normalize(DeviceContact(fullName = " ", phones = listOf("")))

        assertNull(imported.name)
        assertFalse(imported.hasFields)
    }

    @Test
    fun `reminder recipient keeps only digits and the plus sign`() {
        assertEquals("+15125550100", ClientReminder.recipient(" +1 (512) 555-0100 "))
    }

    @Test
    fun `anything that is not English falls back to Russian`() {
        assertEquals(Language.EN, normalizeLanguage("en-US"))
        assertEquals(Language.RU, normalizeLanguage("ru-RU"))
        assertEquals(Language.RU, normalizeLanguage("de-DE"))
        assertEquals(Language.RU, normalizeLanguage(null))
    }

    @Test
    fun `only a Russian locale is shown in Russian, whatever the fallback would choose`() {
        assertEquals(Language.RU, stringsLanguage("ru"))
        assertEquals(Language.RU, stringsLanguage("ru-KZ"))
        assertEquals(Language.EN, stringsLanguage("en-GB"))
        // No German strings: Android shows the default set, which is English.
        assertEquals(Language.EN, stringsLanguage("de-DE"))
        assertEquals(Language.EN, stringsLanguage(null))
    }

    @Test
    fun `base url accepts the web origin or the full tRPC url`() {
        assertEquals("http://10.0.2.2:3000", AppConfig.normalizeBaseUrl("http://10.0.2.2:3000/api/trpc/"))
        assertEquals("https://hcm.example.com", AppConfig.normalizeBaseUrl(" https://hcm.example.com/ "))
    }

    @Test
    fun `times are shown and built in the stylist timezone`() {
        val format = TimeFormat(Locale.US, ZoneId.of("America/Chicago"))
        val appointment = Appointment(
            id = "a",
            primaryClientId = "c",
            primaryClientName = "Anna",
            startsAt = "2026-06-13T19:00:00.000Z",
            endsAt = "2026-06-13T20:30:00.000Z",
            status = AppointmentStatus.SCHEDULED,
            locationType = LocationType.IN_SALON,
        )

        assertEquals(LocalDate.of(2026, 6, 13), format.zoned(appointment.startsAt).toLocalDate())
        assertEquals(14, format.zoned(appointment.startsAt).hour)
        assertEquals(Instant.parse("2026-06-13T19:00:00Z"), format.toInstant(LocalDate.of(2026, 6, 13), LocalTime.of(14, 0)))

        val (start, end) = format.dayRange(LocalDate.of(2026, 6, 13))
        assertEquals(Instant.parse("2026-06-13T05:00:00Z"), start)
        assertEquals(Instant.parse("2026-06-14T05:00:00Z"), end)
    }

    @Test
    fun `dates and clock follow the app language`() {
        val day = LocalDate.of(2026, 10, 8)
        val english = TimeFormat(Locale.US, ZoneId.of("UTC"))
        val russian = TimeFormat(Locale.forLanguageTag("ru-RU"), ZoneId.of("UTC"))

        assertEquals("Thursday, October 8", english.longDay(day))
        assertEquals("Четверг, 8 октября", russian.longDay(day))
        assertTrue(english.uses12HourClock)
        assertFalse(russian.uses12HourClock)
        assertEquals("14:00", russian.time(LocalTime.of(14, 0)))
    }

    // --- date picker ---

    @Test
    fun `a day in the date picker is named in full, in the app language`() {
        val day = LocalDate.of(2026, 10, 8)

        assertEquals("Thursday, October 8, 2026", TimeFormat(Locale.US, ZoneId.of("UTC")).fullDate(day))
        // Which no-break space keeps the year and its "г." together depends on the platform's locale data.
        assertEquals(
            "Четверг, 8 октября 2026 г.",
            TimeFormat(Locale.forLanguageTag("ru-RU"), ZoneId.of("UTC")).fullDate(day).replace(Regex("\\p{Zs}"), " "),
        )
    }

    @Test
    fun `the week starts on Sunday in US English and on Monday in Russian`() {
        assertEquals(DayOfWeek.SUNDAY, TimeFormat(Locale.US, ZoneId.of("UTC")).firstDayOfWeek)
        assertEquals(DayOfWeek.MONDAY, TimeFormat(Locale.forLanguageTag("ru-RU"), ZoneId.of("UTC")).firstDayOfWeek)
    }

    @Test
    fun `a month is laid out in whole weeks from the first day of the week`() {
        // 1 October 2026 is a Thursday.
        val sundayFirst = weeksOf(YearMonth.of(2026, 10), DayOfWeek.SUNDAY)
        val mondayFirst = weeksOf(YearMonth.of(2026, 10), DayOfWeek.MONDAY)

        assertEquals(listOf(null, null, null, null, 1, 2, 3), sundayFirst.first().map { it?.dayOfMonth })
        assertEquals(listOf(25, 26, 27, 28, 29, 30, 31), sundayFirst.last().map { it?.dayOfMonth })
        assertEquals(listOf(null, null, null, 1, 2, 3, 4), mondayFirst.first().map { it?.dayOfMonth })
        assertEquals(listOf(26, 27, 28, 29, 30, 31, null), mondayFirst.last().map { it?.dayOfMonth })
        assertEquals(5, mondayFirst.size)
        assertEquals((1..31).map { LocalDate.of(2026, 10, it) }, mondayFirst.flatten().filterNotNull())
    }

    @Test
    fun `a month takes between four and six weeks`() {
        // February 2026 starts on a Sunday, August 2026 on a Saturday.
        val shortest = weeksOf(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)
        val longest = weeksOf(YearMonth.of(2026, 8), DayOfWeek.MONDAY)

        assertEquals(4, shortest.size)
        assertTrue(shortest.flatten().none { it == null })
        assertEquals(6, longest.size)
        assertEquals(listOf(null, null, null, null, null, 1, 2), longest.first().map { it?.dayOfMonth })
        assertEquals(listOf(31, null, null, null, null, null, null), longest.last().map { it?.dayOfMonth })
        assertTrue(longest.all { it.size == 7 })
    }
}
