package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentCopying
import com.northphoenix.hairdresserclientmanager.state.AppointmentReads
import com.northphoenix.hairdresserclientmanager.state.Changes
import com.northphoenix.hairdresserclientmanager.state.CopyTargetKey
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Appointment Copying in the app: what a source of the copy sheet stands for, and whose list the
 * sheet shows. The list is read through the read cache (docs/adr/0016), by the Appointment the
 * copy goes into and that Appointment's Clients.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentCopyingTest {
    private fun service(id: String, clientId: String) = AppointmentService(id = id, clientId = clientId, name = id, priceCents = 1_000)

    /** A Completed Appointment as the copy sheet's list has it for one target. */
    private fun source(
        id: String,
        clients: List<Pair<String, String>>,
        services: List<AppointmentService> = emptyList(),
        copyableClientIds: List<String> = emptyList(),
    ) = Appointment(
        id = id,
        primaryClientId = clients.first().first,
        primaryClientName = clients.first().second,
        participants = clients.mapIndexed { index, (clientId, name) -> AppointmentParticipant(clientId = clientId, name = name, isPrimary = index == 0) },
        services = services,
        startsAt = "2026-10-10T15:19:00.000Z",
        status = AppointmentStatus.COMPLETED,
        locationType = LocationType.IN_SALON,
        copyableServiceCount = services.count { it.clientId in copyableClientIds },
        copyableClientIds = copyableClientIds,
    )

    // --- what a source stands for ---

    @Test
    fun `a group source shows only the Services of the Clients the copy is about, in the order they were added`() {
        // M7, L25: Bob's and Anna's Appointment as a source for Anna's own. Bob's Service is not copied, so it is not shown.
        val group = source(
            "group",
            clients = listOf("bob" to "Bob Baker", "anna" to "Anna Avery"),
            services = listOf(service("Anna Color", "anna"), service("Bob Fade", "bob"), service("Anna Gloss", "anna")),
            copyableClientIds = listOf("anna"),
        )

        assertEquals(listOf("Anna Color", "Anna Gloss"), AppointmentCopying.services(group).map { it.name })
        assertEquals(2, group.copyableServiceCount)
        // The card goes by whose Services are copied, not by the primary Client of the source.
        assertEquals(listOf("Anna Avery"), AppointmentCopying.clientNames(group))
    }

    @Test
    fun `a source for a group names every Client it copies for, in the source's order`() {
        val group = source(
            "group",
            clients = listOf("cara" to "Cara Cole", "bob" to "Bob Baker", "anna" to "Anna Avery"),
            services = listOf(service("Anna Color", "anna"), service("Cara Cut", "cara"), service("Bob Fade", "bob")),
            copyableClientIds = listOf("bob", "anna"),
        )

        assertEquals(listOf("Bob Baker", "Anna Avery"), AppointmentCopying.clientNames(group))
        assertEquals(listOf("Anna Color", "Bob Fade"), AppointmentCopying.services(group).map { it.name })
    }

    @Test
    fun `a source that names no Clients goes by the name of its Appointment`() {
        assertEquals(listOf("Anna Avery"), AppointmentCopying.clientNames(source("plain", clients = listOf("anna" to "Anna Avery"))))
    }

    // --- whose list the sheet shows ---

    private val annasTarget = CopyTargetKey("target-anna", setOf("anna"))
    private val bobsTarget = CopyTargetKey("target-bob", setOf("bob"))

    /** The reads with a server that answers the copy sources with [fetch]. */
    private fun TestScope.reads(changes: Changes = Changes(), fetch: suspend (CopyTargetKey) -> List<Appointment>) =
        AppointmentReads(backgroundScope, changes, fetchToday = { emptyList() }, fetchDay = { emptyList() }, find = { null }, fetchSources = fetch, fetchWeek = { emptyList() })

    /** What a copy sheet that reads [flow] shows, value by value. It starts reading at once, as a sheet does. */
    private fun <T> TestScope.shownBy(flow: Flow<T>): Pair<List<T>, Job> {
        val shown = mutableListOf<T>()

        return shown to backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { shown += it } }
    }

    private val loading = Read<List<Appointment>>(LoadState.Loading)

    @Test
    fun `nothing is loaded while no copy sheet is open`() = runTest {
        var asked = 0
        val reads = reads { asked++; emptyList() }

        // The Appointment lists are loaded again, as after any change: the copy sources go with them only for an open sheet.
        reads.sources.refreshShown()
        runCurrent()

        assertEquals(0, asked)
        assertTrue(reads.sources.shown.value.isEmpty())
    }

    @Test
    fun `the open sheet's Appointment is the one the list is asked for`() = runTest {
        val asked = mutableListOf<String>()
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val reads = reads { target -> asked += target.appointmentId; listOf(annas) }

        val (shown) = shownBy(reads.sources.watch(annasTarget))
        assertEquals(listOf(loading), shown)
        runCurrent()

        assertEquals(listOf("target-anna"), asked)
        assertEquals(Read(LoadState.Loaded, listOf(annas)), shown.last())

        // Loaded again with the Appointment lists while the sheet is open, as after a copy that was refused.
        reads.sources.refreshShown()
        assertEquals(listOf("target-anna", "target-anna"), asked)
    }

    @Test
    fun `a list loaded for one Appointment is never shown for another`() = runTest {
        // L25: what can be copied depends on the Clients of the Appointment it is copied into.
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val bobs = source("bobs", clients = listOf("bob" to "Bob Baker"))
        val answers = mapOf("target-anna" to CompletableDeferred<List<Appointment>>(), "target-bob" to CompletableDeferred())
        val reads = reads { target -> answers.getValue(target.appointmentId).await() }

        val (_, annasSheet) = shownBy(reads.sources.watch(annasTarget))
        runCurrent()

        // Anna's sheet closes and Bob's opens before the answer for Anna's has come.
        annasSheet.cancel()
        val (shown) = shownBy(reads.sources.watch(bobsTarget))
        runCurrent()
        assertEquals(listOf(loading), shown)

        answers.getValue("target-bob").complete(listOf(bobs))
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(bobs)), shown.last())

        // The late answer for Anna's Appointment changes nothing.
        answers.getValue("target-anna").complete(listOf(annas))
        runCurrent()
        assertEquals(listOf(loading, Read(LoadState.Loaded, listOf(bobs))), shown)
    }

    @Test
    fun `the list starts over when the Clients of the Appointment change under the open sheet`() = runTest {
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        var answer = listOf(annas)
        val reads = reads { answer }

        val (_, sheet) = shownBy(reads.sources.watch(CopyTargetKey("target", setOf("anna", "bob"))))
        runCurrent()
        assertEquals(listOf(annas), reads.sources.shown.value.values.single().read.data)

        // Anna was removed from the Appointment on another device: her Appointments are no sources for it any more.
        // With other Clients the sheet reads another key, which has nothing of the list from before.
        answer = emptyList()
        sheet.cancel()
        val (shown) = shownBy(reads.sources.watch(CopyTargetKey("target", setOf("bob"))))
        assertEquals(listOf(loading), shown)

        runCurrent()
        assertEquals(listOf(loading, Read(LoadState.Loaded, emptyList())), shown)
    }

    @Test
    fun `a sheet that is opened again shows its list at once, and closing one sheet does not close another`() = runTest {
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val older = source("older", clients = listOf("anna" to "Anna Avery"))
        var answer = listOf(annas)
        val reads = reads { answer }

        val (_, first) = shownBy(reads.sources.watch(annasTarget))
        runCurrent()
        first.cancel()
        runCurrent()

        // A closed sheet shows nothing and is asked for nothing.
        assertTrue(reads.sources.shown.value.isEmpty())

        // Opened again: the list from before from the first frame, and what the server has by now behind it.
        answer = listOf(annas, older)
        assertEquals(Read(LoadState.Loaded, listOf(annas)), reads.sources.now(annasTarget))
        val (again, annasSheet) = shownBy(reads.sources.watch(annasTarget))
        assertEquals(listOf(Read(LoadState.Loaded, listOf(annas))), again)
        val (bobsSheet) = shownBy(reads.sources.watch(bobsTarget))
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(annas, older)), again.last())

        annasSheet.cancel()
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(annas, older)), bobsSheet.last())
        assertEquals(bobsTarget, reads.sources.shown.value.values.single().key)
    }

    @Test
    fun `after any change a list is loaded anew, not copied from as it was`() = runTest {
        // An Appointment that is no source yet becomes one with its first Service, or when it is Completed.
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val newest = source("newest", clients = listOf("anna" to "Anna Avery"))
        var answer = listOf(annas)
        val changes = Changes()
        val reads = reads(changes) { answer }
        val (_, first) = shownBy(reads.sources.watch(annasTarget))
        runCurrent()
        first.cancel()
        runCurrent()

        answer = listOf(newest, annas)
        reads.statusChanged("newest")

        // The first of the list is what a copy takes when it names no source: the list from before is not offered.
        assertEquals(loading, reads.sources.now(annasTarget))
        val (shown) = shownBy(reads.sources.watch(annasTarget))
        assertEquals(listOf(loading), shown)
        runCurrent()
        assertEquals(listOf(loading, Read(LoadState.Loaded, listOf(newest, annas))), shown)
    }

    @Test
    fun `a list that cannot be loaded says why, and one that is on screen stays when loading it again fails`() = runTest {
        var failing = true
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val reads = reads { if (failing) throw ApiException("offline", isNetwork = true) else listOf(annas) }
        val (shown) = shownBy(reads.sources.watch(annasTarget))
        runCurrent()
        assertEquals(listOf(loading, Read(LoadState.Failed(R.string.network_error))), shown)

        // The load the sheet asks for when it opens throws the failure, for the caller to report.
        assertTrue(failureOf { reads.sources.refresh(annasTarget) }.isNetwork)

        failing = false
        reads.sources.refresh(annasTarget)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(annas)), shown.last())

        failing = true
        assertTrue(failureOf { reads.sources.refreshShown() }.isNetwork)
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(annas)), shown.last())
    }

    @Test
    fun `a deleted Appointment is no source any more`() = runTest {
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val older = source("older", clients = listOf("anna" to "Anna Avery"))
        val reads = reads { listOf(annas, older) }
        val (shown) = shownBy(reads.sources.watch(annasTarget))
        runCurrent()
        assertTrue(reads.isListed("annas"))

        reads.forget("annas")

        assertEquals(listOf(older), reads.sources.shown(annasTarget)?.data)
        assertTrue(!reads.isListed("annas"))
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf(older)), shown.last())
    }

    @Test
    fun `a source is never taken for the Appointment itself`() = runTest {
        // A source is stated for the Appointment it is copied into: only the Services that can be copied count in it.
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val reads = reads { listOf(annas) }
        shownBy(reads.sources.watch(annasTarget))
        runCurrent()

        assertEquals(null, reads["annas"])
        assertTrue(reads.all().isEmpty())
    }

    private suspend fun failureOf(read: suspend () -> Unit): ApiException =
        try {
            read()
            throw AssertionError("expected the failure to be thrown for the caller to report")
        } catch (error: ApiException) {
            error
        }
}
