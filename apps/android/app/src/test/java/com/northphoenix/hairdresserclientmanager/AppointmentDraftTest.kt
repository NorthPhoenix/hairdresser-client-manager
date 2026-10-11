package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft.Times
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** What Appointment Creation works out from what the Stylist chose: the default start, the instants it sends, the at-home address. */
class AppointmentDraftTest {
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val berlin = TimeFormat(Locale.US, ZoneId.of("Europe/Berlin"))
    private val springForward = LocalDate.of(2027, 3, 28)

    private fun at(zone: ZoneId, date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone)

    // --- the start the form opens with ---

    @Test
    fun `the form opens at the next full hour`() {
        val afternoon = at(auckland, "2026-10-09", "15:20:41")

        // From Today, and from the Calendar while it shows today.
        assertEquals(LocalDateTime.parse("2026-10-09T16:00"), AppointmentDraft.defaultStart(afternoon, day = null))
        assertEquals(LocalDateTime.parse("2026-10-09T16:00"), AppointmentDraft.defaultStart(afternoon, day = LocalDate.parse("2026-10-09")))
        // On the hour, the next hour is still an hour away.
        assertEquals(LocalDateTime.parse("2026-10-09T16:00"), AppointmentDraft.defaultStart(at(auckland, "2026-10-09", "15:00"), day = null))
    }

    @Test
    fun `late in the evening the next full hour is midnight of the next day, from the Calendar as from Today`() {
        val lateEvening = at(auckland, "2026-10-09", "23:43")

        assertEquals(LocalDateTime.parse("2026-10-10T00:00"), AppointmentDraft.defaultStart(lateEvening, day = null))
        // It used to be today at 12:00 AM: almost a day in the past.
        assertEquals(LocalDateTime.parse("2026-10-10T00:00"), AppointmentDraft.defaultStart(lateEvening, day = LocalDate.parse("2026-10-09")))
    }

    @Test
    fun `another day chosen in the Calendar keeps its date and takes the time of day of the next full hour`() {
        val nextWeek = LocalDate.parse("2026-10-16")
        val lastWeek = LocalDate.parse("2026-10-02")

        assertEquals(LocalDateTime.parse("2026-10-16T16:00"), AppointmentDraft.defaultStart(at(auckland, "2026-10-09", "15:20"), nextWeek))
        assertEquals(LocalDateTime.parse("2026-10-02T16:00"), AppointmentDraft.defaultStart(at(auckland, "2026-10-09", "15:20"), lastWeek))
        // Late in the evening that is the first hour of the chosen day, never a day next to it.
        assertEquals(LocalDateTime.parse("2026-10-16T00:00"), AppointmentDraft.defaultStart(at(auckland, "2026-10-09", "23:43"), nextWeek))
        assertEquals(LocalDateTime.parse("2026-10-10T00:00"), AppointmentDraft.defaultStart(at(auckland, "2026-10-09", "23:43"), LocalDate.parse("2026-10-10")))
    }

    @Test
    fun `the form never opens at a time the chosen day does not have`() {
        // 1:20 AM a week before the clocks go forward in Berlin: 2:00 AM does not exist on that Sunday.
        val now = at(berlin.zone, "2027-03-21", "01:20")

        assertEquals(LocalDateTime.parse("2027-03-28T03:00"), AppointmentDraft.defaultStart(now, springForward))
        assertEquals(LocalDateTime.parse("2027-03-21T02:00"), AppointmentDraft.defaultStart(now, day = null))
    }

    // --- the instants that are sent ---

    @Test
    fun `the chosen day and times are sent as instants in the stylist timezone`() {
        val chicago = TimeFormat(Locale.US, ZoneId.of("America/Chicago"))
        val day = LocalDate.of(2026, 6, 13)

        assertEquals(
            Times.Valid(Instant.parse("2026-06-13T19:00:00Z"), Instant.parse("2026-06-13T20:30:00Z")),
            AppointmentDraft.times(day, LocalTime.of(14, 0), LocalTime.of(15, 30), chicago),
        )
        assertEquals(Times.Valid(Instant.parse("2026-06-13T19:00:00Z"), endsAt = null), AppointmentDraft.times(day, LocalTime.of(14, 0), end = null, chicago))
    }

    @Test
    fun `a start time the clocks skip is refused, not moved an hour later`() {
        // 2:30 AM used to be saved as 3:30 AM with only 'Appointment created.'
        assertEquals(Times.StartSkipped, AppointmentDraft.times(springForward, LocalTime.of(2, 30), end = null, berlin))
        assertEquals(
            Times.StartSkipped,
            AppointmentDraft.times(LocalDate.of(2027, 3, 14), LocalTime.of(2, 30), end = null, TimeFormat(Locale.US, ZoneId.of("America/New_York"))),
        )
        // 2:50 to 3:10 AM used to be sent as 3:50 to 3:10 and refused with 'End time must be after start time.'
        assertEquals(Times.StartSkipped, AppointmentDraft.times(springForward, LocalTime.of(2, 50), LocalTime.of(3, 10), berlin))

        // The hour before and the hour after exist.
        assertEquals(
            Times.Valid(Instant.parse("2027-03-28T00:30:00Z"), Instant.parse("2027-03-28T01:10:00Z")),
            AppointmentDraft.times(springForward, LocalTime.of(1, 30), LocalTime.of(3, 10), berlin),
        )
    }

    @Test
    fun `an end time the clocks skip is refused, on the day the end falls on`() {
        assertEquals(Times.EndSkipped, AppointmentDraft.times(springForward, LocalTime.of(1, 30), LocalTime.of(2, 30), berlin))
        // An evening Appointment that ends in the skipped hour of the next day.
        assertEquals(Times.EndSkipped, AppointmentDraft.times(springForward.minusDays(1), LocalTime.of(23, 0), LocalTime.of(2, 30), berlin))
        // The same times a day earlier are fine: that night has its 2:30 AM.
        assertEquals(
            Times.Valid(Instant.parse("2027-03-26T22:00:00Z"), Instant.parse("2027-03-27T01:30:00Z")),
            AppointmentDraft.times(springForward.minusDays(2), LocalTime.of(23, 0), LocalTime.of(2, 30), berlin),
        )
    }

    @Test
    fun `an end time at or before the start time is on the next day`() {
        val utc = TimeFormat(Locale.US, ZoneId.of("UTC"))
        val day = LocalDate.of(2026, 10, 24)

        // 11:30 PM to 12:30 AM, the end the picker itself suggests: it used to be refused.
        assertEquals(day.plusDays(1), AppointmentDraft.endDate(day, LocalTime.of(23, 30), LocalTime.of(0, 30)))
        assertEquals(
            Times.Valid(Instant.parse("2026-10-24T23:30:00Z"), Instant.parse("2026-10-25T00:30:00Z")),
            AppointmentDraft.times(day, LocalTime.of(23, 30), LocalTime.of(0, 30), utc),
        )
        // The same time as the start is a whole day later, never an Appointment of no length.
        assertEquals(day.plusDays(1), AppointmentDraft.endDate(day, LocalTime.of(14, 0), LocalTime.of(14, 0)))
        assertEquals(
            Times.Valid(Instant.parse("2026-10-24T14:00:00Z"), Instant.parse("2026-10-25T14:00:00Z")),
            AppointmentDraft.times(day, LocalTime.of(14, 0), LocalTime.of(14, 0), utc),
        )

        // An end after the start stays on the day.
        assertEquals(day, AppointmentDraft.endDate(day, LocalTime.of(14, 0), LocalTime.of(14, 1)))
        assertEquals(day, AppointmentDraft.endDate(day, LocalTime.of(0, 0), LocalTime.of(23, 59)))
    }

    // --- the at-home address ---

    private val alice = "45 Elm Road, Shelbyville"
    private val carol = "9 Oak Lane, Capital City"

    /** What the form does to the typed text when the primary Client or the location is changed. */
    private fun afterChange(typed: String?, primaryClientAddress: String?) = AppointmentDraft.ownAddress(typed, primaryClientAddress)

    @Test
    fun `the at-home address follows the primary Client until the Stylist types their own`() {
        // Nothing typed: Alice's address, then Carol's, then none for a Client who has no address or an inline one.
        var typed: String? = null
        assertEquals(alice, AppointmentDraft.shownAddress(typed, alice))
        typed = afterChange(typed, alice)
        assertEquals(carol, AppointmentDraft.shownAddress(typed, carol))
        typed = afterChange(typed, carol)
        assertEquals("", AppointmentDraft.shownAddress(typed, ""))
        assertEquals("", AppointmentDraft.shownAddress(typed, primaryClientAddress = null))

        // Nothing is sent, so the Appointment takes the address of its primary Client on the server too.
        assertNull(AppointmentDraft.ownAddress(typed = null, primaryClientAddress = carol))
    }

    @Test
    fun `an address the Stylist typed stays and is sent, whoever the primary Client is`() {
        val typed = "1 Lost Lane, apt 3 "

        assertEquals(typed, AppointmentDraft.shownAddress(typed, alice))
        assertEquals("1 Lost Lane, apt 3", afterChange(typed, alice))
        assertEquals("1 Lost Lane, apt 3", AppointmentDraft.shownAddress(afterChange(typed, alice), carol))
        assertEquals("1 Lost Lane, apt 3", AppointmentDraft.ownAddress(typed, carol))
    }

    @Test
    fun `an address field left empty or put back to the Client's address is not the Stylist's own`() {
        // While the Stylist is typing the field shows what was typed, an empty field as well.
        assertEquals("", AppointmentDraft.shownAddress(typed = "", primaryClientAddress = alice))
        assertEquals("$alice ", AppointmentDraft.shownAddress(typed = "$alice ", primaryClientAddress = alice))

        // Emptied, then another primary Client: that Client's address, not an empty field the server would fill unseen.
        assertEquals(carol, AppointmentDraft.shownAddress(afterChange(typed = " ", primaryClientAddress = alice), carol))
        // Alice's address touched and put back, then Carol chosen: Alice's address must not stay on Carol's Appointment.
        assertEquals(carol, AppointmentDraft.shownAddress(afterChange(typed = "$alice ", primaryClientAddress = alice), carol))
        // Neither is sent as a custom address.
        assertNull(AppointmentDraft.ownAddress(typed = "", primaryClientAddress = alice))
        assertNull(AppointmentDraft.ownAddress(typed = " $alice", primaryClientAddress = alice))
        // The address of a former primary Client that the Stylist typed out is their own choice and is kept.
        assertEquals(alice, AppointmentDraft.ownAddress(typed = alice, primaryClientAddress = carol))
    }
}
