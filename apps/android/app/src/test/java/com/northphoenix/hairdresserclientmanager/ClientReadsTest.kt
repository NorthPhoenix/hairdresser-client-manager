package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.state.AppointmentReads
import com.northphoenix.hairdresserclientmanager.state.Changes
import com.northphoenix.hairdresserclientmanager.state.ClientKey
import com.northphoenix.hairdresserclientmanager.state.ClientReads
import com.northphoenix.hairdresserclientmanager.state.CopyTargetKey
import com.northphoenix.hairdresserclientmanager.state.DayKey
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.state.ServiceMenuReads
import com.northphoenix.hairdresserclientmanager.state.StylistKey
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Client list, a Client's Appointment history and the Service Menu are read through the read
 * cache (docs/adr/0016), by the rules ReadCacheTest proves for Appointments: shown at once when
 * seen before, loading only when nothing is cached, kept when a refresh behind them fails, and
 * never shown or served as they were before a change that may have made them wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientReadsTest {
    private val stylist = StylistKey("s1")
    private val annaKey = ClientKey("anna")

    private val anna = ClientProfile(id = "anna", name = "Anna Avery", language = Language.EN, phone = "5550001")
    private val bella = ClientProfile(id = "bella", name = "Bella Brook", language = Language.RU, phone = "5550002")
    private val cut = ServiceMenuItem(id = "cut", name = "Cut", defaultPriceCents = 4_000)
    private val color = ServiceMenuItem(id = "color", name = "Color", defaultPriceCents = 9_000)

    private val failedOffline = LoadState.Failed(R.string.network_error)

    private fun entry(id: String, status: AppointmentStatus = AppointmentStatus.COMPLETED) =
        ClientHistoryEntry(id = id, primaryClientName = "Anna Avery", startsAt = "2026-10-12T15:00:00.000Z", status = status)

    /** Answers as a server does: with what it had when the request arrived, also when the answer is held back. */
    private class Server {
        var clients = emptyList<ClientProfile>()
        val histories = mutableMapOf<String, List<ClientHistoryEntry>>()
        var menu = emptyList<ServiceMenuItem>()
        var online = true
        val requests = mutableListOf<String>()
        private var held: CompletableDeferred<Unit>? = null

        /** Requests from now on get no answer until [release]. */
        fun hold() {
            held = CompletableDeferred()
        }

        fun release() {
            held?.complete(Unit)
            held = null
        }

        suspend fun clients(key: StylistKey): List<ClientProfile> = answer("clients ${key.stylistId}") { clients }

        suspend fun history(key: ClientKey): List<ClientHistoryEntry> = answer("history ${key.id}") { histories[key.id].orEmpty() }

        suspend fun menu(key: StylistKey): List<ServiceMenuItem> = answer("menu ${key.stylistId}") { menu }

        private suspend fun <T> answer(request: String, read: () -> T): T {
            val gate = held
            val reachable = online
            val answer = read()

            requests += request
            gate?.await()
            if (!reachable) {
                throw ApiException("Network request failed.", code = "NETWORK", isNetwork = true)
            }

            return answer
        }
    }

    private fun TestScope.clientReads(server: Server, changes: Changes = Changes()) =
        ClientReads(backgroundScope, changes, server::clients, server::history)

    private fun TestScope.menuReads(server: Server, changes: Changes = Changes()) = ServiceMenuReads(backgroundScope, changes, server::menu)

    /** What a screen that reads [flow] is shown, value by value. It starts reading at once, as a screen does. */
    private fun <T> TestScope.shownBy(flow: Flow<T>): Pair<List<T>, Job> {
        val shown = mutableListOf<T>()

        return shown to backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { shown += it } }
    }

    private suspend fun failureOf(read: suspend () -> Unit): Exception? =
        try {
            read()
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }

    // --- the Client list ---

    @Test
    fun `the Client list is loading until its first load ends, and on screen from then on`() = runTest {
        val server = Server().apply { clients = listOf(anna) }
        val reads = clientReads(server)

        server.hold()
        val (shown) = shownBy(reads.list.watch(stylist))
        runCurrent()
        // On its way: a spinner, not "No Clients yet".
        assertEquals(listOf(Read<List<ClientProfile>>(LoadState.Loading)), shown)

        server.release()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(anna)), shown.last())

        // A picker that opens over it reads the same list: there at once, with no loading state of its own.
        server.hold()
        val (picker) = shownBy(reads.list.watch(stylist))
        assertEquals(listOf(Read(LoadState.Loaded, listOf(anna))), picker)
        runCurrent()
        assertEquals(1, picker.size)
    }

    @Test
    fun `a failed refresh keeps the Client list, and a pull to refresh still reports its failure`() = runTest {
        val server = Server().apply { clients = listOf(anna) }
        val reads = clientReads(server)
        val (shown) = shownBy(reads.list.watch(stylist))
        runCurrent()

        server.online = false
        val failure = failureOf { reads.list.refresh(stylist) }
        runCurrent()

        // Thrown for the caller to report, and the list is neither loading nor failed.
        assertTrue((failure as ApiException).isNetwork)
        assertEquals(listOf(Read<List<ClientProfile>>(LoadState.Loading), Read(LoadState.Loaded, listOf(anna))), shown)
    }

    @Test
    fun `a saved Client shows in the list and in pickers at once, also when the reload fails`() = runTest {
        val server = Server().apply { clients = listOf(anna) }
        val reads = clientReads(server)
        val (list) = shownBy(reads.list.watch(stylist))
        val (picker) = shownBy(reads.list.watch(stylist))
        runCurrent()
        val asked = server.requests.size

        // The server confirms a new Client. The network is gone for the reload after it.
        server.clients = listOf(bella, anna)
        server.online = false
        reads.saved(bella)

        // On screen by the time the save returns, first as the server lists them, and nothing was asked for it.
        assertEquals(listOf(bella, anna), reads.list.shown(stylist)?.data)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(bella, anna)), list.last())
        assertEquals(Read(LoadState.Loaded, listOf(bella, anna)), picker.last())
        assertEquals(asked, server.requests.size)

        assertTrue((failureOf { reads.list.refresh(stylist) } as ApiException).isNetwork)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(bella, anna)), list.last())

        // A Client the list had is saved again: as saved, and first.
        val renamed = anna.copy(name = "Anna Avery-Stone")
        reads.saved(renamed)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(renamed, bella)), picker.last())
    }

    @Test
    fun `a Client list that was asked for before a save does not take the saved Client away again`() = runTest {
        val server = Server().apply { clients = listOf(anna) }
        val reads = clientReads(server)
        val (shown) = shownBy(reads.list.watch(stylist))
        runCurrent()

        // A refresh is on its way with the list as it was. The save is confirmed before its answer arrives.
        server.hold()
        val reload = launch { reads.list.refresh(stylist) }
        runCurrent()
        server.clients = listOf(bella, anna)
        reads.saved(bella)
        server.release()
        runCurrent()

        // The old answer is dropped and the list asked once more.
        assertTrue(reload.isCompleted)
        assertEquals(listOf("clients s1", "clients s1", "clients s1"), server.requests)
        assertTrue(shown.dropWhile { bella !in it.data.orEmpty() }.all { bella in it.data.orEmpty() })
        assertEquals(Read(LoadState.Loaded, listOf(bella, anna)), shown.last())
    }

    @Test
    fun `a new Client whose save got no reply puts the cached list out of date`() = runTest {
        val server = Server().apply { clients = listOf(anna) }
        val reads = clientReads(server)
        val (_, first) = shownBy(reads.list.watch(stylist))
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(anna)), reads.list.now(stylist))

        // The save may have reached the server: the new Client has no id here, and is in no cached answer.
        reads.changed(null)

        assertEquals(Read<List<ClientProfile>>(), reads.list.now(stylist))
    }

    @Test
    fun `a deleted Client is gone everywhere`() = runTest {
        val zone = ZoneId.of("America/Chicago")
        val monday = DayKey(LocalDate.of(2026, 10, 12), zone)
        val copyTarget = CopyTargetKey("a2", setOf("anna", "bella"))
        val annas = Appointment(
            id = "a1",
            primaryClientId = "anna",
            primaryClientName = "Anna Avery",
            participants = listOf(AppointmentParticipant(clientId = "anna", name = "Anna Avery", phone = "5550001", isPrimary = true)),
            startsAt = "2026-10-12T15:00:00.000Z",
            status = AppointmentStatus.COMPLETED,
            locationType = LocationType.IN_SALON,
        )
        var onMonday = listOf(annas)
        var sources = listOf(annas)
        val changes = Changes()
        val server = Server().apply {
            clients = listOf(anna, bella)
            histories["anna"] = listOf(entry("a1"))
        }
        val clients = clientReads(server, changes)
        val appointments = AppointmentReads(
            backgroundScope,
            changes,
            fetchToday = { emptyList() },
            fetchDay = { onMonday },
            find = { null },
            fetchSources = { sources },
            fetchWeek = { emptyList() },
        )

        // The list is on screen. Monday, Anna's history and a copy sheet that offers her Appointment were shown before.
        val (list) = shownBy(clients.list.watch(stylist))
        listOf(
            shownBy(appointments.days.watch(monday)),
            shownBy(clients.history.watch(annaKey)),
            shownBy(appointments.sources.watch(copyTarget)),
        ).forEach { (_, seen) ->
            runCurrent()
            seen.cancel()
        }
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(annas)), appointments.days.now(monday))
        assertEquals(Read(LoadState.Loaded, listOf(entry("a1"))), clients.history.now(annaKey))
        assertEquals(Read(LoadState.Loaded, listOf(annas)), appointments.sources.now(copyTarget))

        // The server deletes Anna, and her Appointment with her.
        server.clients = listOf(bella)
        server.histories.remove("anna")
        onMonday = emptyList()
        sources = emptyList()
        clients.deleted("anna")

        // Gone from the list, and so from every picker, by the time the delete returns.
        assertEquals(listOf(bella), clients.list.shown(stylist)?.data)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(bella)), list.last())

        // Nothing that names her is served from the cache any more: each is loaded anew when it is next shown.
        assertEquals(Read<List<Appointment>>(), appointments.days.now(monday))
        assertEquals(Read<List<ClientHistoryEntry>>(), clients.history.now(annaKey))
        assertEquals(Read<List<Appointment>>(), appointments.sources.now(copyTarget))

        val (day) = shownBy(appointments.days.watch(monday))
        val (sheet) = shownBy(appointments.sources.watch(copyTarget))
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading)), day)
        assertEquals(listOf(Read<List<Appointment>>(LoadState.Loading)), sheet)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, emptyList<Appointment>()), day.last())
        assertEquals(Read(LoadState.Loaded, emptyList<Appointment>()), sheet.last())
    }

    @Test
    fun `a Profile Share the server answered with is on the Client at once`() = runTest {
        val share = ProfileShare(id = "share1", token = "token", language = Language.EN, createdAt = "2026-10-12T15:00:00.000Z")
        val server = Server().apply { clients = listOf(anna, bella) }
        val reads = clientReads(server)
        val (shown) = shownBy(reads.list.watch(stylist))
        runCurrent()

        // A refresh from before the Profile Share was made is still on its way.
        server.hold()
        val reload = launch { reads.list.refresh(stylist) }
        runCurrent()
        server.clients = listOf(anna.copy(activeProfileShare = share), bella)
        reads.profileShare("anna", share)
        assertEquals(share, reads.list.shown(stylist)?.data?.first()?.activeProfileShare)

        // Its answer has no Profile Share: it is not shown, and the list is asked again.
        server.release()
        runCurrent()
        assertTrue(reload.isCompleted)
        assertTrue(shown.dropWhile { it.data?.firstOrNull()?.activeProfileShare == null }.all { it.data?.first()?.activeProfileShare == share })
        assertEquals(share, shown.last().data?.first()?.activeProfileShare)

        // Revoked: gone from the card at once.
        reads.profileShare("anna", null)
        assertNull(reads.list.shown(stylist)?.data?.first()?.activeProfileShare)
    }

    // --- a Client's Appointment history ---

    @Test
    fun `a history is loading the first time, and there from the first frame when it was seen before`() = runTest {
        val server = Server().apply { histories["anna"] = listOf(entry("a1", AppointmentStatus.SCHEDULED)) }
        val reads = clientReads(server)

        // The profile opens for the first time: nothing to show yet.
        assertEquals(Read<List<ClientHistoryEntry>>(), reads.history.now(annaKey))
        server.hold()
        val (first, profile) = shownBy(reads.history.watch(annaKey))
        runCurrent()
        assertEquals(listOf(Read<List<ClientHistoryEntry>>(LoadState.Loading)), first)

        server.release()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(entry("a1", AppointmentStatus.SCHEDULED))), first.last())

        // The Stylist goes to another screen and comes back. Another device has completed the Appointment meanwhile.
        profile.cancel()
        runCurrent()
        server.histories["anna"] = listOf(entry("a1"))
        server.hold()

        // The first frame has the history as it was, without asking the server for it.
        assertEquals(Read(LoadState.Loaded, listOf(entry("a1", AppointmentStatus.SCHEDULED))), reads.history.now(annaKey))
        assertEquals(1, server.requests.size)

        // So has the read, at once. It is asked again behind what is shown, and that shows nothing either.
        val (again) = shownBy(reads.history.watch(annaKey))
        assertEquals(listOf(Read(LoadState.Loaded, listOf(entry("a1", AppointmentStatus.SCHEDULED)))), again)
        runCurrent()
        assertEquals(listOf("history anna", "history anna"), server.requests)
        assertEquals(1, again.size)

        server.release()
        runCurrent()
        assertEquals(
            listOf(Read(LoadState.Loaded, listOf(entry("a1", AppointmentStatus.SCHEDULED))), Read(LoadState.Loaded, listOf(entry("a1")))),
            again,
        )
    }

    @Test
    fun `a history that failed to load says why, and one on screen stays when loading it again fails`() = runTest {
        val server = Server().apply {
            histories["anna"] = listOf(entry("a1"))
            online = false
        }
        val reads = clientReads(server)
        val (shown, profile) = shownBy(reads.history.watch(annaKey))
        runCurrent()
        assertEquals(listOf(Read<List<ClientHistoryEntry>>(LoadState.Loading), Read(failedOffline)), shown)

        // The load the profile asks for when it comes into view throws its failure, for the notice.
        assertTrue((failureOf { reads.history.refresh(annaKey) } as ApiException).isNetwork)

        // 'Try again', back online: the spinner, then the history.
        server.online = true
        server.hold()
        val retry = launch { reads.history.refresh(annaKey) }
        runCurrent()
        assertEquals(Read<List<ClientHistoryEntry>>(LoadState.Loading), shown.last())
        server.release()
        runCurrent()
        assertTrue(retry.isCompleted)
        assertEquals(Read(LoadState.Loaded, listOf(entry("a1"))), shown.last())

        // Coming back to the profile offline: the history as it was last loaded, and nothing is said on screen.
        profile.cancel()
        runCurrent()
        server.online = false
        val (again) = shownBy(reads.history.watch(annaKey))
        runCurrent()
        assertEquals(listOf(Read(LoadState.Loaded, listOf(entry("a1")))), again)
        assertTrue((failureOf { reads.history.refresh(annaKey) } as ApiException).isNetwork)
        assertEquals(listOf(Read(LoadState.Loaded, listOf(entry("a1")))), again)
    }

    @Test
    fun `a history is not served from the cache after any change, and one on screen is asked again`() = runTest {
        val changes = Changes()
        val server = Server().apply { histories["anna"] = listOf(entry("a1")) }
        val reads = clientReads(server, changes)
        val (onScreen, profile) = shownBy(reads.history.watch(annaKey))
        runCurrent()

        // Anna is added to an Appointment her history does not have yet: no id in the history says so.
        server.histories["anna"] = listOf(entry("a7", AppointmentStatus.SCHEDULED), entry("a1"))
        changes.changed("a7")

        // What is on screen stays until it is reloaded, and is reloaded when asked.
        assertEquals(Read(LoadState.Loaded, listOf(entry("a1"))), reads.history.shown(annaKey))
        reads.history.refreshShown()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(entry("a7", AppointmentStatus.SCHEDULED), entry("a1"))), onScreen.last())

        // Another change while the profile is not in view: the cached history is not shown again, it is loaded anew.
        profile.cancel()
        runCurrent()
        assertEquals(LoadState.Loaded, reads.history.now(annaKey).state)
        changes.changed("a1")
        assertEquals(Read<List<ClientHistoryEntry>>(), reads.history.now(annaKey))

        server.hold()
        val asked = server.requests.size
        val (again) = shownBy(reads.history.watch(annaKey))
        runCurrent()
        assertEquals(listOf(Read<List<ClientHistoryEntry>>(LoadState.Loading)), again)
        assertEquals(asked + 1, server.requests.size)
    }

    @Test
    fun `no history is asked for while no profile is open`() = runTest {
        val server = Server().apply { histories["anna"] = listOf(entry("a1")) }
        val reads = clientReads(server)
        val (_, profile) = shownBy(reads.history.watch(annaKey))
        runCurrent()
        profile.cancel()
        runCurrent()

        reads.history.refreshShown()

        assertEquals(listOf("history anna"), server.requests)
    }

    // --- the Service Menu ---

    @Test
    fun `the Service Menu is loading until its first load ends, and stays when a reload fails`() = runTest {
        val server = Server().apply { menu = listOf(cut, color) }
        val reads = menuReads(server)

        server.hold()
        val (shown) = shownBy(reads.menu.watch(stylist))
        runCurrent()
        assertEquals(listOf(Read<List<ServiceMenuItem>>(LoadState.Loading)), shown)

        server.release()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(cut, color)), shown.last())

        server.online = false
        assertTrue((failureOf { reads.menu.refresh(stylist) } as ApiException).isNetwork)
        runCurrent()
        assertEquals(listOf(Read<List<ServiceMenuItem>>(LoadState.Loading), Read(LoadState.Loaded, listOf(cut, color))), shown)
    }

    @Test
    fun `a saved Service Menu Item is in the menu at once, and a deleted one is gone, also when the reload fails`() = runTest {
        val server = Server().apply { menu = listOf(cut, color) }
        val reads = menuReads(server)
        val (shown) = shownBy(reads.menu.watch(stylist))
        runCurrent()
        server.online = false

        // Another price for an item of the menu: replaced where it stands.
        val dearer = cut.copy(defaultPriceCents = 4_500)
        reads.saved(dearer)
        assertEquals(listOf(dearer, color), reads.menu.shown(stylist)?.data)

        reads.deleted("color")
        assertEquals(listOf(dearer), reads.menu.shown(stylist)?.data)

        assertTrue((failureOf { reads.menu.refresh(stylist) } as ApiException).isNetwork)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(dearer)), shown.last())
    }

    @Test
    fun `a saved Service Menu Item keeps how often it was used and how often with a Color Formula`() = runTest {
        val used = cut.copy(useCount = 5, formulaCount = 2)
        val server = Server().apply { menu = listOf(used, color) }
        val reads = menuReads(server)
        reads.menu.watch(stylist).let { shownBy(it) }
        runCurrent()

        // The reply of a save does not carry the counts: they stay as the list said.
        reads.saved(cut.copy(defaultPriceCents = 4_500))

        assertEquals(used.copy(defaultPriceCents = 4_500), reads.menu.shown(stylist)?.data?.first())
    }

    @Test
    fun `an item saved as the Travel Fee is the only one in the menu at once`() = runTest {
        val server = Server().apply { menu = listOf(cut.copy(travelFee = true), color) }
        val reads = menuReads(server)
        shownBy(reads.menu.watch(stylist))
        runCurrent()
        // What is shown before any reload: the reload has the server's word on it.
        server.online = false

        reads.saved(color.copy(travelFee = true))
        assertEquals(listOf(cut, color.copy(travelFee = true)), reads.menu.shown(stylist)?.data)

        // Another save of an item that is not the Travel Fee leaves the one that is.
        reads.saved(cut.copy(defaultPriceCents = 4_500))
        assertEquals(listOf(cut.copy(defaultPriceCents = 4_500), color.copy(travelFee = true)), reads.menu.shown(stylist)?.data)
    }

    @Test
    fun `a Service Menu that was asked for before a change is not shown, and asking again brings the new item`() = runTest {
        val server = Server().apply { menu = listOf(cut) }
        val reads = menuReads(server)
        val (shown) = shownBy(reads.menu.watch(stylist))
        runCurrent()

        // A refresh is on its way with the menu as it was. A new item is confirmed before its answer arrives.
        server.hold()
        val refresh = launch { reads.menu.refresh(stylist) }
        runCurrent()
        server.menu = listOf(color, cut)
        reads.saved(color)
        server.release()
        runCurrent()

        // The old answer is dropped and the menu asked once more: where the new item goes is the server's to say.
        assertTrue(refresh.isCompleted)
        assertEquals(listOf("menu s1", "menu s1", "menu s1"), server.requests)
        assertEquals(Read(LoadState.Loaded, listOf(color, cut)), shown.last())
        assertFalse(shown.dropWhile { color !in it.data.orEmpty() }.any { color !in it.data.orEmpty() })
    }

    // --- another Stylist ---

    @Test
    fun `sign-out empties the Clients, the histories and the Service Menu, and what was on its way goes nowhere`() = runTest {
        val changes = Changes()
        val server = Server().apply {
            clients = listOf(anna)
            histories["anna"] = listOf(entry("a1"))
            menu = listOf(cut)
        }
        val clients = clientReads(server, changes)
        val menu = menuReads(server, changes)
        val watching = listOf(
            shownBy(clients.list.watch(stylist)),
            shownBy(clients.history.watch(annaKey)),
            shownBy(menu.menu.watch(stylist)),
        )
        runCurrent()

        // A refresh of the first Stylist's list is still on its way at sign-out.
        server.hold()
        val late = async { clients.list.refresh(stylist) }
        runCurrent()
        watching.forEach { (_, job) -> job.cancel() }
        runCurrent()
        changes.startOver()
        clients.clear()
        menu.clear()
        server.release()
        runCurrent()

        // Its answer is given to no one, and nothing of the first Stylist is cached.
        assertEquals(Read<List<ClientProfile>>(), late.await())
        assertEquals(Read<List<ClientProfile>>(), clients.list.now(stylist))
        assertEquals(Read<List<ClientHistoryEntry>>(), clients.history.now(annaKey))
        assertEquals(Read<List<ServiceMenuItem>>(), menu.menu.now(stylist))

        // The next Stylist's lists are of another key, and start as loading.
        val next = StylistKey("s2")
        server.clients = listOf(bella)
        server.menu = listOf(color)
        server.hold()
        val (list) = shownBy(clients.list.watch(next))
        val (items) = shownBy(menu.menu.watch(next))
        runCurrent()
        assertEquals(listOf(Read<List<ClientProfile>>(LoadState.Loading)), list)
        assertEquals(listOf(Read<List<ServiceMenuItem>>(LoadState.Loading)), items)

        server.release()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(bella)), list.last())
        assertEquals(Read(LoadState.Loaded, listOf(color)), items.last())
    }
}
