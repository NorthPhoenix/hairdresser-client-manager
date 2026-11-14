package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.CalendarAccess
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetup
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetupStep
import com.northphoenix.hairdresserclientmanager.domain.PhoneCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one step of the Calendar Integration setup that is left, from what the phone says. */
class CalendarSetupTest {

    private fun calendar(id: Long, name: String, owner: String = "mom@gmail.com", canWrite: Boolean = true) =
        PhoneCalendar(id = id, accountName = "mom@gmail.com", name = name, ownerAccount = owner, canWrite = canWrite, syncsEvents = true)

    private val main = calendar(1, "mom@gmail.com")
    private val holidays = calendar(2, "Праздники", owner = "ru.russian#holiday@group.v.calendar.google.com", canWrite = false)

    @Test
    fun `without calendar access that is the step, and Android's settings once it cannot ask`() {
        assertEquals(CalendarSetupStep.Access(blocked = false), CalendarSetup.step(CalendarAccess.ASKABLE, emptyList(), autoSync = true))
        assertEquals(CalendarSetupStep.Access(blocked = true), CalendarSetup.step(CalendarAccess.BLOCKED, emptyList(), autoSync = true))
    }

    @Test
    fun `a phone without Google calendars needs a Google account`() {
        assertEquals(CalendarSetupStep.NoGoogleAccount(autoSync = true), CalendarSetup.step(CalendarAccess.GRANTED, emptyList(), autoSync = true))
    }

    @Test
    fun `with a Google account the calendar is made`() {
        assertEquals(CalendarSetupStep.Calendar(existing = null, accounts = listOf("mom@gmail.com"), autoSync = true), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, holidays), autoSync = true))
    }

    @Test
    fun `a calendar of the app's name that is already there is offered, in either language and spelling`() {
        listOf("Клиенты", "клиенты ", "Clients", "CLIENTS").forEach { name ->
            val own = calendar(5, name, owner = "abc@group.calendar.google.com")

            assertEquals(name, CalendarSetupStep.Calendar(existing = own, accounts = listOf("mom@gmail.com"), autoSync = true), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, own), autoSync = true))
        }
    }

    @Test
    fun `one the app may not write to is not offered`() {
        val readOnly = calendar(5, "Клиенты", owner = "shared@group.calendar.google.com", canWrite = false)

        assertEquals(CalendarSetupStep.Calendar(existing = null, accounts = listOf("mom@gmail.com"), autoSync = true), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, readOnly), autoSync = true))
    }

    @Test
    fun `it can be made in any Google account on the phone`() {
        val work = calendar(3, "work@gmail.com").copy(accountName = "work@gmail.com", ownerAccount = "work@gmail.com")
        val test = calendar(4, "test@gmail.com").copy(accountName = "test@gmail.com", ownerAccount = "test@gmail.com")
        val calendars = listOf(main, holidays, work, test)

        val all = CalendarSetup.step(CalendarAccess.GRANTED, calendars, autoSync = true) as CalendarSetupStep.Calendar
        assertEquals(listOf("mom@gmail.com", "work@gmail.com", "test@gmail.com"), all.accounts)

        // Auto-sync off is said with the step; the accounts stay what they are.
        val autoOff = CalendarSetup.step(CalendarAccess.GRANTED, calendars, autoSync = false) as CalendarSetupStep.Calendar
        assertFalse(autoOff.autoSync)
        assertEquals(all.accounts, autoOff.accounts)
    }

    @Test
    fun `a calendar of the app's name made on calendar google com is offered, and found again by its address`() {
        val made = calendar(9, "Клиенты", owner = "new@group.calendar.google.com")

        assertEquals(made, CalendarSetup.find(listOf(main, made), "New@group.calendar.google.com"))
        assertEquals(CalendarSetupStep.Calendar(existing = made, accounts = listOf("mom@gmail.com"), autoSync = true), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, made), autoSync = true))
    }

    @Test
    fun `the choices are the calendars the app may write to, its own name first, then the main calendar`() {
        val work = calendar(3, "Работа", owner = "w@group.calendar.google.com")
        val own = calendar(4, "Клиенты", owner = "c@group.calendar.google.com")

        assertEquals(listOf(own, main, work), CalendarSetup.choices(listOf(work, holidays, main, own)))
    }

    @Test
    fun `the main calendar is the one named after the account`() {
        assertTrue(main.isMain)
        assertFalse(holidays.isMain)
    }
}
