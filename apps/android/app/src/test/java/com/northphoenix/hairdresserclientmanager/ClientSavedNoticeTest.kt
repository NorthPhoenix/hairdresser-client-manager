package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.state.clientSavedNotice
import com.northphoenix.hairdresserclientmanager.ui.screens.NewClientBooking
import com.northphoenix.hairdresserclientmanager.ui.screens.NewClientBooking.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ClientSavedNoticeTest {
    private val vera = ClientProfile(id = "vera", name = "Вера Смирнова", language = Language.RU, phone = "(512) 555-0142")

    @Test
    fun `a Client who was just created is offered for booking`() {
        val booked = mutableListOf<String>()
        val notice = clientSavedNotice(vera, created = true) { booked += it }

        assertEquals(R.string.client_saved, notice.message)
        assertFalse(notice.isError)
        assertEquals(R.string.notice_book_client, notice.action?.label)
        assertEquals(emptyList<String>(), booked)

        notice.action?.run?.invoke()

        assertEquals(listOf("vera"), booked)
    }

    @Test
    fun `a save of a Client who was there before only says it is saved`() {
        val notice = clientSavedNotice(vera, created = false) { error("Nothing is booked") }

        assertEquals(R.string.client_saved, notice.message)
        assertNull(notice.action)
    }

    @Test
    fun `«Записать» opens the form in place of the Client form that has nothing to lose`() {
        assertEquals(Step.IN_PLACE_OF_FORM, NewClientBooking.step("vera", appointmentFormOpen = false, unchangedOnTop = "vera"))
    }

    @Test
    fun `«Записать» opens the form on top of a Client form with changes, or of another screen`() {
        // Typed in since the save: the Client form stays under the new one.
        assertEquals(Step.ON_TOP, NewClientBooking.step("vera", appointmentFormOpen = false, unchangedOnTop = null))
        // The Client form of another Client, or the Clients View after Back.
        assertEquals(Step.ON_TOP, NewClientBooking.step("vera", appointmentFormOpen = false, unchangedOnTop = "anna"))
    }

    @Test
    fun `«Записать» opens no second form while one is open, also under the Client form`() {
        assertEquals(Step.NONE, NewClientBooking.step("vera", appointmentFormOpen = true, unchangedOnTop = "vera"))
        assertEquals(Step.NONE, NewClientBooking.step("vera", appointmentFormOpen = true, unchangedOnTop = null))
    }
}
