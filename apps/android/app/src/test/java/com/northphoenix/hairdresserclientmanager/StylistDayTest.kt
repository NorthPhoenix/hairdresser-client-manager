package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.state.StylistDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Today in the Stylist's timezone: when it changes, and what follows it. */
class StylistDayTest {
    private var now = Instant.parse("2026-10-09T06:30:00Z")
    private val day = StylistDay { now }

    private val utc = ZoneId.of("Etc/UTC")
    private val honolulu = ZoneId.of("Pacific/Honolulu")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati")

    private val oct8 = LocalDate.of(2026, 10, 8)
    private val oct9 = LocalDate.of(2026, 10, 9)
    private val oct10 = LocalDate.of(2026, 10, 10)

    // --- midnight ---

    @Test
    fun `today is the day in the Stylist's timezone, not the device's`() {
        // 20:30 on October 8 in Honolulu, 20:30 on October 9 on Kiritimati.
        day.follow(honolulu)
        assertEquals(oct8, day.today.value)

        day.follow(kiritimati)
        assertEquals(oct9, day.today.value)
    }

    @Test
    fun `the day changes at the Stylist's midnight and not before`() {
        // Kiritimati is 14 hours ahead of UTC: its midnight is at 10:00 UTC.
        now = Instant.parse("2026-10-09T09:59:59.999Z")
        day.follow(kiritimati)
        assertEquals(oct9, day.today.value)

        assertNull(day.follow(kiritimati))
        assertEquals(oct9, day.today.value)

        now = Instant.parse("2026-10-09T10:00:00Z")
        assertEquals(StylistDay.Change(ended = oct9, today = oct10), day.follow(kiritimati))
        assertEquals(oct10, day.today.value)
    }

    @Test
    fun `a day change is reported once`() {
        day.follow(kiritimati)
        now = Instant.parse("2026-10-09T10:00:00Z")
        day.follow(kiritimati)

        now = Instant.parse("2026-10-09T10:05:00Z")
        assertNull(day.follow(kiritimati))
    }

    @Test
    fun `a night in the background is one change to the day it is now`() {
        day.follow(kiritimati)

        now = Instant.parse("2026-10-11T20:00:00Z")
        assertEquals(StylistDay.Change(ended = oct9, today = LocalDate.of(2026, 10, 12)), day.follow(kiritimati))
    }

    // --- timezone ---

    @Test
    fun `another timezone can be on another day, earlier or later`() {
        day.follow(utc)
        assertEquals(oct9, day.today.value)

        assertEquals(StylistDay.Change(ended = oct9, today = oct8), day.follow(honolulu))
        assertEquals(oct8, day.today.value)

        assertEquals(StylistDay.Change(ended = oct8, today = oct9), day.follow(ZoneId.of("Europe/London")))
        assertEquals(oct9, day.today.value)
    }

    @Test
    fun `another timezone on the same day changes nothing`() {
        day.follow(utc)

        assertNull(day.follow(ZoneId.of("Europe/London")))
        assertEquals(oct9, day.today.value)
    }

    // --- the Calendar ---

    @Test
    fun `a Calendar that was on today moves to the new today`() {
        assertEquals(oct10, StylistDay.Change(ended = oct9, today = oct10).calendarDay(selected = oct9))
        // After a timezone change today can be the day before.
        assertEquals(oct8, StylistDay.Change(ended = oct9, today = oct8).calendarDay(selected = oct9))
    }

    @Test
    fun `a day the Stylist picked in the Calendar stays selected`() {
        val picked = LocalDate.of(2026, 10, 20)

        assertEquals(picked, StylistDay.Change(ended = oct9, today = oct10).calendarDay(selected = picked))
    }

    @Test
    fun `a picked day that has become today is followed from then on`() {
        // Tomorrow was picked before midnight: it stays, and is today now.
        assertEquals(oct10, StylistDay.Change(ended = oct9, today = oct10).calendarDay(selected = oct10))
        // The night after, it moves on like any Calendar that is on today.
        assertEquals(LocalDate.of(2026, 10, 11), StylistDay.Change(ended = oct10, today = LocalDate.of(2026, 10, 11)).calendarDay(selected = oct10))
    }

    // --- when to look at the clock again ---

    @Test
    fun `the next look at the clock is just after the Stylist's midnight`() {
        now = Instant.parse("2026-10-09T09:59:30Z")
        assertEquals(30_001, day.millisUntilNextLook(kiritimati))

        // Less than a millisecond to go: still a wait, and one that ends after midnight.
        now = Instant.parse("2026-10-09T09:59:59.999600Z")
        assertEquals(1, day.millisUntilNextLook(kiritimati))
    }

    @Test
    fun `the wait is for the midnight of the timezone asked about`() {
        // 10:00 UTC is midnight on Kiritimati and 23:59:40 is twenty seconds before midnight in UTC.
        now = Instant.parse("2026-10-09T23:59:40Z")

        assertEquals(20_001, day.millisUntilNextLook(utc))
        assertEquals(60_000, day.millisUntilNextLook(kiritimati))
    }

    @Test
    fun `with midnight far away the clock is looked at every minute`() {
        assertEquals(60_000, day.millisUntilNextLook(utc))

        // Right at midnight the next one is a day away.
        now = Instant.parse("2026-10-10T00:00:00Z")
        assertEquals(60_000, day.millisUntilNextLook(utc))
    }
}
