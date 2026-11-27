package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.state.clientSavedNotice
import com.northphoenix.hairdresserclientmanager.state.contactsImportedNotice
import com.northphoenix.hairdresserclientmanager.ui.screens.BookNewClient
import com.northphoenix.hairdresserclientmanager.ui.screens.BookNewClient.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ClientSavedNoticeTest {
    private val vera = ClientProfile(id = "vera", name = "Вера Смирнова", language = Language.RU, phone = "(512) 555-0142")

    @Test
    fun `a Client who was just created is offered for booking`() {
        val booked = mutableListOf<String>()
        val notice = clientSavedNotice(vera, created = true, offersToBook = true) { booked += it }

        assertEquals(R.string.client_saved, notice.message)
        assertFalse(notice.isError)
        assertEquals(R.string.notice_book_client, notice.action?.label)
        assertEquals(emptyList<String>(), booked)

        notice.action?.run?.invoke()

        assertEquals(listOf("vera"), booked)
    }

    @Test
    fun `a save of a Client who was there before only says it is saved`() {
        // The Client form of an existing Client offers to book too: the save is what decides.
        val notice = clientSavedNotice(vera, created = false, offersToBook = true) { error("Nothing is booked") }

        assertEquals(R.string.client_saved, notice.message)
        assertNull(notice.action)
    }

    @Test
    fun `a new Client saved from a form that offers no booking only says it is saved`() {
        val notice = clientSavedNotice(vera, created = true, offersToBook = false) { error("Nothing is booked") }

        assertNull(notice.action)
    }

    @Test
    fun `«Записать» opens the form in place of the Client form that has nothing to lose`() {
        assertEquals(Step.IN_PLACE_OF_FORM, BookNewClient.step("vera", appointmentFormOpen = false, unchangedOnTop = "vera"))
    }

    @Test
    fun `«Записать» opens the form on top of a Client form with changes, or of another screen`() {
        // Typed in since the save: the Client form stays under the new one.
        assertEquals(Step.ON_TOP, BookNewClient.step("vera", appointmentFormOpen = false, unchangedOnTop = null))
        // The Client form of another Client, or the Clients View after Back.
        assertEquals(Step.ON_TOP, BookNewClient.step("vera", appointmentFormOpen = false, unchangedOnTop = "anna"))
    }

    @Test
    fun `«Записать» opens no second form while one is open, also under the Client form`() {
        assertEquals(Step.NONE, BookNewClient.step("vera", appointmentFormOpen = true, unchangedOnTop = "vera"))
        assertEquals(Step.NONE, BookNewClient.step("vera", appointmentFormOpen = true, unchangedOnTop = null))
    }

    @Test
    fun `a contact imported alone is offered for booking, as a Client created in the form is`() {
        val booked = mutableListOf<String>()
        val notice = contactsImportedNotice(addedInAll = 1, added = listOf("vera")) { booked += it }

        assertEquals(R.plurals.contact_import_added, notice.message)
        assertEquals(1, notice.quantity)
        assertEquals(listOf<Any>(1), notice.args)
        assertFalse(notice.isError)
        assertEquals(R.string.notice_book_client, notice.action?.label)
        assertEquals(emptyList<String>(), booked)

        notice.action?.run?.invoke()

        assertEquals(listOf("vera"), booked)
    }

    @Test
    fun `an import of several Clients only says how many`() {
        val several = contactsImportedNotice(addedInAll = 3, added = listOf("vera", "anna", "olga")) { error("Nothing is booked") }

        assertEquals(3, several.quantity)
        assertEquals(listOf<Any>(3), several.args)
        assertNull(several.action)

        // A retry that added the one left over, after a first try that added another: two Clients in all.
        val retried = contactsImportedNotice(addedInAll = 2, added = listOf("vera")) { error("Nothing is booked") }

        assertEquals(2, retried.quantity)
        assertNull(retried.action)
    }
}
