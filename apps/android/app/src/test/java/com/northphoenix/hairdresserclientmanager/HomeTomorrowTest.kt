package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.HomeTomorrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home View's 'Tomorrow': which Appointments it lists, and when a card offers the one-tap reminder. */
class HomeTomorrowTest {
    private fun appointment(
        id: String,
        startsAt: String = "2026-10-22T15:00:00Z",
        status: AppointmentStatus = AppointmentStatus.SCHEDULED,
        participants: List<AppointmentParticipant> = emptyList(),
    ) = Appointment(
        id = id,
        primaryClientId = "c-$id",
        primaryClientName = "Client $id",
        participants = participants,
        startsAt = startsAt,
        status = status,
        locationType = LocationType.IN_SALON,
    )

    private fun primary(phone: String) = AppointmentParticipant(clientId = "c", name = "Ann", phone = phone, isPrimary = true)

    @Test
    fun `tomorrow lists the Scheduled Appointments of the day, earliest first`() {
        val day = listOf(
            appointment("late", "2026-10-22T22:00:00Z"),
            appointment("early", "2026-10-22T14:00:00Z"),
        )

        assertEquals(listOf("early", "late"), HomeTomorrow.scheduled(day).map { it.id })
    }

    @Test
    fun `a canceled, completed or no-show Appointment is not listed`() {
        val day = listOf(
            appointment("a", status = AppointmentStatus.CANCELED),
            appointment("b", status = AppointmentStatus.COMPLETED),
            appointment("c", status = AppointmentStatus.NO_SHOW),
            appointment("d"),
        )

        assertEquals(listOf("d"), HomeTomorrow.scheduled(day).map { it.id })
        assertTrue(HomeTomorrow.scheduled(emptyList()).isEmpty())
    }

    @Test
    fun `the one-tap reminder is offered when the primary Client has a phone`() {
        assertTrue(ClientReminder.canRemindInOneTap(appointment("a", participants = listOf(primary("(512) 555-0100")))))
    }

    @Test
    fun `the one-tap reminder is hidden without a phone, with a phone that is no number, and without a primary Client`() {
        assertFalse(ClientReminder.canRemindInOneTap(appointment("a", participants = listOf(primary("")))))
        assertFalse(ClientReminder.canRemindInOneTap(appointment("a", participants = listOf(primary("n/a")))))
        assertFalse(ClientReminder.canRemindInOneTap(appointment("a")))
    }

    @Test
    fun `in a group Appointment the phone of the primary Client decides, not of the others`() {
        val other = AppointmentParticipant(clientId = "o", name = "Bea", phone = "5125550101")

        assertFalse(ClientReminder.canRemindInOneTap(appointment("a", participants = listOf(primary(""), other))))
        assertTrue(ClientReminder.canRemindInOneTap(appointment("a", participants = listOf(primary("5125550100"), other.copy(phone = "")))))
    }

    @Test
    fun `no reminder is offered for an Appointment that is not Scheduled`() {
        assertFalse(
            ClientReminder.canRemindInOneTap(appointment("a", status = AppointmentStatus.CANCELED, participants = listOf(primary("5125550100")))),
        )
    }
}
