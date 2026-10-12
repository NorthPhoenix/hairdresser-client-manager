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

    // --- the inline fields when what the server has changes under them ---

    @Test
    fun `a note field that was not touched follows the Appointment`() {
        // The save of "Bring swatches" is answered: the field holds what was sent, also with the spaces a save drops.
        assertEquals("Bring swatches", UnsavedInput.noteDraft("Bring swatches", shownBefore = "Bring swatches", shown = "Bring swatches"))
        assertEquals("Bring swatches", UnsavedInput.noteDraft("Bring swatches \n", shownBefore = "Bring swatches", shown = "Bring swatches"))
        // A reload brings the note another device saved.
        assertEquals("From the tablet", UnsavedInput.noteDraft("Old note", shownBefore = "Old note", shown = "From the tablet"))
        assertEquals("", UnsavedInput.noteDraft("Old note", shownBefore = "Old note", shown = ""))
    }

    @Test
    fun `text typed into the note while its save was on its way survives the answer, and is unsaved input`() {
        // "Bring swatches" was sent, and the Stylist typed on before the server answered.
        val draft = UnsavedInput.noteDraft("Bring swatches and gloves", shownBefore = "Bring swatches", shown = "Bring swatches")

        assertEquals("Bring swatches and gloves", draft)
        assertTrue(UnsavedInput.appointmentNote(draft, "Bring swatches"))

        // A note erased meanwhile stays erased.
        assertEquals("", UnsavedInput.noteDraft("", shownBefore = "Bring swatches", shown = "Bring swatches"))
        // Of two saves in a row the first is answered while the second is shown: the field holds the second, and stays.
        assertEquals("Second", UnsavedInput.noteDraft("Second", shownBefore = "Second", shown = "Second"))
        // Unsaved text is not replaced by what a reload brings either.
        assertEquals("Half a thought", UnsavedInput.noteDraft("Half a thought", shownBefore = "Old note", shown = "From the tablet"))
    }

    @Test
    fun `a final total field that was not touched follows the Appointment, written as an amount`() {
        // "90" was sent and saved: the field shows the override as it is written everywhere.
        assertEquals("90.00", UnsavedInput.finalTotalDraft("90", shownBeforeCents = 9000, shownCents = 9000))
        assertEquals("90.00", UnsavedInput.finalTotalDraft("90.00", shownBeforeCents = 9000, shownCents = 9000))
        // 'Clear override' empties the field with the tap, and its answer leaves it empty.
        assertEquals("", UnsavedInput.finalTotalDraft("", shownBeforeCents = null, shownCents = null))
        // A reload brings an override from another device, or its removal.
        assertEquals("75.50", UnsavedInput.finalTotalDraft("", shownBeforeCents = null, shownCents = 7550))
        assertEquals("75.50", UnsavedInput.finalTotalDraft("90.00", shownBeforeCents = 9000, shownCents = 7550))
        assertEquals("", UnsavedInput.finalTotalDraft("90.00", shownBeforeCents = 9000, shownCents = null))
    }

    @Test
    fun `an amount typed into the final total while its save was on its way survives the answer`() {
        // "90" was sent, and the Stylist went on to "905".
        val draft = UnsavedInput.finalTotalDraft("905", shownBeforeCents = 9000, shownCents = 9000)

        assertEquals("905", draft)
        assertTrue(UnsavedInput.finalTotal(draft, savedOverrideCents = 9000))

        // The field was cleared to type another amount: the one just saved does not come back into it.
        assertEquals("", UnsavedInput.finalTotalDraft("", shownBeforeCents = 9000, shownCents = 9000))
        // Typed while 'Clear override' was on its way.
        assertEquals("40", UnsavedInput.finalTotalDraft("40", shownBeforeCents = null, shownCents = null))
        // Text that is no amount is something typed as well.
        assertEquals("abc", UnsavedInput.finalTotalDraft("abc", shownBeforeCents = 9000, shownCents = 9000))
    }

    @Test
    fun `a save that fails leaves the text in its field, where it is unsaved again`() {
        // What the server has did not change, so the field is not looked at: the screen goes back to the saved note...
        val typed = "Bring swatches"
        val savedNote = "Old note"

        // ...and the text is unsaved input against it, as before the save.
        assertTrue(UnsavedInput.appointmentNote(typed, savedNote))
        assertTrue(UnsavedInput.finalTotal("90", savedOverrideCents = null))

        // When a reload then brings another note, the text that could not be saved is still not replaced.
        assertEquals(typed, UnsavedInput.noteDraft(typed, shownBefore = savedNote, shown = "From the tablet"))
        assertEquals("90", UnsavedInput.finalTotalDraft("90", shownBeforeCents = null, shownCents = 7550))
    }
}
