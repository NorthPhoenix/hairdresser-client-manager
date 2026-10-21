package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.HomeOutcomes
import com.northphoenix.hairdresserclientmanager.domain.HomeOutcomes.Undo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Quick outcomes on the Home View: which cards offer them, the 'All completed' row of a day, and 'Undo'. */
class HomeOutcomesTest {
    private val chicago = ZoneId.of("America/Chicago")

    private fun appointment(id: String, startsAt: String, status: AppointmentStatus = AppointmentStatus.SCHEDULED) = Appointment(
        id = id,
        primaryClientId = "c-$id",
        primaryClientName = "Client $id",
        startsAt = startsAt,
        status = status,
        locationType = LocationType.IN_SALON,
    )

    // --- which cards offer the chips ---

    @Test
    fun `a Scheduled Appointment offers its outcomes from its start time on, and not before`() {
        val twoPm = appointment("a", "2026-10-20T19:00:00Z")

        assertFalse(HomeOutcomes.needsOutcome(twoPm, Instant.parse("2026-10-20T18:59:59Z")))
        assertTrue(HomeOutcomes.needsOutcome(twoPm, Instant.parse("2026-10-20T19:00:00Z")))
        assertTrue(HomeOutcomes.needsOutcome(twoPm, Instant.parse("2026-10-21T09:00:00Z")))
    }

    @Test
    fun `an Appointment with an outcome offers none, also when it is past`() {
        val now = Instant.parse("2026-10-21T09:00:00Z")

        for (status in listOf(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW)) {
            assertFalse(status.name, HomeOutcomes.needsOutcome(appointment("a", "2026-10-16T18:00:00Z", status), now))
        }
    }

    @Test
    fun `the chips are the three outcomes, in the order of the Appointment's own screen, without Scheduled`() {
        assertEquals(listOf(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW), HomeOutcomes.choices)
    }

    // --- the days of 'Needs an outcome' ---

    @Test
    fun `unresolved Appointments are grouped by the day they start on in the Stylist's timezone, in the order of the list`() {
        val friday1 = appointment("f1", "2026-10-16T18:00:00Z")
        val friday2 = appointment("f2", "2026-10-16T23:00:00Z")
        // 21:00 on Friday in Chicago is Saturday in UTC.
        val friday3 = appointment("f3", "2026-10-17T02:00:00Z")
        val saturday = appointment("s1", "2026-10-17T16:00:00Z")

        val days = HomeOutcomes.byDay(listOf(friday1, friday2, friday3, saturday), chicago)

        assertEquals(listOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17)), days.map { it.day })
        assertEquals(listOf("f1", "f2", "f3"), days[0].appointments.map { it.id })
        assertEquals(listOf("s1"), days[1].appointments.map { it.id })
    }

    @Test
    fun `only a day with two or more unresolved Appointments gets the row that completes them all`() {
        val days = HomeOutcomes.byDay(
            listOf(appointment("f1", "2026-10-16T18:00:00Z"), appointment("f2", "2026-10-16T20:00:00Z"), appointment("s1", "2026-10-17T16:00:00Z")),
            chicago,
        )

        assertEquals(listOf(true, false), days.map { it.closesAll })
        assertTrue(HomeOutcomes.byDay(emptyList(), chicago).isEmpty())
    }

    // --- Undo ---

    @Test
    fun `undo sets back an outcome that is still shown, and one that has left every screen`() {
        assertEquals(Undo.REVERT, HomeOutcomes.undo(AppointmentStatus.COMPLETED, AppointmentStatus.COMPLETED))
        assertEquals(Undo.REVERT, HomeOutcomes.undo(AppointmentStatus.NO_SHOW, AppointmentStatus.NO_SHOW))
        assertEquals(Undo.REVERT, HomeOutcomes.undo(null, AppointmentStatus.CANCELED))
    }

    @Test
    fun `undo leaves an outcome that was changed elsewhere meanwhile, and has nothing to do for one that is Scheduled again`() {
        assertEquals(Undo.CHANGED_ELSEWHERE, HomeOutcomes.undo(AppointmentStatus.CANCELED, AppointmentStatus.COMPLETED))
        assertEquals(Undo.NOTHING, HomeOutcomes.undo(AppointmentStatus.SCHEDULED, AppointmentStatus.COMPLETED))
    }

    @Test
    fun `a card that is set back is in Today's list where its start time puts it, as Scheduled`() {
        val first = appointment("a", "2026-10-16T18:00:00Z")
        val back = appointment("b", "2026-10-16T20:00:00Z", AppointmentStatus.COMPLETED)
        val last = appointment("c", "2026-10-21T15:00:00Z")

        val list = HomeOutcomes.withCardBack(listOf(first, last), back)

        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertEquals(AppointmentStatus.SCHEDULED, list[1].status)
        assertEquals(listOf("b", "c"), HomeOutcomes.withCardBack(listOf(last), back).map { it.id })
        assertEquals(listOf("a", "b"), HomeOutcomes.withCardBack(listOf(first), back).map { it.id })
        assertEquals(listOf("b"), HomeOutcomes.withCardBack(emptyList(), back).map { it.id })
    }

    @Test
    fun `a list that has the card already is left as it is`() {
        val list = listOf(appointment("a", "2026-10-16T18:00:00Z", AppointmentStatus.COMPLETED))

        assertSame(list, HomeOutcomes.withCardBack(list, appointment("a", "2026-10-16T18:00:00Z")))
    }
}
