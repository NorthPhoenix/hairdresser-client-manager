package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.CalendarAccess
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetup
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetupStep
import com.northphoenix.hairdresserclientmanager.domain.PendingCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The one step of the Calendar Integration setup that is left, from what the phone says. */
class CalendarSetupTest {
    private val now = Instant.parse("2026-11-11T18:00:00Z")
    private val sync = PhoneSync()

    private fun calendar(id: Long, name: String, owner: String = "mom@gmail.com", canWrite: Boolean = true) =
        PhoneCalendar(id = id, accountName = "mom@gmail.com", name = name, ownerAccount = owner, canWrite = canWrite, syncsEvents = true)

    private val main = calendar(1, "mom@gmail.com")
    private val holidays = calendar(2, "Праздники", owner = "ru.russian#holiday@group.v.calendar.google.com", canWrite = false)

    @Test
    fun `without calendar access that is the step, and Android's settings once it cannot ask`() {
        assertEquals(CalendarSetupStep.Access(blocked = false), CalendarSetup.step(CalendarAccess.ASKABLE, emptyList(), null, sync, now))
        assertEquals(CalendarSetupStep.Access(blocked = true), CalendarSetup.step(CalendarAccess.BLOCKED, emptyList(), null, sync, now))
    }

    @Test
    fun `a phone without Google calendars needs a Google account`() {
        assertEquals(CalendarSetupStep.NoGoogleAccount(sync), CalendarSetup.step(CalendarAccess.GRANTED, emptyList(), null, sync, now))
    }

    @Test
    fun `with a Google account the calendar is made`() {
        assertEquals(CalendarSetupStep.Calendar(existing = null), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, holidays), null, sync, now))
    }

    @Test
    fun `a calendar of the app's name that is already there is offered, in either language and spelling`() {
        listOf("Клиенты", "клиенты ", "Clients", "CLIENTS").forEach { name ->
            val own = calendar(5, name, owner = "abc@group.calendar.google.com")

            assertEquals(name, CalendarSetupStep.Calendar(existing = own), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, own), null, sync, now))
        }
    }

    @Test
    fun `one the app may not write to is not offered`() {
        val readOnly = calendar(5, "Клиенты", owner = "shared@group.calendar.google.com", canWrite = false)

        assertEquals(CalendarSetupStep.Calendar(existing = null), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, readOnly), null, sync, now))
    }

    @Test
    fun `a calendar made in Google is waited for, and after a minute the wait is slow`() {
        val pending = PendingCalendar("new@group.calendar.google.com", now.minusSeconds(10))
        val off = PhoneSync(auto = false)

        assertEquals(CalendarSetupStep.Waiting(slow = false, sync = off), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main), pending, off, now))
        assertEquals(CalendarSetupStep.Waiting(slow = true, sync = off), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main), pending, off, now.plusSeconds(50)))
    }

    @Test
    fun `it has arrived once the phone has it under its address in Google`() {
        val made = calendar(9, "Клиенты", owner = "New@group.calendar.google.com")
        val pending = PendingCalendar("new@group.calendar.google.com", now)

        assertEquals(CalendarSetupStep.Arrived(made), CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, made), pending, sync, now))
    }

    @Test
    fun `a calendar of the same name with another address is not the one that was made`() {
        val other = calendar(9, "Клиенты", owner = "old@group.calendar.google.com")
        val pending = PendingCalendar("new@group.calendar.google.com", now)

        assertTrue(CalendarSetup.step(CalendarAccess.GRANTED, listOf(main, other), pending, sync, now) is CalendarSetupStep.Waiting)
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
