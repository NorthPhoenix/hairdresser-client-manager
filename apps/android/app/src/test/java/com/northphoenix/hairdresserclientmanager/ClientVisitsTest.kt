package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits.Rebooking
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits.Span
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits.SpanUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the Client Profile says about a Client's visits: the stats, the next Appointment and where "Book again" books. */
class ClientVisitsTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-10-21T15:00:00Z")

    private fun entry(
        id: String,
        startsAt: String,
        status: AppointmentStatus = AppointmentStatus.COMPLETED,
        locationType: LocationType? = LocationType.IN_SALON,
        primaryClientId: String? = "anna",
        locationAddress: String = "",
        customLocationAddress: String = "",
    ) = ClientHistoryEntry(
        id = id,
        primaryClientName = "Anna",
        startsAt = startsAt,
        status = status,
        primaryClientId = primaryClientId,
        locationType = locationType,
        locationAddress = locationAddress,
        customLocationAddress = customLocationAddress,
    )

    // --- summary ---

    @Test
    fun `a Client without Appointments has no visits, no last visit, no interval and nothing next`() {
        val summary = ClientVisits.summary(emptyList(), now, chicago)

        assertEquals(ClientVisits.Summary(visits = 0, lastVisit = null, usualInterval = null, next = null), summary)
    }

    @Test
    fun `only Completed Appointments are visits`() {
        val entries = listOf(
            entry("canceled", "2026-10-10T17:00:00Z", AppointmentStatus.CANCELED),
            entry("no-show", "2026-10-03T17:00:00Z", AppointmentStatus.NO_SHOW),
            entry("unresolved", "2026-10-01T17:00:00Z", AppointmentStatus.SCHEDULED),
            entry("done", "2026-09-01T17:00:00Z"),
        )

        val summary = ClientVisits.summary(entries, now, chicago)

        assertEquals(1, summary.visits)
        assertEquals(LocalDate.of(2026, 9, 1), summary.lastVisit)
        // One visit says nothing about how often.
        assertNull(summary.usualInterval)
        // A Scheduled Appointment that has started is not the next one.
        assertNull(summary.next)
    }

    @Test
    fun `the last visit is the day in the Stylist's timezone`() {
        // 9:30 PM in Austin on October 1 is October 2 in UTC.
        val summary = ClientVisits.summary(listOf(entry("late", "2026-10-02T02:30:00Z")), now, chicago)

        assertEquals(LocalDate.of(2026, 10, 1), summary.lastVisit)
    }

    @Test
    fun `the usual interval is the median gap between visit days`() {
        val entries = listOf(
            entry("a", "2026-06-01T17:00:00Z"),
            entry("b", "2026-07-13T17:00:00Z"), // 42 days
            entry("c", "2026-08-24T17:00:00Z"), // 42 days
            entry("d", "2026-10-19T17:00:00Z"), // 56 days
        )

        assertEquals(Span(SpanUnit.WEEKS, 6), ClientVisits.summary(entries, now, chicago).usualInterval)
    }

    @Test
    fun `two Appointments on one day are two visits but one visit day`() {
        val entries = listOf(
            entry("mother", "2026-09-07T17:00:00Z"),
            entry("daughter", "2026-09-07T18:00:00Z"),
            entry("later", "2026-10-19T17:00:00Z"),
        )

        val summary = ClientVisits.summary(entries, now, chicago)

        assertEquals(3, summary.visits)
        assertEquals(Span(SpanUnit.WEEKS, 6), summary.usualInterval)
    }

    @Test
    fun `two visits on one day only give no interval`() {
        val entries = listOf(entry("a", "2026-09-07T17:00:00Z"), entry("b", "2026-09-07T18:00:00Z"))

        assertNull(ClientVisits.summary(entries, now, chicago).usualInterval)
    }

    @Test
    fun `the next Appointment is the first Scheduled one that has not started`() {
        val entries = listOf(
            entry("later", "2026-11-20T18:00:00Z", AppointmentStatus.SCHEDULED),
            entry("canceled", "2026-10-22T18:00:00Z", AppointmentStatus.CANCELED),
            entry("soon", "2026-10-23T23:00:00Z", AppointmentStatus.SCHEDULED),
            entry("past", "2026-10-20T18:00:00Z", AppointmentStatus.SCHEDULED),
        )

        val summary = ClientVisits.summary(entries, now, chicago)

        assertEquals("soon", summary.next?.id)
        // Only future Appointments: no visits yet.
        assertEquals(0, summary.visits)
        assertNull(summary.lastVisit)
    }

    // --- how long ago ---

    @Test
    fun `how long ago is said in days, then weeks, then months, then years`() {
        val today = LocalDate.of(2026, 10, 21)

        assertEquals(Span(SpanUnit.DAYS, 0), ClientVisits.ago(today, today))
        assertEquals(Span(SpanUnit.DAYS, 1), ClientVisits.ago(today.minusDays(1), today))
        assertEquals(Span(SpanUnit.DAYS, 13), ClientVisits.ago(today.minusDays(13), today))
        assertEquals(Span(SpanUnit.WEEKS, 2), ClientVisits.ago(today.minusDays(14), today))
        assertEquals(Span(SpanUnit.WEEKS, 7), ClientVisits.ago(today.minusDays(52), today))
        assertEquals(Span(SpanUnit.WEEKS, 8), ClientVisits.ago(today.minusDays(62), today))
        assertEquals(Span(SpanUnit.MONTHS, 2), ClientVisits.ago(today.minusDays(63), today))
        assertEquals(Span(SpanUnit.MONTHS, 5), ClientVisits.ago(LocalDate.of(2026, 5, 20), today))
        assertEquals(Span(SpanUnit.MONTHS, 11), ClientVisits.ago(today.minusDays(364), today))
        assertEquals(Span(SpanUnit.YEARS, 1), ClientVisits.ago(today.minusDays(365), today))
        assertEquals(Span(SpanUnit.YEARS, 3), ClientVisits.ago(LocalDate.of(2023, 1, 5), today))
    }

    @Test
    fun `a visit after today is today`() {
        val today = LocalDate.of(2026, 10, 21)

        assertEquals(Span(SpanUnit.DAYS, 0), ClientVisits.ago(today.plusDays(2), today))
    }

    @Test
    fun `a usual interval is rounded to days, weeks, months or years`() {
        assertEquals(Span(SpanUnit.DAYS, 1), ClientVisits.interval(0.5))
        assertEquals(Span(SpanUnit.DAYS, 10), ClientVisits.interval(10.0))
        assertEquals(Span(SpanUnit.WEEKS, 2), ClientVisits.interval(14.0))
        assertEquals(Span(SpanUnit.WEEKS, 6), ClientVisits.interval(45.0))
        assertEquals(Span(SpanUnit.WEEKS, 13), ClientVisits.interval(90.0))
        assertEquals(Span(SpanUnit.MONTHS, 3), ClientVisits.interval(91.0))
        assertEquals(Span(SpanUnit.MONTHS, 12), ClientVisits.interval(365.0))
        assertEquals(Span(SpanUnit.YEARS, 2), ClientVisits.interval(730.0))
    }

    // --- book again ---

    @Test
    fun `Book again uses the place of the last visit`() {
        val entries = listOf(
            entry("future", "2026-11-20T18:00:00Z", AppointmentStatus.SCHEDULED, LocationType.AT_HOME),
            entry("canceled", "2026-10-10T18:00:00Z", AppointmentStatus.CANCELED, LocationType.AT_HOME),
            entry("last", "2026-10-01T18:00:00Z", locationType = LocationType.IN_SALON),
            entry("before", "2026-09-01T18:00:00Z", locationType = LocationType.AT_HOME),
        )

        assertEquals(Rebooking(LocationType.IN_SALON, null), ClientVisits.rebooking(entries, "anna"))
    }

    @Test
    fun `an at-home visit at the Client's own address is booked at the address the Client has now`() {
        val entries = listOf(entry("home", "2026-10-01T18:00:00Z", locationType = LocationType.AT_HOME, locationAddress = "1 Old St"))

        assertEquals(Rebooking(LocationType.AT_HOME, null), ClientVisits.rebooking(entries, "anna"))
    }

    @Test
    fun `an at-home visit at a typed address is booked there again`() {
        val entries = listOf(
            entry("home", "2026-10-01T18:00:00Z", locationType = LocationType.AT_HOME, locationAddress = "12 Elm St", customLocationAddress = "12 Elm St"),
        )

        assertEquals(Rebooking(LocationType.AT_HOME, "12 Elm St"), ClientVisits.rebooking(entries, "anna"))
    }

    @Test
    fun `an at-home visit at another primary Client's address is booked at that address`() {
        val entries = listOf(
            entry("family", "2026-10-01T18:00:00Z", locationType = LocationType.AT_HOME, primaryClientId = "mother", locationAddress = "5 Family Rd"),
        )

        assertEquals(Rebooking(LocationType.AT_HOME, "5 Family Rd"), ClientVisits.rebooking(entries, "anna"))
    }

    @Test
    fun `without a visit or a place to go by the form opens as always`() {
        assertNull(ClientVisits.rebooking(emptyList(), "anna"))
        assertNull(ClientVisits.rebooking(listOf(entry("canceled", "2026-10-01T18:00:00Z", AppointmentStatus.CANCELED)), "anna"))
        // An older server does not say where it was.
        assertNull(ClientVisits.rebooking(listOf(entry("old", "2026-10-01T18:00:00Z", locationType = null)), "anna"))
    }
}
