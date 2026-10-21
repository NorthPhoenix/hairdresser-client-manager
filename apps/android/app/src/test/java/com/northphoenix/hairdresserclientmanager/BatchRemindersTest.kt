package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.BatchReminders
import com.northphoenix.hairdresserclientmanager.domain.Reminded
import com.northphoenix.hairdresserclientmanager.domain.ReminderLog
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 'Remind all': which Appointments are listed and checked, and the device-local log of reminders. */
class BatchRemindersTest {
    private val now = Instant.parse("2026-10-21T15:00:00Z")

    private fun appointment(
        id: String,
        phone: String = "5125550100",
        status: AppointmentStatus = AppointmentStatus.SCHEDULED,
        startsAt: String = "2026-10-22T15:00:00Z",
        primary: Boolean = true,
    ) = Appointment(
        id = id,
        primaryClientId = "c-$id",
        primaryClientName = "Client $id",
        participants = listOf(AppointmentParticipant(clientId = "c-$id", name = "Ann", phone = phone, isPrimary = primary)),
        startsAt = startsAt,
        status = status,
        locationType = LocationType.IN_SALON,
    )

    @Test
    fun `only Appointments with a Client who can be texted are listed`() {
        val day = listOf(
            appointment("ok"),
            appointment("no-phone", phone = ""),
            appointment("not-a-number", phone = "n/a"),
            appointment("canceled", status = AppointmentStatus.CANCELED),
            appointment("no-primary", primary = false),
        )

        assertEquals(listOf("ok"), BatchReminders.remindable(day, now).map { it.id })
    }

    @Test
    fun `a day nobody can be reminded of lists nothing`() {
        assertEquals(emptyList<Appointment>(), BatchReminders.remindable(listOf(appointment("a", phone = "")), now))
        assertEquals(emptyList<Appointment>(), BatchReminders.remindable(emptyList(), now))
    }

    @Test
    fun `all start checked when none was reminded`() {
        val day = listOf(appointment("a"), appointment("b"))

        assertEquals(setOf("a", "b"), BatchReminders.initiallyChecked(day, emptyMap()))
    }

    @Test
    fun `an Appointment reminded for its current time starts unchecked`() {
        val day = listOf(appointment("a"), appointment("b"))
        val log = mapOf("a" to Reminded("a", "2026-10-22T15:00:00Z", now))

        assertEquals(setOf("b"), BatchReminders.initiallyChecked(day, log))
    }

    @Test
    fun `an Appointment moved to another time since its reminder starts checked again`() {
        val moved = appointment("a", startsAt = "2026-10-22T16:00:00Z")
        val log = mapOf("a" to Reminded("a", "2026-10-22T15:00:00Z", now))

        assertEquals(setOf("a"), BatchReminders.initiallyChecked(listOf(moved), log))
        assertNull(ReminderLog.remindedAt(log, moved))
    }

    @Test
    fun `the log round-trips through its stored text`() {
        val entry = Reminded("clx1", "2026-10-22T15:00:00Z", Instant.parse("2026-10-21T01:30:00Z"))

        assertEquals(entry, ReminderLog.decode(ReminderLog.encode(entry)))
    }

    @Test
    fun `stored text that is not an entry is skipped`() {
        assertNull(ReminderLog.decode(""))
        assertNull(ReminderLog.decode("a|b|c"))
        assertNull(ReminderLog.decode("a|123"))
    }

    @Test
    fun `old reminders are forgotten and recent ones are kept`() {
        val old = Reminded("old", "2026-08-01T15:00:00Z", Instant.parse("2026-08-01T10:00:00Z"))
        val recent = Reminded("recent", "2026-10-22T15:00:00Z", Instant.parse("2026-10-20T10:00:00Z"))

        assertEquals(setOf("recent"), ReminderLog.pruned(mapOf("old" to old, "recent" to recent), now).keys)
    }

    @Test
    fun `a reminder is stamped with its time today and with its day before`() {
        val format = TimeFormat(Locale.US, ZoneId.of("America/Chicago"), uses24HourClock = true)
        // 19:30 on Oct 21 in Chicago.
        val at = Instant.parse("2026-10-22T00:30:00Z")

        assertEquals("19:30", format.stamp(at, Instant.parse("2026-10-22T02:00:00Z")))
        assertEquals("Oct 21, 19:30", format.stamp(at, Instant.parse("2026-10-22T14:00:00Z")))
    }
}
