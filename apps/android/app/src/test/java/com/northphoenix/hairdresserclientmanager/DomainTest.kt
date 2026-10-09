package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.core.AppConfig
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.ContactImport
import com.northphoenix.hairdresserclientmanager.domain.DeviceContact
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.i18n.normalizeLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class DomainTest {
    private fun client(id: String, name: String, phone: String = "") =
        ClientProfile(id = id, name = name, language = Language.RU, phone = phone)

    @Test
    fun `prices round-trip between text and cents`() {
        assertEquals(8500, Money.priceToCents("85"))
        assertEquals(8550, Money.priceToCents(" 85,5 "))
        assertEquals(22050, Money.priceToCents("220.50"))
        assertEquals(0, Money.priceToCents(""))
        assertEquals(0, Money.priceToCents("abc"))
        assertEquals(0, Money.priceToCents("-4"))
        assertEquals("85.00", Money.centsToPrice(8500))
        assertEquals("$220.50", Money.format(22050))
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
}
