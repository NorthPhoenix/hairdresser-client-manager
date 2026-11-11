package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.CalendarAccess
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetup
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetupStep
import com.northphoenix.hairdresserclientmanager.domain.PhoneCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one step of the Calendar Integration setup that is left, from what the phone says. */
class CalendarSetupTest {
    private val sync = PhoneSync()

    private fun calendar(id: Long, name: String, owner: String = "mom@gmail.com", canWrite: Boolean = true) =
        PhoneCalendar(id = id, accountName = "mom@gmail.com", name = name, ownerAccount = owner, canWrite = canWrite, syncsEvents = true)

    private val main = calendar(1, "mom@gmail.com")
    private val holidays = calendar(2, "Праздники", owner = "ru.russian#holiday@group.v.calendar.google.com", canWrite = false)

    @Test
    fun `without calendar access that is the step, and Android's settings once it cannot ask`() {
        assertEquals(CalendarSetupStep.Access(blocked = false), CalendarSetup.step(CalendarAccess.ASKABLE, emptyList(), sync))
        assertEquals(CalendarSetupStep.Access(blocked = true), CalendarSetup.step(CalendarAccess.BLOCKED, emptyList(), sync))
    }

    @Test
    fun `a phone without Google calendars needs a Google account`() {
        assertEquals(CalendarSetupStep.NoGoogleAccount(sync), CalendarSetup.step(CalendarAccess.GRANTED, emptyList(), sync))
    }

    @Test
    fun `with a Google account the calendar is made`() {
        assertEquals(CalendarSetupStep.Calendar(existing = null, sync = sync), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, holidays), sync))
    }

    @Test
    fun `a calendar of the app's name that is already there is offered, in either language and spelling`() {
        listOf("Клиенты", "клиенты ", "Clients", "CLIENTS").forEach { name ->
            val own = calendar(5, name, owner = "abc@group.calendar.google.com")

            assertEquals(name, CalendarSetupStep.Calendar(existing = own, sync = sync), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, own), sync))
        }
    }

    @Test
    fun `one the app may not write to is not offered`() {
        val readOnly = calendar(5, "Клиенты", owner = "shared@group.calendar.google.com", canWrite = false)

        assertEquals(CalendarSetupStep.Calendar(existing = null, sync = sync), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, readOnly), sync))
    }

    @Test
    fun `while it is made, the step says whether sync can keep it from the phone`() {
        val off = PhoneSync(auto = false)
        val accountOff = PhoneSync(calendarOffFor = setOf("mom@gmail.com"))

        assertEquals(CalendarSetupStep.Calendar(existing = null, sync = off), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main), off))
        assertTrue(off.off)
        assertTrue(accountOff.off)
        assertFalse(sync.off)
    }

    @Test
    fun `a calendar of the app's name made on calendar google com is offered, and found again by its address`() {
        val made = calendar(9, "Клиенты", owner = "new@group.calendar.google.com")

        assertEquals(made, CalendarSetup.find(listOf(main, made), "New@group.calendar.google.com"))
        assertEquals(CalendarSetupStep.Calendar(existing = made, sync = sync), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, made), sync))
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
