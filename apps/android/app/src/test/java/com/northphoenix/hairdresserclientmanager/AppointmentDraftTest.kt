package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft.Times
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * What Appointment Creation works out from what the Stylist chose: the default start, the instants
 * it sends, the at-home address. And what the edit of an Appointment's time and place opens with and sends.
 */
class AppointmentDraftTest {
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val berlin = TimeFormat(Locale.US, ZoneId.of("Europe/Berlin"))
    private val springForward = LocalDate.of(2027, 3, 28)
    private val utc = TimeFormat(Locale.US, ZoneId.of("UTC"))
    private val day = LocalDate.of(2026, 10, 24)

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
    fun `an end time before the start time is on the next day`() {
        // 11:30 PM to 12:30 AM, the end the picker itself suggests: it used to be refused.
        assertEquals(day.plusDays(1), AppointmentDraft.endDate(day, LocalTime.of(23, 30), LocalTime.of(0, 30)))
        assertEquals(
            Times.Valid(Instant.parse("2026-10-24T23:30:00Z"), Instant.parse("2026-10-25T00:30:00Z")),
            AppointmentDraft.times(day, LocalTime.of(23, 30), LocalTime.of(0, 30), utc),
        )
        // A long evening: 8:00 PM to 2:00 AM.
        assertEquals(
            Times.Valid(Instant.parse("2026-10-24T20:00:00Z"), Instant.parse("2026-10-25T02:00:00Z")),
            AppointmentDraft.times(day, LocalTime.of(20, 0), LocalTime.of(2, 0), utc),
        )
        // Until midnight is the first minute of the next day, up to the half day an overnight Appointment can be.
        assertEquals(day.plusDays(1), AppointmentDraft.endDate(day, LocalTime.of(21, 0), LocalTime.MIDNIGHT))
        assertEquals(day.plusDays(1), AppointmentDraft.endDate(day, LocalTime.NOON, LocalTime.MIDNIGHT))

        // An end after the start stays on the day, however long that is.
        assertEquals(day, AppointmentDraft.endDate(day, LocalTime.of(14, 0), LocalTime.of(14, 1)))
        assertEquals(day, AppointmentDraft.endDate(day, LocalTime.of(0, 0), LocalTime.of(23, 59)))
    }

    @Test
    fun `times that only a mistake puts on the next day are refused`() {
        fun refused(start: LocalTime, end: LocalTime) {
            assertNull("$start to $end", AppointmentDraft.endDate(day, start, end))
            assertEquals("$start to $end", Times.EndNotAfterStart, AppointmentDraft.times(day, start, end, utc))
        }

        // The same time as the start: it was saved as an Appointment of 24 hours.
        refused(LocalTime.of(14, 0), LocalTime.of(14, 0))
        // End 3:00 PM chosen, then the start moved to 4:00 PM: it was saved as 23 hours.
        refused(LocalTime.of(16, 0), LocalTime.of(15, 0))
        // 12:30 AM picked for 12:30 PM after a start of 9:00 AM: it was saved as 15 and a half hours.
        refused(LocalTime.of(9, 0), LocalTime.of(0, 30))
        // One minute more than half a day.
        refused(LocalTime.of(11, 59), LocalTime.MIDNIGHT)

        // AM for PM, or PM for AM, in the end of any Appointment that was meant to end on its day never passes as an overnight one.
        val quarterHours = (0 until 96).map { LocalTime.MIDNIGHT.plusMinutes(15L * it) }
        for (start in quarterHours) {
            for (meant in quarterHours.filter { it.isAfter(start) }) {
                val slip = meant.plusHours(12)

                if (!slip.isAfter(start)) {
                    assertNull("$start to $meant, picked $slip", AppointmentDraft.endDate(day, start, slip))
                }
            }
        }

        // A start time the clocks skip is still what the Stylist is told first.
        assertEquals(Times.StartSkipped, AppointmentDraft.times(springForward, LocalTime.of(2, 30), LocalTime.of(2, 30), berlin))
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

    // --- the edit of an Appointment's time and place ---

    private fun appointment(
        startsAt: String,
        endsAt: String? = null,
        locationType: LocationType = LocationType.IN_SALON,
        customLocationAddress: String = "",
        status: AppointmentStatus = AppointmentStatus.SCHEDULED,
    ) = Appointment(
        id = "a1",
        primaryClientId = "c1",
        primaryClientName = "Alice",
        startsAt = startsAt,
        endsAt = endsAt,
        status = status,
        locationType = locationType,
        locationAddress = customLocationAddress.ifEmpty { "12 Salon Street" },
        customLocationAddress = customLocationAddress,
    )

    @Test
    fun `the edit form opens with the day and the times of the Appointment in the stylist timezone`() {
        // 19:30 UTC on October 9 is 08:30 on October 10 in Auckland (UTC+13).
        val nz = TimeFormat(Locale.US, auckland)
        val saved = AppointmentDraft.saved(appointment("2026-10-09T19:30:00.000Z", endsAt = "2026-10-09T20:15:00.000Z"), nz)

        assertEquals(LocalDate.parse("2026-10-10"), saved.date)
        assertEquals(LocalTime.parse("08:30"), saved.startTime)
        assertEquals(LocalDate.parse("2026-10-10"), saved.endDate)
        assertEquals(LocalTime.parse("09:15"), saved.endTime)

        // No end time is no end time, not one the form makes up.
        val open = AppointmentDraft.saved(appointment("2026-10-09T19:30:00.000Z"), nz)
        assertNull(open.endDate)
        assertNull(open.endTime)
    }

    @Test
    fun `the edit form opens with the Stylist's own at-home address, and with none for the primary Client's or the salon's`() {
        val start = "2026-10-24T10:00:00.000Z"

        assertEquals(LocationType.IN_SALON, AppointmentDraft.saved(appointment(start), utc).locationType)
        assertNull(AppointmentDraft.saved(appointment(start), utc).ownAddress)
        // At the primary Client's address: the field shows that Client's address, whoever it is by now.
        assertNull(AppointmentDraft.saved(appointment(start, locationType = LocationType.AT_HOME), utc).ownAddress)

        val custom = AppointmentDraft.saved(appointment(start, locationType = LocationType.AT_HOME, customLocationAddress = alice), utc)
        assertEquals(LocationType.AT_HOME, custom.locationType)
        assertEquals(alice, custom.ownAddress)
        // M9: the address of a former primary Client that stayed on the Appointment is in the field, to be changed or emptied.
        assertEquals(alice, AppointmentDraft.shownAddress(custom.ownAddress, carol))
        assertNull(AppointmentDraft.ownAddress(typed = "", primaryClientAddress = carol))
    }

    @Test
    fun `an Appointment with an outcome keeps its place until the Stylist changes it in the form`() {
        val start = "2026-10-24T10:00:00.000Z"
        val outcomes = AppointmentStatus.entries - AppointmentStatus.SCHEDULED

        outcomes.forEach { status ->
            // At its primary Client's address, which was another one when the visit took place: a save of the time alone must not replace it.
            val atClients = AppointmentDraft.saved(appointment(start, locationType = LocationType.AT_HOME, status = status), utc)

            assertTrue(AppointmentDraft.keepsPlace(atClients, LocationType.AT_HOME, typed = atClients.ownAddress))
            // Emptied, or the Client's address of today typed out: the Stylist asks for that Client's address, and it is sent.
            assertFalse(AppointmentDraft.keepsPlace(atClients, LocationType.AT_HOME, typed = ""))
            assertFalse(AppointmentDraft.keepsPlace(atClients, LocationType.AT_HOME, typed = carol))
            assertFalse(AppointmentDraft.keepsPlace(atClients, LocationType.IN_SALON, typed = null))

            val custom = AppointmentDraft.saved(appointment(start, locationType = LocationType.AT_HOME, customLocationAddress = alice, status = status), utc)

            assertTrue(AppointmentDraft.keepsPlace(custom, LocationType.AT_HOME, typed = alice))
            assertFalse(AppointmentDraft.keepsPlace(custom, LocationType.AT_HOME, typed = "$alice, flat 2"))
            assertFalse(AppointmentDraft.keepsPlace(custom, LocationType.AT_HOME, typed = null))

            // In the salon there is no address field: an address left from a look at 'At home' changes nothing.
            val inSalon = AppointmentDraft.saved(appointment(start, status = status), utc)

            assertTrue(AppointmentDraft.keepsPlace(inSalon, LocationType.IN_SALON, typed = null))
            assertTrue(AppointmentDraft.keepsPlace(inSalon, LocationType.IN_SALON, typed = alice))
            assertFalse(AppointmentDraft.keepsPlace(inSalon, LocationType.AT_HOME, typed = null))
        }
    }

    @Test
    fun `a Scheduled Appointment sends its place with every save`() {
        val start = "2026-10-24T10:00:00.000Z"
        val inSalon = AppointmentDraft.saved(appointment(start), utc)
        val atHome = AppointmentDraft.saved(appointment(start, locationType = LocationType.AT_HOME), utc)

        // The save brings it the address its salon or its primary Client has now.
        assertFalse(AppointmentDraft.keepsPlace(inSalon, LocationType.IN_SALON, typed = null))
        assertFalse(AppointmentDraft.keepsPlace(atHome, LocationType.AT_HOME, typed = null))
    }

    @Test
    fun `times the Stylist did not change are not sent`() {
        val saved = AppointmentDraft.saved(appointment("2026-10-24T10:00:00.000Z", endsAt = "2026-10-24T11:00:00.000Z"), utc)

        // Only the place is edited.
        assertNull(AppointmentDraft.editedTimes(saved, saved.date, saved.startTime, saved.endTime, utc))
    }

    @Test
    fun `a changed day, start or end is sent whole, by the rules of Appointment Creation`() {
        val saved = AppointmentDraft.saved(appointment("2026-10-24T10:00:00.000Z", endsAt = "2026-10-24T11:00:00.000Z"), utc)
        val ten = LocalTime.of(10, 0)
        val eleven = LocalTime.of(11, 0)

        // Another day: both times move with it.
        assertEquals(
            Times.Valid(Instant.parse("2026-10-26T10:00:00Z"), Instant.parse("2026-10-26T11:00:00Z")),
            AppointmentDraft.editedTimes(saved, day.plusDays(2), ten, eleven, utc),
        )
        // Another start: the end is sent with it, because the server checks a new start against the end it has.
        assertEquals(
            Times.Valid(Instant.parse("2026-10-24T10:30:00Z"), Instant.parse("2026-10-24T11:00:00Z")),
            AppointmentDraft.editedTimes(saved, day, LocalTime.of(10, 30), eleven, utc),
        )
        // The end cleared: sent as no end.
        assertEquals(Times.Valid(Instant.parse("2026-10-24T10:00:00Z"), endsAt = null), AppointmentDraft.editedTimes(saved, day, ten, end = null, utc))
        // An end past midnight, and the mistakes Appointment Creation refuses.
        assertEquals(
            Times.Valid(Instant.parse("2026-10-24T22:00:00Z"), Instant.parse("2026-10-25T01:00:00Z")),
            AppointmentDraft.editedTimes(saved, day, LocalTime.of(22, 0), LocalTime.of(1, 0), utc),
        )
        assertEquals(Times.EndNotAfterStart, AppointmentDraft.editedTimes(saved, day, LocalTime.of(12, 0), eleven, utc))
        assertEquals(Times.StartSkipped, AppointmentDraft.editedTimes(saved, springForward, LocalTime.of(2, 30), eleven, berlin))
    }

    @Test
    fun `an edit of the place leaves a start in the hour the clocks repeat where it is`() {
        // Berlin, October 25, 2026: 02:30 comes twice. This Appointment is at the second one (01:30 UTC).
        val saved = AppointmentDraft.saved(appointment("2026-10-25T01:30:00.000Z"), berlin)

        assertEquals(LocalTime.of(2, 30), saved.startTime)
        // Worked out from the form it would be the first 02:30, an hour earlier.
        assertEquals(Times.Valid(Instant.parse("2026-10-25T00:30:00Z"), endsAt = null), AppointmentDraft.times(saved.date, saved.startTime, end = null, berlin))
        assertNull(AppointmentDraft.editedTimes(saved, saved.date, saved.startTime, saved.endTime, berlin))
    }

    @Test
    fun `an end the form does not offer stays, and is named, until the Stylist changes the times`() {
        // Saved before an end time could be at most twelve hours into the next day: 15:00 to 14:00 the day after.
        val saved = AppointmentDraft.saved(appointment("2026-10-24T15:00:00.000Z", endsAt = "2026-10-25T14:00:00.000Z"), utc)

        assertNull(AppointmentDraft.editedTimes(saved, saved.date, saved.startTime, saved.endTime, utc))
        assertEquals(LocalDate.parse("2026-10-25"), AppointmentDraft.editedEndDate(saved, saved.date, saved.startTime, saved.endTime))

        // Once a time is changed the end is read as Appointment Creation reads it.
        assertEquals(Times.EndNotAfterStart, AppointmentDraft.editedTimes(saved, saved.date, LocalTime.of(15, 30), saved.endTime, utc))
        assertNull(AppointmentDraft.editedEndDate(saved, saved.date, LocalTime.of(15, 30), saved.endTime))
        assertEquals(LocalDate.parse("2026-10-24"), AppointmentDraft.editedEndDate(saved, saved.date, saved.startTime, LocalTime.of(16, 0)))
        assertEquals(LocalDate.parse("2026-10-25"), AppointmentDraft.editedEndDate(saved, saved.date, LocalTime.of(22, 0), LocalTime.of(1, 0)))
        assertNull(AppointmentDraft.editedEndDate(saved, saved.date, saved.startTime, end = null))
    }
}
