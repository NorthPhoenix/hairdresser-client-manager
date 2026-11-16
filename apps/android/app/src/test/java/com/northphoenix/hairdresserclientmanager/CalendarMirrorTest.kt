package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ColorFormula
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.CalendarEventRow
import com.northphoenix.hairdresserclientmanager.domain.CalendarMirror
import com.northphoenix.hairdresserclientmanager.domain.MirrorStep
import com.northphoenix.hairdresserclientmanager.domain.MirrorWords
import com.northphoenix.hairdresserclientmanager.domain.WrittenEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What an Appointment's event says (ADR 0003), and what mirroring does to the calendar. */
class CalendarMirrorTest {
    private val words = object : MirrorWords {
        override fun services(names: String) = "Услуги: $names"

        override fun clients(names: String) = "Клиенты: $names"

        override fun phone(number: String) = "Телефон: $number"

        override val footer = "Из приложения."
    }

    private val connectedAt = Instant.parse("2026-11-10T00:00:00Z")

    private fun participant(id: String, name: String, phone: String = "") = AppointmentParticipant(clientId = id, name = name, phone = phone)

    private fun appointment(
        id: String = "a1",
        startsAt: String = "2026-11-12T15:00:00Z",
        endsAt: String? = null,
        status: AppointmentStatus = AppointmentStatus.SCHEDULED,
        participants: List<AppointmentParticipant> = listOf(participant("c1", "Наташа от Лены", "5129174528")),
        services: List<AppointmentService> = emptyList(),
        address: String = "ул. Ленина, 5",
    ) = Appointment(
        id = id,
        primaryClientId = participants.first().clientId,
        primaryClientName = participants.first().name,
        participants = participants,
        services = services,
        serviceTotalCents = services.sumOf { it.priceCents },
        finalTotalCents = services.sumOf { it.priceCents },
        tipCents = 1500,
        startsAt = startsAt,
        endsAt = endsAt,
        status = status,
        locationType = LocationType.IN_SALON,
        locationAddress = address,
        note = "Аллергия на аммиак",
    )

    private fun service(name: String, clientId: String = "c1", formula: String? = null) = AppointmentService(
        id = name + clientId,
        clientId = clientId,
        name = name,
        priceCents = 450_00,
        note = "Заметка к услуге",
        colorFormulas = listOfNotNull(formula?.let { ColorFormula(id = "f-$name", formula = it) }),
    )

    @Test
    fun `an event is named after the primary Client, at the Appointment's place, in the Stylist's timezone`() {
        val event = CalendarMirror.event(appointment(endsAt = "2026-11-12T16:30:00Z"), "America/Chicago", words)!!

        assertEquals("a1", event.appointmentId)
        assertEquals("Наташа от Лены", event.title)
        assertEquals("ул. Ленина, 5", event.location)
        assertEquals(Instant.parse("2026-11-12T15:00:00Z").toEpochMilli(), event.startMillis)
        assertEquals(Instant.parse("2026-11-12T16:30:00Z").toEpochMilli(), event.endMillis)
        assertEquals("America/Chicago", event.timezone)
    }

    @Test
    fun `an Appointment without an end time is an hour long`() {
        val event = CalendarMirror.event(appointment(), "Europe/Moscow", words)!!

        assertEquals(60 * 60 * 1000L, event.endMillis - event.startMillis)
    }

    @Test
    fun `an end time that is not after the start is an hour long too`() {
        val event = CalendarMirror.event(appointment(endsAt = "2026-11-12T15:00:00Z"), "Europe/Moscow", words)!!

        assertEquals(60 * 60 * 1000L, event.endMillis - event.startMillis)
    }

    @Test
    fun `the description has the Services by name and the phone, never formulas, notes, prices or tips`() {
        val event = CalendarMirror.event(
            appointment(services = listOf(service("Стрижка", formula = "7.1 + 6% 1:2"), service("Укладка"))),
            "Europe/Moscow",
            words,
        )!!

        assertEquals("Услуги: Стрижка, Укладка\nТелефон: (512) 917-4528\nИз приложения.", event.description)
        listOf("7.1", "Заметка", "Аллергия", "450", "15").forEach { assertFalse(it, event.description.contains(it)) }
    }

    @Test
    fun `a Service named twice is named once with how many there are`() {
        val event = CalendarMirror.event(
            appointment(services = listOf(service("Стрижка"), service("Укладка"), service("Стрижка", clientId = "c2"))),
            "Europe/Moscow",
            words,
        )!!

        assertTrue(event.description.startsWith("Услуги: Стрижка ×2, Укладка\n"))
    }

    @Test
    fun `an Appointment without Services or a phone has only the last line`() {
        val event = CalendarMirror.event(appointment(participants = listOf(participant("c1", "Миша"))), "Europe/Moscow", words)!!

        assertEquals("Из приложения.", event.description)
    }

    @Test
    fun `a group Appointment is one event that counts and names the other Clients`() {
        val group = appointment(participants = listOf(participant("c1", "Мама", "5129174528"), participant("c2", "Миша"), participant("c3", "Лиза")))
        val event = CalendarMirror.event(group, "Europe/Moscow", words)!!

        assertEquals("Мама +2", event.title)
        assertEquals("Клиенты: Мама, Миша, Лиза\nТелефон: (512) 917-4528\nИз приложения.", event.description)
    }

    @Test
    fun `another primary Client is another event`() {
        val before = CalendarMirror.event(appointment(participants = listOf(participant("c1", "Мама"), participant("c2", "Миша"))), "Europe/Moscow", words)!!
        val after = CalendarMirror.event(appointment(participants = listOf(participant("c2", "Миша"), participant("c1", "Мама"))), "Europe/Moscow", words)!!

        assertEquals("Миша +1", after.title)
        assertNotEquals(before.fingerprint, after.fingerprint)
    }

    @Test
    fun `mirrored are the Appointments from the connection on, except canceled ones`() {
        val appointments = listOf(
            appointment(id = "before", startsAt = "2026-11-09T23:59:00Z"),
            appointment(id = "scheduled"),
            appointment(id = "completed", status = AppointmentStatus.COMPLETED),
            appointment(id = "no-show", status = AppointmentStatus.NO_SHOW),
            appointment(id = "canceled", status = AppointmentStatus.CANCELED),
            appointment(id = "at-connection", startsAt = "2026-11-10T00:00:00Z"),
        )

        val events = CalendarMirror.events(appointments, connectedAt, "Europe/Moscow", words)

        assertEquals(listOf("scheduled", "completed", "no-show", "at-connection"), events.map { it.appointmentId })
    }

    @Test
    fun `completing an Appointment does not change its event`() {
        val scheduled = CalendarMirror.event(appointment(), "Europe/Moscow", words)!!
        val completed = CalendarMirror.event(appointment(status = AppointmentStatus.COMPLETED), "Europe/Moscow", words)!!

        assertEquals(scheduled.fingerprint, completed.fingerprint)
    }

    @Test
    fun `the tag names the Appointment, and other tags name none`() {
        assertEquals("a1", CalendarMirror.appointmentOfTag(CalendarMirror.tag("a1")))
        assertNull(CalendarMirror.appointmentOfTag(null))
        assertNull(CalendarMirror.appointmentOfTag("content://other/1"))
        assertNull(CalendarMirror.appointmentOfTag(CalendarMirror.tag("")))
    }

    // --- plan ---

    private fun event(id: String, title: String = "Наташа") = CalendarMirror.event(appointment(id = id), "Europe/Moscow", words)!!.copy(title = title)

    @Test
    fun `an Appointment without an event gets one`() {
        val steps = CalendarMirror.plan(listOf(event("a1")), emptyMap(), emptyList())

        assertEquals(listOf(MirrorStep.Insert(event("a1"))), steps)
    }

    @Test
    fun `an event that says what it should is kept`() {
        val written = mapOf("a1" to WrittenEvent(7, event("a1").fingerprint))

        val steps = CalendarMirror.plan(listOf(event("a1")), written, listOf(CalendarEventRow(7, "a1")))

        assertEquals(listOf(MirrorStep.Keep("a1", written.getValue("a1"))), steps)
    }

    @Test
    fun `an event that should say something else is changed`() {
        val written = mapOf("a1" to WrittenEvent(7, event("a1").fingerprint))

        val steps = CalendarMirror.plan(listOf(event("a1", title = "Миша")), written, listOf(CalendarEventRow(7, "a1")))

        assertEquals(listOf(MirrorStep.Update(7, event("a1", title = "Миша"))), steps)
    }

    @Test
    fun `an event deleted in Google Calendar is written again`() {
        val written = mapOf("a1" to WrittenEvent(7, event("a1").fingerprint))

        val steps = CalendarMirror.plan(listOf(event("a1")), written, emptyList())

        assertEquals(listOf(MirrorStep.Insert(event("a1"))), steps)
    }

    @Test
    fun `an event the app does not remember is found by its tag and written over`() {
        val steps = CalendarMirror.plan(listOf(event("a1")), emptyMap(), listOf(CalendarEventRow(9, "a1")))

        assertEquals(listOf(MirrorStep.Update(9, event("a1"))), steps)
    }

    @Test
    fun `the event the app remembers is kept even when Google dropped its tag`() {
        val written = mapOf("a1" to WrittenEvent(7, event("a1").fingerprint))

        val steps = CalendarMirror.plan(listOf(event("a1")), written, listOf(CalendarEventRow(7, null)))

        assertEquals(listOf(MirrorStep.Keep("a1", written.getValue("a1"))), steps)
    }

    @Test
    fun `the event of an Appointment that is no longer mirrored is deleted`() {
        val written = mapOf("gone" to WrittenEvent(7, "x"))

        val steps = CalendarMirror.plan(emptyList(), written, listOf(CalendarEventRow(7, "gone"), CalendarEventRow(8, "tagged-only")))

        assertEquals(listOf(MirrorStep.Delete(7), MirrorStep.Delete(8)), steps)
    }

    @Test
    fun `a second event tagged with the same Appointment is deleted`() {
        val written = mapOf("a1" to WrittenEvent(7, event("a1").fingerprint))

        val steps = CalendarMirror.plan(listOf(event("a1")), written, listOf(CalendarEventRow(7, "a1"), CalendarEventRow(8, "a1")))

        assertEquals(listOf(MirrorStep.Keep("a1", written.getValue("a1")), MirrorStep.Delete(8)), steps)
    }

    @Test
    fun `events that are not the app's are never touched`() {
        val steps = CalendarMirror.plan(emptyList(), emptyMap(), listOf(CalendarEventRow(3, null), CalendarEventRow(4, null)))

        assertTrue(steps.isEmpty())
    }
}
