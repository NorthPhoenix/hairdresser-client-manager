package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.domain.NightStart
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class NightStartTest {
    private val chicago = TimeFormat(Locale.forLanguageTag("ru"), ZoneId.of("America/Chicago"), uses24HourClock = false)

    // 23:00 on Monday, November 30, 2026 in Chicago.
    private fun appointment(startsAt: String = "2026-12-01T05:00:00.000Z", status: AppointmentStatus = AppointmentStatus.SCHEDULED) = Appointment(
        id = "a1",
        primaryClientId = "c1",
        primaryClientName = "Анна",
        startsAt = startsAt,
        status = status,
        locationType = LocationType.IN_SALON,
    )

    @Test
    fun `night is from 21 00 to before 6 00`() {
        listOf("21:00", "22:00", "23:00", "23:59", "00:00", "00:30", "01:00", "05:59").forEach {
            assertTrue(it, NightStart.isAtNight(LocalTime.parse(it)))
        }
        listOf("06:00", "07:00", "10:00", "12:00", "18:00", "19:00", "20:59").forEach {
            assertFalse(it, NightStart.isAtNight(LocalTime.parse(it)))
        }
    }

    @Test
    fun `a start at night is offered the same time on the other half of the day`() {
        assertEquals(LocalTime.of(11, 0), NightStart.dayTime(LocalTime.of(23, 0)))
        assertEquals(LocalTime.of(10, 30), NightStart.dayTime(LocalTime.of(22, 30)))
        assertEquals(LocalTime.of(12, 30), NightStart.dayTime(LocalTime.of(0, 30)))
        assertEquals(LocalTime.of(9, 0), NightStart.dayTime(LocalTime.of(21, 0)))
        assertEquals(LocalTime.of(17, 0), NightStart.dayTime(LocalTime.of(5, 0)))
        assertNull(NightStart.dayTime(LocalTime.of(6, 0)))
        assertNull(NightStart.dayTime(LocalTime.of(11, 0)))
        assertNull(NightStart.dayTime(LocalTime.of(20, 59)))
    }

    @Test
    fun `the end time moves with the start, so the Appointment keeps its length`() {
        assertEquals(NightStart.Moved(LocalTime.of(11, 0), null), NightStart.moved(LocalTime.of(23, 0), null))

        // 23:00 to 00:30 the next day becomes 11:00 to 12:30 on the same day.
        val overnight = NightStart.moved(LocalTime.of(23, 0), LocalTime.of(0, 30))!!
        assertEquals(NightStart.Moved(LocalTime.of(11, 0), LocalTime.of(12, 30)), overnight)
        val day = LocalDate.of(2026, 11, 30)
        assertEquals(day, AppointmentDraft.endDate(day, overnight.start, overnight.end!!))

        // An end picked right in the afternoon stays the same distance away.
        assertEquals(NightStart.Moved(LocalTime.of(13, 0), LocalTime.of(14, 30)), NightStart.moved(LocalTime.of(1, 0), LocalTime.of(2, 30)))
        assertNull(NightStart.moved(LocalTime.of(11, 0), LocalTime.of(12, 0)))
    }

    @Test
    fun `a Scheduled Appointment at night asks until the Stylist keeps that time`() {
        val late = appointment()
        val start = chicago.zoned(late.startsAt).toLocalTime()

        assertEquals(LocalTime.of(23, 0), start)
        assertTrue(NightStart.asks(late, start, kept = emptySet()))

        val kept = setOf(NightStart.keptKey(late.id, Instant.parse(late.startsAt)))
        assertFalse(NightStart.asks(late, start, kept))

        // Moved to another time at night, it asks again.
        val moved = late.copy(startsAt = "2026-12-01T04:00:00.000Z")
        assertTrue(NightStart.asks(moved, chicago.zoned(moved.startsAt).toLocalTime(), kept))
    }

    @Test
    fun `an Appointment with an outcome or in the daytime does not ask`() {
        listOf(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW).forEach { status ->
            assertFalse(status.name, NightStart.asks(appointment(status = status), LocalTime.of(23, 0), emptySet()))
        }

        // 11:00 in Chicago.
        val morning = appointment(startsAt = "2026-11-30T17:00:00.000Z")
        assertFalse(NightStart.asks(morning, chicago.zoned(morning.startsAt).toLocalTime(), emptySet()))
    }

    @Test
    fun `the kept moment does not depend on how the server writes the time`() {
        assertEquals(
            NightStart.keptKey("a1", Instant.parse("2026-12-01T05:00:00.000Z")),
            NightStart.keptKey("a1", Instant.parse("2026-12-01T05:00:00Z")),
        )
    }
}
