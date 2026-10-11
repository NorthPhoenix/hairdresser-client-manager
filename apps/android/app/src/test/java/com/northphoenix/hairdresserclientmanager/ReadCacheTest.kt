package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.state.Answer
import com.northphoenix.hairdresserclientmanager.state.AppointmentKey
import com.northphoenix.hairdresserclientmanager.state.AppointmentReads
import com.northphoenix.hairdresserclientmanager.state.Changes
import com.northphoenix.hairdresserclientmanager.state.DayKey
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.state.after
import com.northphoenix.hairdresserclientmanager.state.showLatest
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

/**
 * Reads go through the read cache (docs/adr/0016): what was read before is shown at once and
 * refreshed behind it, a loading state is only for what was never read, and nothing that a change
 * may have made wrong is shown or served again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadCacheTest {
    private val zone = ZoneId.of("America/Chicago")
    private val monday = DayKey(LocalDate.of(2026, 10, 12), zone)
    private val tuesday = DayKey(LocalDate.of(2026, 10, 13), zone)
    private val wednesday = DayKey(LocalDate.of(2026, 10, 14), zone)

    private val failedOffline = LoadState.Failed(R.string.network_error)

    private fun appointment(id: String, phone: String = "5550001", status: AppointmentStatus = AppointmentStatus.SCHEDULED, clientId: String = "c1") =
        Appointment(
            id = id,
            primaryClientId = clientId,
            primaryClientName = "Phone Test",
            participants = listOf(AppointmentParticipant(clientId = clientId, name = "Phone Test", phone = phone, isPrimary = true)),
            startsAt = "2026-10-12T15:00:00.000Z",
            status = status,
            locationType = LocationType.IN_SALON,
        )

    /** Answers as a server does: with what it had when the request arrived, also when the answer is held back. */
    private class Server {
        val days = mutableMapOf<LocalDate, List<Appointment>>()
        var todays = emptyList<Appointment>()
        var online = true
        val requests = mutableListOf<String>()

        /** How many requests were given up before their answer. */
        var abandoned = 0
        private var held: CompletableDeferred<Unit>? = null

        /** Requests from now on get no answer until [release]. */
        fun hold() {
            held = CompletableDeferred()
        }

        fun release() {
            held?.complete(Unit)
            held = null
        }

        suspend fun day(key: DayKey): List<Appointment> = answer("day ${key.day}") { days[key.day].orEmpty() }

        suspend fun today(key: DayKey): List<Appointment> = answer("today ${key.day}") { todays }

        suspend fun find(id: String): Appointment? = answer("get $id") { (days.values.flatten() + todays).firstOrNull { it.id == id } }

        private suspend fun <T> answer(request: String, read: () -> T): T {
            val gate = held
            val reachable = online
            val answer = read()

            requests += request
            try {
                gate?.await()
            } catch (error: CancellationException) {
                abandoned++
                throw error
            }
            if (!reachable) {
                throw ApiException("Network request failed.", code = "NETWORK", isNetwork = true)
            }

            return answer
        }
    }

    private fun TestScope.reads(server: Server, changes: Changes = Changes()) =
        AppointmentReads(backgroundScope, changes, server::today, server::day, server::find, fetchSources = { emptyList() })

    /** What a screen that reads [flow] is shown, value by value. It starts reading at once, as a screen does. */
    private fun <T> TestScope.shownBy(flow: Flow<T>): Pair<List<T>, Job> {
        val shown = mutableListOf<T>()

        return shown to backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { shown += it } }
    }

    private suspend fun failureOf(read: suspend () -> Unit): Exception? =
        try {
            read()
            null
        } catch (error: Exception) {
            error
        }

    // --- a Calendar day ---

    @Test
    fun `a day that is shown for the first time is loading until the server answers`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)

        server.hold()
        val (shown) = shownBy(reads.days.watch(monday))
        runCurrent()
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading)), shown)

        server.release()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(appointment("a1"))), shown.last())
        assertEquals(2, shown.size)
    }

    @Test
    fun `a day seen before is shown at once, with no loading state, and refreshed behind it`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"))
            days[tuesday.day] = listOf(appointment("a2"))
        }
        val reads = reads(server)

        // Monday, then on to Tuesday.
        val (_, onMonday) = shownBy(reads.days.watch(monday))
        runCurrent()
        onMonday.cancel()
        val (_, onTuesday) = shownBy(reads.days.watch(tuesday))
        runCurrent()
        onTuesday.cancel()
        runCurrent()

        // Back to Monday, which another device has changed meanwhile. The server is slow to answer.
        server.days[monday.day] = listOf(appointment("a1", status = AppointmentStatus.COMPLETED))
        server.hold()
        val (shown) = shownBy(reads.days.watch(monday))

        // The day is there as soon as it is asked for: nothing had to run first.
        assertEquals(listOf(Read(LoadState.Loaded, listOf(appointment("a1")))), shown)

        // It is asked again behind what is shown, and that shows nothing either.
        runCurrent()
        assertEquals(listOf("day 2026-10-12", "day 2026-10-13", "day 2026-10-12"), server.requests)
        assertEquals(1, shown.size)

        server.release()
        runCurrent()
        assertEquals(
            listOf(Read(LoadState.Loaded, listOf(appointment("a1"))), Read(LoadState.Loaded, listOf(appointment("a1", status = AppointmentStatus.COMPLETED)))),
            shown,
        )
    }

    @Test
    fun `a failed background refresh keeps the cached day, and a pull to refresh still reports its failure`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)
        val (_, first) = shownBy(reads.days.watch(monday))
        runCurrent()
        first.cancel()
        runCurrent()

        server.online = false
        val (shown) = shownBy(reads.days.watch(monday))
        runCurrent()

        // Never loading, never failed: the day as it was last loaded.
        assertEquals(listOf(Read(LoadState.Loaded, listOf(appointment("a1")))), shown)

        // Pull to refresh: the failure is the caller's to report, and the day stays.
        val failure = failureOf { reads.days.refresh(monday) }
        runCurrent()
        assertTrue((failure as ApiException).isNetwork)
        assertEquals(listOf(Read(LoadState.Loaded, listOf(appointment("a1")))), shown)
    }

    @Test
    fun `a failed first load is a failure, and trying again shows the spinner and then the day`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"))
            online = false
        }
        val reads = reads(server)
        val (shown) = shownBy(reads.days.watch(monday))
        runCurrent()

        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading), Read(failedOffline)), shown)

        // 'Try again', still offline: loading while it is tried, failed again, and the failure is thrown for the notice.
        val failure = failureOf { reads.days.refresh(monday) }
        runCurrent()
        assertTrue((failure as ApiException).isNetwork)
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading), Read(failedOffline), Read(LoadState.Loading), Read(failedOffline)), shown)

        server.online = true
        server.hold()
        val retry = launch { reads.days.refresh(monday) }
        runCurrent()
        assertEquals(Read<List<Appointment>>(LoadState.Loading), shown.last())

        server.release()
        runCurrent()
        assertTrue(retry.isCompleted)
        assertEquals(Read(LoadState.Loaded, listOf(appointment("a1"))), shown.last())
    }

    @Test
    fun `a day that failed is not cached, and a day the Stylist has left is asked for no longer`() = runTest {
        val server = Server().apply { online = false }
        val reads = reads(server)
        val (_, failed) = shownBy(reads.days.watch(monday))
        runCurrent()
        failed.cancel()
        runCurrent()

        // Back online, the day is a first load again: loading, not the failure from before.
        server.online = true
        server.hold()
        val (shown, leaving) = shownBy(reads.days.watch(monday))
        runCurrent()
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading)), shown)

        // The Stylist moves on before the answer: the request is given up, and nothing of it is shown.
        leaving.cancel()
        runCurrent()
        assertEquals(1, server.abandoned)
        server.release()
        runCurrent()
        assertEquals(1, shown.size)
        assertTrue(reads.days.shown.value.isEmpty())
    }

    @Test
    fun `everyone who asks for a day while it is on its way shares one request`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)

        server.hold()
        val (shown) = shownBy(reads.days.watch(monday))
        val reload = launch { reads.days.refresh(monday) }
        runCurrent()
        assertEquals(listOf("day 2026-10-12"), server.requests)

        server.release()
        runCurrent()
        assertTrue(reload.isCompleted)
        assertEquals(Read(LoadState.Loaded, listOf(appointment("a1"))), shown.last())
        assertEquals(listOf("day 2026-10-12"), server.requests)
    }

    @Test
    fun `how a response of the Store becomes a load state`() {
        val fetcher = StoreReadResponseOrigin.Fetcher()
        val cached = StoreReadResponse.Data(Answer("cached", asked = 0), StoreReadResponseOrigin.Cache)
        val fresh = StoreReadResponse.Data(Answer("fresh", asked = 0), fetcher)
        val failed = StoreReadResponse.Error.Exception(ApiException("Network request failed.", code = "NETWORK", isNetwork = true), fetcher)
        val nothing = Read<String>()

        // Nothing cached: loading, then the answer or the failure.
        assertEquals(Read<String>(LoadState.Loading), nothing.after(StoreReadResponse.Loading(fetcher)))
        assertEquals(Read(LoadState.Loaded, "fresh"), nothing.after(fresh))
        assertEquals(Read<String>(failedOffline), nothing.after(failed))
        assertEquals(Read<String>(LoadState.Loading), Read<String>(failedOffline).after(StoreReadResponse.Loading(fetcher)))

        // Something cached: it stays through the refresh and through its failure, and gives way only to an answer.
        val shown = nothing.after(cached)
        assertEquals(Read(LoadState.Loaded, "cached"), shown)
        assertEquals(shown, shown.after(StoreReadResponse.Loading(fetcher)))
        assertEquals(shown, shown.after(failed))
        assertEquals(shown, shown.after(StoreReadResponse.NoNewData(fetcher)))
        assertEquals(Read(LoadState.Loaded, "fresh"), shown.after(fresh))

        // A failure that is not the API's own is a failure all the same, never a spinner.
        val surprise = StoreReadResponse.Error.Exception(IllegalStateException("boom"), fetcher)
        assertEquals(Read<String>(LoadState.Failed(R.string.error_generic)), nothing.after(surprise))
        assertEquals(Read<String>(LoadState.Failed(R.string.error_generic)), nothing.after(StoreReadResponse.Error.Message("boom", fetcher)))
    }

    @Test
    fun `the Calendar is on the day that was chosen at once, also while the day before is still winding up`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"))
            days[tuesday.day] = listOf(appointment("a2"))
        }
        val reads = reads(server)
        val (_, seen) = shownBy(reads.days.watch(tuesday))
        runCurrent()
        seen.cancel()
        runCurrent()

        val chosen = MutableStateFlow(monday)
        val calendar = mutableListOf<Pair<LocalDate, Read<List<Appointment>>>>()
        val windingUp = CompletableDeferred<Unit>()
        val watch = { key: DayKey ->
            // Giving up Monday's request takes a while, as it does when the request is on another thread.
            if (key == monday) reads.days.watch(key).onCompletion { withContext(NonCancellable) { windingUp.await() } } else reads.days.watch(key)
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { showLatest(chosen, watch) { key, read -> calendar += key.day to read } }
        server.hold()
        runCurrent()
        assertEquals(listOf(monday.day to Read<List<Appointment>>(LoadState.Loading)), calendar)

        // Tuesday, which was shown before: there with the tap, and never under Monday's date or as loading.
        chosen.value = tuesday
        runCurrent()
        assertEquals(tuesday.day to Read(LoadState.Loaded, listOf(appointment("a2"))), calendar.last())
        assertEquals(2, calendar.size)

        // Whatever still comes of Monday is not shown.
        server.release()
        windingUp.complete(Unit)
        runCurrent()
        assertTrue(calendar.drop(1).all { (day) -> day == tuesday.day })
    }

    @Test
    fun `a day seen before is on screen by the time the tap on it returns`() = runTest {
        // As the view model's scope on the main thread: whoever waits for a state runs before the change of it returns.
        val immediate = CoroutineScope(Dispatchers.Unconfined + Job())

        try {
            val server = Server().apply {
                days[monday.day] = listOf(appointment("a1"))
                days[tuesday.day] = listOf(appointment("a2"))
            }
            val reads = AppointmentReads(immediate, Changes(), server::today, server::day, server::find, fetchSources = { emptyList() })
            val timezone = MutableStateFlow<ZoneId?>(null)
            val chosen = MutableStateFlow(monday.day)
            val calendar = mutableListOf<Pair<LocalDate, Read<List<Appointment>>>>()

            // The view model's chain from the selected day to what the Calendar shows.
            immediate.launch {
                showLatest(
                    keys = combine(timezone, chosen) { zone, day -> day to zone }.distinctUntilChanged(),
                    watch = { (day, zone) -> zone?.let { reads.days.watch(DayKey(day, it)) } ?: flowOf(Read()) },
                ) { (day, _), read -> calendar += day to read }
            }
            timezone.value = zone
            chosen.value = tuesday.day
            assertEquals(tuesday.day to Read(LoadState.Loaded, listOf(appointment("a2"))), calendar.last())
            val firstLoads = calendar.size

            // Back and forth between the two days while the server takes its time over every refresh.
            server.hold()
            chosen.value = monday.day
            assertEquals(monday.day to Read(LoadState.Loaded, listOf(appointment("a1"))), calendar.last())
            chosen.value = tuesday.day
            assertEquals(tuesday.day to Read(LoadState.Loaded, listOf(appointment("a2"))), calendar.last())

            // One value per tap: the day from the cache. No loading state and no empty list in between.
            assertEquals(firstLoads + 2, calendar.size)
        } finally {
            immediate.cancel()
        }
    }

    // --- a change ---

    @Test
    fun `after a change the affected day and Today show the new data`() = runTest {
        val scheduled = appointment("a1")
        val server = Server().apply {
            days[monday.day] = listOf(scheduled, appointment("a2", clientId = "c2"))
            todays = listOf(scheduled)
        }
        val reads = reads(server)
        val (today) = shownBy(reads.today.watch(monday))
        val (day) = shownBy(reads.days.watch(monday))
        runCurrent()

        // The status is saved. What the server confirmed is on both screens before anything is loaded again...
        val completed = scheduled.copy(status = AppointmentStatus.COMPLETED)
        server.days[monday.day] = listOf(completed.copy(note = "paid"), appointment("a2", clientId = "c2"))
        server.todays = listOf(completed.copy(note = "paid"))
        reads.update("a1") { it.copy(status = AppointmentStatus.COMPLETED) }
        reads.statusChanged("a1")
        assertEquals(Read(LoadState.Loaded, listOf(completed)), reads.today.shown(monday))
        assertEquals(listOf(completed, appointment("a2", clientId = "c2")), reads.days.shown(monday)?.data)

        // ...and the reload brings the rest of what the server has.
        reads.today.refresh(monday)
        reads.days.refresh(monday)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(completed.copy(note = "paid"))), today.last())
        assertEquals("paid", day.last().data?.first()?.note)
        // Loading only before the first answer: the change and the reload showed no loading state.
        assertEquals(listOf(LoadState.Loading, LoadState.Loaded, LoadState.Loaded, LoadState.Loaded), today.map { it.state })

        // The result is what the cache has now: shown at once the next time.
        val (again) = shownBy(reads.days.watch(monday))
        assertEquals("paid", again.single().data?.first()?.note)
    }

    @Test
    fun `a change is on screen also when the reload after it fails`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)
        val (day) = shownBy(reads.days.watch(monday))
        runCurrent()

        server.online = false
        reads.update("a1") { it.copy(note = "Bring swatches") }
        val failure = failureOf { reads.days.refresh(monday) }
        runCurrent()

        assertTrue(failure is ApiException)
        assertEquals(Read(LoadState.Loaded, listOf(appointment("a1").copy(note = "Bring swatches"))), day.last())

        // A second screen on the same day shows what the first one does, not a load that fails.
        val (second) = shownBy(reads.days.watch(monday))
        runCurrent()
        assertEquals(listOf(Read(LoadState.Loaded, listOf(appointment("a1").copy(note = "Bring swatches")))), second)
    }

    @Test
    fun `a cached day that holds a changed Appointment is not served again, and a day that does not is`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"))
            days[tuesday.day] = listOf(appointment("a2", clientId = "c2"))
        }
        val reads = reads(server)

        // Both days were shown, and neither is on screen: the Appointment was opened from a Client's history.
        for (key in listOf(monday, tuesday)) {
            val (_, seen) = shownBy(reads.days.watch(key))
            runCurrent()
            seen.cancel()
            runCurrent()
        }

        server.days[monday.day] = listOf(appointment("a1", status = AppointmentStatus.CANCELED))
        reads.update("a1") { it.copy(status = AppointmentStatus.CANCELED) }
        server.hold()

        // Monday as it was cached shows a status that is no longer true: it is loaded anew instead.
        val (onMonday) = shownBy(reads.days.watch(monday))
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading)), onMonday)

        // Tuesday has nothing to do with the change and is there at once.
        val (onTuesday) = shownBy(reads.days.watch(tuesday))
        assertEquals(listOf(Read(LoadState.Loaded, listOf(appointment("a2", clientId = "c2")))), onTuesday)

        server.release()
        runCurrent()
        assertEquals(AppointmentStatus.CANCELED, onMonday.last().data?.single()?.status)
    }

    @Test
    fun `another status can put an Appointment into Today's list, so that list is not served as it was`() = runTest {
        val server = Server().apply { todays = listOf(appointment("a1")) }
        val reads = reads(server)
        val (_, seen) = shownBy(reads.today.watch(monday))
        runCurrent()
        seen.cancel()
        runCurrent()

        // Last week's Appointment is Scheduled again: it is overdue now, and Today's list has it.
        server.todays = listOf(appointment("a0", clientId = "c2"), appointment("a1"))
        reads.statusChanged("a0")
        server.hold()
        val (today) = shownBy(reads.today.watch(monday))
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading)), today)

        server.release()
        runCurrent()
        assertEquals(listOf("a0", "a1"), today.last().data?.map { it.id })
    }

    @Test
    fun `a new Appointment can be on any day, so no cached day is served as it was`() = runTest {
        val changes = Changes()
        val server = Server().apply { days[tuesday.day] = listOf(appointment("a2")) }
        val reads = reads(server, changes)
        val (_, seen) = shownBy(reads.days.watch(tuesday))
        runCurrent()
        seen.cancel()
        runCurrent()

        // Created on Tuesday from another screen. Its own screen opens with the reply, before any read of it has answered.
        val created = appointment("a3", clientId = "c3")
        server.days[tuesday.day] = listOf(appointment("a2"), created)
        changes.changedAnywhere()
        reads.created(created)
        server.hold()
        val (onItsScreen) = shownBy(reads.watch("a3"))
        runCurrent()
        assertEquals(listOf(Read<Appointment?>(LoadState.Loaded, created)), onItsScreen)

        val (onTuesday) = shownBy(reads.days.watch(tuesday))
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading)), onTuesday)

        server.release()
        runCurrent()
        assertEquals(listOf("a2", "a3"), onTuesday.last().data?.map { it.id })
    }

    @Test
    fun `an answer asked for before a confirmed change is not shown, and the read is asked again`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)
        val (day) = shownBy(reads.days.watch(monday))
        runCurrent()

        // A reload is on its way, with a note from another device in its answer...
        server.days[monday.day] = listOf(appointment("a1").copy(note = "from before"))
        server.hold()
        val reload = launch { reads.days.refresh(monday) }
        runCurrent()

        // ...when a Service is added: nothing to put on screen here, but the answer on its way does not have it.
        server.days[monday.day] = listOf(appointment("a1").copy(note = "from before", serviceTotalCents = 4500))
        reads.update("a1") { it }
        server.release()
        runCurrent()

        // The old answer went nowhere, and the reload ended with one from after the change.
        assertTrue(reload.isCompleted)
        assertEquals(listOf("day 2026-10-12", "day 2026-10-12", "day 2026-10-12"), server.requests)
        assertEquals(4500L, day.last().data?.single()?.serviceTotalCents)
        assertTrue(day.none { read -> read.data.orEmpty().any { it.note == "from before" && it.serviceTotalCents == 0L } })
    }

    // --- a saved Client ---

    private val newNumber = ClientProfile(id = "c1", name = "Phone Test", language = Language.EN, phone = "5550002")

    @Test
    fun `a saved Client's new phone is on every Appointment on screen, also when the reload fails`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"), appointment("a2", phone = "5550100", clientId = "c2"))
            todays = listOf(appointment("a1"))
        }
        val reads = reads(server)
        val (today) = shownBy(reads.today.watch(monday))
        val (day) = shownBy(reads.days.watch(monday))
        val (own) = shownBy(reads.watch("a1"))
        runCurrent()

        server.online = false
        reads.clientSaved(newNumber)
        failureOf { reads.days.refresh(monday) }
        failureOf { reads.today.refresh(monday) }
        runCurrent()

        assertEquals(listOf("5550002"), today.last().data?.map { it.participants.single().phone })
        assertEquals(listOf("5550002", "5550100"), day.last().data?.map { it.participants.single().phone })
        assertEquals("5550002", own.last().data?.participants?.single()?.phone)
        assertEquals("5550002", reads["a1"]?.participants?.single()?.phone)
    }

    @Test
    fun `a Client save cannot leave the old phone number in a cached Appointment`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"))
            days[tuesday.day] = listOf(appointment("a2", phone = "5550100", clientId = "c2"))
            days[wednesday.day] = listOf(appointment("a3"))
        }
        val reads = reads(server)

        // Monday and the Appointment itself are cached and on no screen. Wednesday is on its way.
        val (_, onMonday) = shownBy(reads.days.watch(monday))
        val (_, onItsScreen) = shownBy(reads.watch("a1"))
        runCurrent()
        onMonday.cancel()
        onItsScreen.cancel()
        runCurrent()
        server.hold()
        val (wednesdays) = shownBy(reads.days.watch(wednesday))
        runCurrent()

        // The Client's phone is saved while the phone is offline for everything after it.
        server.days[monday.day] = listOf(appointment("a1", phone = "5550002"))
        server.days[wednesday.day] = listOf(appointment("a3", phone = "5550002"))
        reads.clientSaved(newNumber)
        server.release()
        server.online = false
        runCurrent()

        // What was on its way was asked before the save: it is not shown.
        assertTrue(wednesdays.none { read -> read.data.orEmpty().any { it.participants.single().phone == "5550001" } })

        // Neither cached copy is served: each is a load that fails, with nothing to send a reminder to.
        val (mondays) = shownBy(reads.days.watch(monday))
        val (appointments) = shownBy(reads.watch("a1"))
        runCurrent()
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading), Read(failedOffline)), mondays)
        assertEquals(listOf(Read<Appointment?>(LoadState.Loading), Read(failedOffline)), appointments)

        // Online again: the new number, and only that.
        server.online = true
        reads.days.refresh(monday)
        reads.days.refresh(wednesday)
        runCurrent()
        assertEquals("5550002", mondays.last().data?.single()?.participants?.single()?.phone)
        assertEquals("5550002", wednesdays.last().data?.single()?.participants?.single()?.phone)
    }

    // --- one Appointment ---

    @Test
    fun `an Appointment that no list on screen has is loading until it arrives`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)

        server.hold()
        val (shown) = shownBy(reads.watch("a1"))
        runCurrent()
        assertEquals(listOf(Read<Appointment?>(LoadState.Loading)), shown)
        assertNull(reads["a1"])

        server.release()
        runCurrent()
        assertEquals(Read<Appointment?>(LoadState.Loaded, appointment("a1")), shown.last())
        assertEquals("a1", reads["a1"]?.id)
    }

    @Test
    fun `an Appointment opened from a list on screen is shown at once, and then as its own read has it`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)
        shownBy(reads.days.watch(monday))
        runCurrent()

        server.days[monday.day] = listOf(appointment("a1").copy(note = "Bring swatches"))
        server.hold()
        val (shown) = shownBy(reads.watch("a1"))
        runCurrent()
        assertEquals(listOf(Read<Appointment?>(LoadState.Loaded, appointment("a1"))), shown)

        server.release()
        runCurrent()
        assertEquals("Bring swatches", shown.last().data?.note)
        assertTrue(shown.all { it.state == LoadState.Loaded })
    }

    @Test
    fun `an Appointment the server no longer has is missing, not loading`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a2")) }
        val reads = reads(server)

        // Opened from a history row after it was deleted in another session.
        val (gone) = shownBy(reads.watch("a1"))
        runCurrent()
        assertEquals(Read<Appointment?>(LoadState.Missing), gone.last())

        // On screen from a list, then gone with its only Client: its own read says so, whatever the list still shows.
        shownBy(reads.days.watch(monday))
        val (listed) = shownBy(reads.watch("a2"))
        runCurrent()
        assertEquals("a2", listed.last().data?.id)

        server.days.clear()
        assertNull(reads.one.refresh(AppointmentKey("a2")).data)
        runCurrent()
        assertEquals(Read<Appointment?>(LoadState.Missing), listed.last())
        assertNull(reads["a2"])
        assertTrue(reads.isListed("a2"))
    }

    @Test
    fun `an Appointment that failed to load says why, and trying again loads it`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"))
            online = false
        }
        val reads = reads(server)
        val (shown) = shownBy(reads.watch("a1"))
        runCurrent()
        assertEquals(Read<Appointment?>(failedOffline), shown.last())

        server.online = true
        server.hold()
        val retry = launch { reads.one.refresh(AppointmentKey("a1")) }
        runCurrent()
        assertEquals(Read<Appointment?>(LoadState.Loading), shown.last())

        server.release()
        runCurrent()
        assertTrue(retry.isCompleted)
        assertEquals("a1", shown.last().data?.id)
    }

    @Test
    fun `an Appointment on screen stays there when loading it again fails`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)
        val (shown) = shownBy(reads.watch("a1"))
        runCurrent()

        server.online = false
        val failure = failureOf { reads.one.refresh(AppointmentKey("a1")) }
        runCurrent()

        assertTrue(failure is ApiException)
        assertEquals(Read<Appointment?>(LoadState.Loaded, appointment("a1")), shown.last())
    }

    @Test
    fun `a deleted Appointment leaves the lists, and its screen says that it is gone while it is looked up again`() = runTest {
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"), appointment("a2", clientId = "c2"))
            todays = listOf(appointment("a1"))
        }
        val reads = reads(server)
        val (today) = shownBy(reads.today.watch(monday))
        val (day) = shownBy(reads.days.watch(monday))
        val (shown) = shownBy(reads.watch("a1"))
        runCurrent()

        server.days[monday.day] = listOf(appointment("a2", clientId = "c2"))
        server.todays = emptyList()
        reads.forget("a1")
        runCurrent()
        assertEquals(Read(LoadState.Loaded, emptyList<Appointment>()), today.last())
        assertEquals(listOf("a2"), day.last().data?.map { it.id })
        assertEquals(Read<Appointment?>(LoadState.Missing), shown.last())
        assertNull(reads["a1"])

        // The reload after the delete, and one that fails: the screen keeps saying that it is gone.
        val states = shown.size
        reads.one.refresh(AppointmentKey("a1"))
        server.online = false
        failureOf { reads.one.refresh(AppointmentKey("a1")) }
        runCurrent()
        assertEquals(states, shown.size)
        assertEquals(Read<Appointment?>(LoadState.Missing), shown.last())
    }

    @Test
    fun `an answer about an Appointment that was asked for before a change to it does not undo the change`() = runTest {
        val server = Server().apply { days[monday.day] = listOf(appointment("a1")) }
        val reads = reads(server)
        val (shown) = shownBy(reads.watch("a1"))
        runCurrent()
        val before = shown.size

        // The screen came into view and asked for the Appointment again...
        server.hold()
        val reload = launch { reads.one.refresh(AppointmentKey("a1")) }
        runCurrent()

        // ...and before the answer the Client's phone was changed and saved.
        server.days[monday.day] = listOf(appointment("a1", phone = "5550002", status = AppointmentStatus.COMPLETED))
        reads.clientSaved(newNumber)
        server.release()
        runCurrent()

        // The old answer is never shown. The Appointment is asked for once more instead.
        assertTrue(reload.isCompleted)
        assertEquals(listOf("get a1", "get a1", "get a1"), server.requests)
        assertTrue(shown.size > before)
        assertTrue(shown.drop(before).all { it.data?.participants?.single()?.phone == "5550002" })
        assertEquals(AppointmentStatus.COMPLETED, shown.last().data?.status)
    }

    // --- sign-out ---

    @Test
    fun `sign-out empties the cache, and what was on its way for the previous Stylist goes nowhere`() = runTest {
        val changes = Changes()
        val server = Server().apply {
            days[monday.day] = listOf(appointment("a1"))
            days[tuesday.day] = listOf(appointment("a2"))
            todays = listOf(appointment("a1"))
        }
        val reads = reads(server, changes)
        val (_, today) = shownBy(reads.today.watch(monday))
        val (_, day) = shownBy(reads.days.watch(monday))
        val (_, own) = shownBy(reads.watch("a1"))
        runCurrent()

        // Two reloads are on their way at sign-out: one that will be answered and one that will fail.
        server.hold()
        var answered: Read<List<Appointment>>? = null
        var thrown: Exception? = null
        launch { answered = reads.days.refresh(tuesday) }
        runCurrent()
        server.online = false
        launch { thrown = failureOf { reads.today.refresh(monday) } }
        runCurrent()

        // Signed out: nothing watches any more, and the caches start over.
        listOf(today, day, own).forEach(Job::cancel)
        changes.startOver()
        reads.clear()
        server.release()
        runCurrent()
        assertEquals(Read<List<Appointment>>(LoadState.Loading), answered)
        assertNull(thrown)
        assertTrue(reads.all().isEmpty())

        // The next Stylist on this device: every key is a first load, answered with what is theirs.
        server.online = true
        server.days[monday.day] = listOf(appointment("b1", clientId = "c9"))
        server.days[tuesday.day] = emptyList()
        server.todays = emptyList()
        server.hold()
        val (todays) = shownBy(reads.today.watch(monday))
        val (mondays) = shownBy(reads.days.watch(monday))
        val (tuesdays) = shownBy(reads.days.watch(tuesday))
        val (appointments) = shownBy(reads.watch("a1"))
        runCurrent()
        listOf(todays, mondays, tuesdays, appointments).forEach { shown -> assertEquals(1, shown.size) }
        assertEquals(Read<List<Appointment>>(LoadState.Loading), todays.single())
        assertEquals(Read<List<Appointment>>(LoadState.Loading), mondays.single())
        assertEquals(Read<List<Appointment>>(LoadState.Loading), tuesdays.single())
        assertEquals(Read<Appointment?>(LoadState.Loading), appointments.single())

        server.release()
        runCurrent()
        assertEquals(listOf("b1"), mondays.last().data?.map { it.id })
        assertEquals(Read(LoadState.Loaded, emptyList<Appointment>()), tuesdays.last())
        assertEquals(Read<Appointment?>(LoadState.Missing), appointments.last())
        assertFalse(reads.all().any { it.id == "a1" || it.id == "a2" })
    }
}
