package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.state.clientSavedNotice
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
}
