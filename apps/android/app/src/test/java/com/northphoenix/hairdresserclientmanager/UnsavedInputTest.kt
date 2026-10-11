package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.UnsavedInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Back asks before it discards input, and only when something was changed. */
class UnsavedInputTest {
    // --- Client form ---

    private fun clientForm(
        name: String = "Anna Petrova",
        language: Language = Language.RU,
        phone: String = "+1 555 0100",
        email: String = "",
        address: String = "",
        note: String = "Prefers soft layers.",
    ) = UnsavedInput.clientForm(name, language, phone, email, address, note)

    @Test
    fun `a Client form that only gained spaces around its values has nothing unsaved`() {
        assertEquals(clientForm(), clientForm(name = " Anna Petrova  ", phone = "+1 555 0100 ", email = " ", note = "Prefers soft layers.\n"))
    }

    @Test
    fun `any changed field of the Client form is unsaved input`() {
        val saved = clientForm()

        assertNotEquals(saved, clientForm(name = "Anna Petrov"))
        assertNotEquals(saved, clientForm(language = Language.EN))
        assertNotEquals(saved, clientForm(phone = "+1 555 0101"))
        assertNotEquals(saved, clientForm(email = "lost@example.com"))
        assertNotEquals(saved, clientForm(address = "1 Lost Lane"))
        assertNotEquals(saved, clientForm(note = "unsaved note"))
        // Text moved from one field to another is a change too.
        assertNotEquals(clientForm(email = "x", address = ""), clientForm(email = "", address = "x"))
    }

    @Test
    fun `an untouched New Client form has nothing unsaved`() {
        val opened = UnsavedInput.clientForm("", Language.RU, "", "", "", "")

        assertEquals(opened, UnsavedInput.clientForm(" ", Language.RU, "", "", "", ""))
        assertNotEquals(opened, UnsavedInput.clientForm("Lost", Language.RU, "555", "", "", "text"))
    }

    // --- New Appointment draft ---

    private fun draft(
        primaryClientId: String? = null,
        inlineClientName: String = "",
        additionalClientIds: List<String> = emptyList(),
        date: LocalDate = LocalDate.of(2026, 10, 10),
        startTime: LocalTime = LocalTime.of(15, 0),
        endTime: LocalTime? = null,
        locationType: LocationType = LocationType.IN_SALON,
        address: String = "",
    ) = UnsavedInput.newAppointment(primaryClientId, inlineClientName, additionalClientIds, date, startTime, endTime, locationType, address)

    @Test
    fun `any choice made in a New Appointment draft is unsaved input`() {
        val opened = draft()

        assertNotEquals(opened, draft(primaryClientId = "client-1"))
        assertNotEquals(opened, draft(inlineClientName = "Walk"))
        assertNotEquals(draft(primaryClientId = "client-1"), draft(primaryClientId = "client-1", additionalClientIds = listOf("client-2")))
        assertNotEquals(opened, draft(date = LocalDate.of(2026, 10, 24)))
        assertNotEquals(opened, draft(startTime = LocalTime.of(16, 0)))
        assertNotEquals(opened, draft(endTime = LocalTime.of(16, 0)))
        assertNotEquals(opened, draft(locationType = LocationType.AT_HOME))
        assertNotEquals(draft(locationType = LocationType.AT_HOME), draft(locationType = LocationType.AT_HOME, address = "1 Lost Lane"))
    }

    @Test
    fun `a New Appointment draft put back to how it opened has nothing unsaved`() {
        val opened = draft()

        // A Client picked and removed again, a date changed and changed back.
        assertEquals(opened, draft(primaryClientId = null, inlineClientName = " "))
        // An address typed for at home is neither shown nor sent once the draft is in salon again.
        assertEquals(opened, draft(locationType = LocationType.IN_SALON, address = "1 Lost Lane"))
    }

    // --- inline fields of the Appointment ---

    @Test
    fun `an Appointment note is unsaved only when its text differs from the saved one`() {
        assertFalse(UnsavedInput.appointmentNote("Saved note", "Saved note"))
        assertFalse(UnsavedInput.appointmentNote("Saved note \n", "Saved note"))
        assertFalse(UnsavedInput.appointmentNote("", ""))
        assertTrue(UnsavedInput.appointmentNote("Saved note plus unsaved edit", "Saved note"))
        // Erasing a saved note is a change that would be lost as well.
        assertTrue(UnsavedInput.appointmentNote("", "Saved note"))
    }

    @Test
    fun `a final total is unsaved only when the amount typed differs from the saved override`() {
        assertTrue(UnsavedInput.finalTotal("77.77", savedOverrideCents = null))
        assertTrue(UnsavedInput.finalTotal("90", savedOverrideCents = 8500))
        // Zero is an override of its own, not the absence of one.
        assertTrue(UnsavedInput.finalTotal("0", savedOverrideCents = null))

        assertFalse(UnsavedInput.finalTotal("85.00", savedOverrideCents = 8500))
        // The same amount written another way.
        assertFalse(UnsavedInput.finalTotal("85", savedOverrideCents = 8500))
        // An empty field has nothing in it to save, with or without a saved override.
        assertFalse(UnsavedInput.finalTotal("", savedOverrideCents = null))
        assertFalse(UnsavedInput.finalTotal(" ", savedOverrideCents = 8500))
    }

    @Test
    fun `text in the final total that is not an amount is unsaved input too`() {
        // It is refused when saved, and it is still something typed that leaving would lose.
        assertTrue(UnsavedInput.finalTotal("abc", savedOverrideCents = null))
        assertTrue(UnsavedInput.finalTotal("abc", savedOverrideCents = 0))
        assertTrue(UnsavedInput.finalTotal("-20", savedOverrideCents = 5000))
        assertTrue(UnsavedInput.finalTotal("99999999999", savedOverrideCents = 5000))
        // A thousands separator is read as one: this is the saved 1200.00 and not 1.20.
        assertFalse(UnsavedInput.finalTotal("1,200", savedOverrideCents = 120000))
        assertTrue(UnsavedInput.finalTotal("1,200", savedOverrideCents = 120))
    }

    @Test
    fun `an untouched override from before amounts had a maximum is not unsaved input`() {
        val saved = 2147483647L

        assertFalse(UnsavedInput.finalTotal(Money.centsToPrice(saved), savedOverrideCents = saved))
        assertTrue(UnsavedInput.finalTotal("21474836.48", savedOverrideCents = saved))
    }
}
