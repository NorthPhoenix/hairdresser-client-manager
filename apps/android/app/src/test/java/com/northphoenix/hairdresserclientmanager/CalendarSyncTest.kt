package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.domain.CalendarEventRow
import com.northphoenix.hairdresserclientmanager.domain.CalendarMirror
import com.northphoenix.hairdresserclientmanager.domain.MirrorEvent
import com.northphoenix.hairdresserclientmanager.domain.MirrorWords
import com.northphoenix.hairdresserclientmanager.domain.PhoneCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneSync
import com.northphoenix.hairdresserclientmanager.domain.WrittenEvent
import com.northphoenix.hairdresserclientmanager.state.CalendarConnection
import com.northphoenix.hairdresserclientmanager.state.CalendarConnectionStore
import com.northphoenix.hairdresserclientmanager.state.CalendarProblem
import com.northphoenix.hairdresserclientmanager.state.CalendarSync
import com.northphoenix.hairdresserclientmanager.state.PhoneCalendarStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Calendar Integration on the phone: when it mirrors, what it writes, and what it says when it cannot. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSyncTest {
    /** The phone's calendar store, with one Google calendar and the events in it. */
    private class FakePhone : PhoneCalendarStore {
        var access = true
        var calendars = listOf(PhoneCalendar(10, "mom@gmail.com", "Клиенты", "c@group.calendar.google.com", canWrite = true, syncsEvents = true))
        val events = linkedMapOf<Long, MirrorEvent>()

        /** Events of the calendar that are not the app's. */
        val foreign = mutableSetOf<Long>()
        var failInsertAfter = Int.MAX_VALUE
        var syncedAccounts = listOf<String>()
        var keptSyncing = listOf<Long>()
        private var nextId = 100L
        var inserts = 0

        override fun hasAccess() = access

        override fun calendars(): List<PhoneCalendar> {
            if (!access) throw SecurityException()
            return calendars
        }

        override fun sync() = PhoneSync()

        override fun requestSync(accounts: Collection<String>) {
            syncedAccounts = accounts.toList()
        }

        override fun keepSyncing(calendarId: Long) {
            keptSyncing = keptSyncing + calendarId
        }

        override fun events(calendarId: Long): List<CalendarEventRow> {
            if (!access) throw SecurityException()
            return events.map { (id, event) -> CalendarEventRow(id, event.appointmentId) } + foreign.map { CalendarEventRow(it, null) }
        }

        override fun insert(calendarId: Long, event: MirrorEvent): Long {
            if (inserts++ >= failInsertAfter) throw IllegalStateException("full")
            return nextId++.also { events[it] = event }
        }

        override fun update(eventId: Long, event: MirrorEvent) {
            events[eventId] = event
        }

        override fun delete(eventId: Long) {
            events.remove(eventId)
            foreign.remove(eventId)
        }

        fun titles() = events.values.map { it.title }
    }

    private class MemoryStore : CalendarConnectionStore {
        override var connection: CalendarConnection? = null
        override var written: Map<String, WrittenEvent> = emptyMap()
        override var lastSync: Pair<Instant, Int>? = null
    }

    private val words = object : MirrorWords {
        override fun services(names: String) = names

        override fun clients(names: String) = names

        override fun phone(number: String) = number

        override val footer = ""
    }

    private var now = Instant.parse("2026-11-11T18:00:00Z")
    private val stylist = Stylist(id = "s1", language = Language.RU, timezone = "America/Chicago", onboardingCompletedAt = "2026-10-01T00:00:00Z")

    private fun appointment(id: String, name: String = "Наташа", startsAt: String = "2026-11-12T15:00:00Z", status: AppointmentStatus = AppointmentStatus.SCHEDULED) =
        Appointment(
            id = id,
            primaryClientId = "c-$id",
            primaryClientName = name,
            participants = listOf(AppointmentParticipant(clientId = "c-$id", name = name)),
            startsAt = startsAt,
            status = status,
            locationType = LocationType.IN_SALON,
        )

    private class Fixture(val phone: FakePhone, val store: MemoryStore, val sync: CalendarSync, val server: MutableList<Appointment>, var fetches: Int = 0, var failFetch: Boolean = false)

    private fun TestScope.fixture(vararg appointments: Appointment): Fixture {
        val phone = FakePhone()
        val store = MemoryStore()
        val server = appointments.toMutableList()
        lateinit var fixture: Fixture
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Not the test's background scope, whose work advanceUntilIdle does not wait for; and not a child of the test, which
        // would wait for the mirroring's loop to end. It runs on the test's clock all the same.
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val sync = CalendarSync(
            phone,
            store,
            scope,
            fetch = { start, _ ->
                fixture.fetches++
                if (fixture.failFetch) throw ApiException("offline", isNetwork = true)
                server.filter { !Instant.parse(it.startsAt).isBefore(start) }
            },
            words = { words },
            io = dispatcher,
            clock = { now },
        )

        fixture = Fixture(phone, store, sync, server)
        return fixture
    }

    private fun TestScope.connected(vararg appointments: Appointment): Fixture {
        val fixture = fixture(*appointments)

        fixture.sync.follow(stylist)
        fixture.sync.connect(fixture.phone.calendars.first())
        advanceUntilIdle()
        return fixture
    }

    @Test
    fun `connecting mirrors the Appointments from now on`() = runTest {
        val f = connected(appointment("a1"), appointment("past", startsAt = "2026-11-10T15:00:00Z"))

        assertEquals(listOf("Наташа"), f.phone.titles())
        assertEquals(setOf("a1"), f.store.written.keys)
        val state = f.sync.state.value
        assertEquals(1, state.mirrored)
        assertEquals(now, state.lastSyncedAt)
        assertNull(state.problem)
        assertEquals("c@group.calendar.google.com", state.connection?.googleId)
    }

    @Test
    fun `changes that come together are one mirroring`() = runTest {
        val f = connected(appointment("a1"))
        val before = f.fetches

        repeat(5) { f.sync.syncSoon() }
        advanceUntilIdle()

        assertEquals(before + 1, f.fetches)
    }

    @Test
    fun `a changed Appointment changes its event, and a canceled one loses it`() = runTest {
        val f = connected(appointment("a1"), appointment("a2", name = "Миша"))

        f.server[0] = appointment("a1", name = "Наталья")
        f.server[1] = appointment("a2", name = "Миша", status = AppointmentStatus.CANCELED)
        f.sync.syncSoon()
        advanceUntilIdle()

        assertEquals(listOf("Наталья"), f.phone.titles())
        assertEquals(setOf("a1"), f.store.written.keys)
    }

    @Test
    fun `a completed Appointment keeps its event as it is`() = runTest {
        val f = connected(appointment("a1"))
        val event = f.phone.events.entries.single()

        f.server[0] = appointment("a1", status = AppointmentStatus.COMPLETED)
        f.sync.syncSoon()
        advanceUntilIdle()

        assertEquals(event, f.phone.events.entries.single())
    }

    @Test
    fun `a canceled Appointment set back to scheduled is written again`() = runTest {
        val f = connected(appointment("a1", status = AppointmentStatus.CANCELED))

        assertTrue(f.phone.events.isEmpty())

        f.server[0] = appointment("a1")
        f.sync.syncSoon()
        advanceUntilIdle()

        assertEquals(listOf("Наташа"), f.phone.titles())
    }

    @Test
    fun `a deleted Appointment loses its event, and the Stylist's own events stay`() = runTest {
        val f = connected(appointment("a1"))

        f.phone.foreign += 5
        f.server.clear()
        f.sync.syncSoon()
        advanceUntilIdle()

        assertTrue(f.phone.events.isEmpty())
        assertEquals(setOf(5L), f.phone.foreign)
    }

    @Test
    fun `without calendar access it says so and writes nothing`() = runTest {
        val f = connected(appointment("a1"))

        f.phone.access = false
        f.server += appointment("a2")
        f.sync.syncSoon()
        advanceUntilIdle()

        assertEquals(CalendarProblem.NO_ACCESS, f.sync.state.value.problem)
        assertTrue(f.sync.state.value.problem!!.needsStylist)

        f.phone.access = true
        f.sync.syncNow()
        advanceUntilIdle()

        assertNull(f.sync.state.value.problem)
        assertEquals(2, f.phone.events.size)
    }

    @Test
    fun `a calendar that is gone from the phone is said`() = runTest {
        val f = connected(appointment("a1"))

        f.phone.calendars = emptyList()
        f.sync.syncSoon()
        advanceUntilIdle()

        assertEquals(CalendarProblem.CALENDAR_GONE, f.sync.state.value.problem)
    }

    @Test
    fun `the calendar is found again under another row by its address in Google`() = runTest {
        val f = connected(appointment("a1"))

        f.phone.calendars = listOf(f.phone.calendars.first().copy(id = 44, name = "Клиенты мамы"))
        f.sync.syncSoon()
        advanceUntilIdle()

        assertNull(f.sync.state.value.problem)
        assertEquals(44L, f.store.connection?.calendarId)
        assertEquals("Клиенты мамы", f.sync.state.value.connection?.calendarName)
    }

    @Test
    fun `Appointments that cannot be loaded leave the calendar as it is`() = runTest {
        val f = connected(appointment("a1"))

        f.failFetch = true
        f.sync.syncSoon()
        advanceUntilIdle()

        assertEquals(CalendarProblem.SERVER, f.sync.state.value.problem)
        assertFalse(f.sync.state.value.problem!!.needsStylist)
        assertEquals(listOf("Наташа"), f.phone.titles())
    }

    @Test
    fun `what was written before a failure is remembered, so nothing is written twice`() = runTest {
        val f = fixture(appointment("a1"), appointment("a2", startsAt = "2026-11-13T15:00:00Z"))

        f.phone.failInsertAfter = 1
        f.sync.follow(stylist)
        f.sync.connect(f.phone.calendars.first())
        advanceUntilIdle()

        assertEquals(CalendarProblem.PHONE, f.sync.state.value.problem)
        assertEquals(1, f.phone.events.size)

        f.phone.failInsertAfter = Int.MAX_VALUE
        f.sync.syncNow()
        advanceUntilIdle()

        assertNull(f.sync.state.value.problem)
        assertEquals(2, f.phone.events.size)
    }

    @Test
    fun `disconnecting stops the mirroring and leaves the events`() = runTest {
        val f = connected(appointment("a1"))

        f.sync.disconnect()
        f.server.clear()
        f.sync.syncSoon()
        advanceUntilIdle()

        assertNull(f.sync.state.value.connection)
        assertEquals(1, f.phone.events.size)
    }

    @Test
    fun `the connection is of the Stylist who made it`() = runTest {
        val f = connected(appointment("a1"))
        val fetches = f.fetches

        f.sync.follow(stylist.copy(id = "s2"))
        f.sync.syncNow()
        advanceUntilIdle()

        assertNull(f.sync.state.value.connection)
        assertEquals(fetches, f.fetches)

        f.sync.follow(null)
        f.sync.follow(stylist)
        advanceUntilIdle()

        assertEquals("s1", f.sync.state.value.connection?.stylistId)
    }

    @Test
    fun `another timezone or language mirrors again`() = runTest {
        val f = connected(appointment("a1"))

        f.sync.follow(stylist.copy(timezone = "Europe/Moscow"))
        advanceUntilIdle()

        assertEquals("Europe/Moscow", f.phone.events.values.single().timezone)
    }

    @Test
    fun `a calendar that does not sync is made to`() = runTest {
        val f = fixture(appointment("a1"))

        f.phone.calendars = listOf(f.phone.calendars.first().copy(syncsEvents = false))
        f.sync.follow(stylist)
        f.sync.connect(f.phone.calendars.first())
        advanceUntilIdle()

        assertTrue(10L in f.phone.keptSyncing)
    }

    @Test
    fun `while the calendar made on calendar google com is waited for, Android is asked to sync`() = runTest {
        val f = fixture()

        f.sync.askForSync(listOf("mom@gmail.com"))
        advanceUntilIdle()

        assertEquals(listOf("mom@gmail.com"), f.phone.syncedAccounts)
    }

    @Test
    fun `nothing is mirrored without a connection`() = runTest {
        val f = fixture(appointment("a1"))

        f.sync.follow(stylist)
        f.sync.syncNow()
        advanceUntilIdle()

        assertEquals(0, f.fetches)
        assertTrue(f.phone.events.isEmpty())
    }

    @Test
    fun `the tag of every event names its Appointment`() = runTest {
        val f = connected(appointment("a1"))

        assertEquals("a1", CalendarMirror.appointmentOfTag(CalendarMirror.tag(f.phone.events.values.single().appointmentId)))
    }
}
