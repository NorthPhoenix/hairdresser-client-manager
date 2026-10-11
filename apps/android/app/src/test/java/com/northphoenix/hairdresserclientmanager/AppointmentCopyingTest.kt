package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentCopying
import com.northphoenix.hairdresserclientmanager.state.CopySources
import com.northphoenix.hairdresserclientmanager.state.LoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Appointment Copying in the app: what a source of the copy sheet stands for, and whose list the sheet shows. */
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

    @Test
    fun `nothing is loaded while no copy sheet is open`() = runTest {
        var asked = 0
        val sources = CopySources { asked++; emptyList() }

        sources.load()

        assertEquals(0, asked)
        assertEquals(LoadState.Loading, sources.state.value)
    }

    @Test
    fun `the open sheet's Appointment is the one the list is asked for`() = runTest {
        val asked = mutableListOf<String>()
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val sources = CopySources { target -> asked += target; listOf(annas) }

        sources.open("target-anna")
        assertEquals(LoadState.Loading, sources.state.value)
        sources.load()

        assertEquals(listOf("target-anna"), asked)
        assertEquals(listOf(annas), sources.sources.value)
        assertEquals(LoadState.Loaded, sources.state.value)

        // Loaded again with the Appointment lists while the sheet is open, as after a copy that was refused.
        sources.load()
        assertEquals(listOf("target-anna", "target-anna"), asked)
    }

    @Test
    fun `a list loaded for one Appointment is never shown for another`() = runTest {
        // L25: what can be copied depends on the Clients of the Appointment it is copied into.
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val bobs = source("bobs", clients = listOf("bob" to "Bob Baker"))
        val answers = mapOf("target-anna" to CompletableDeferred<List<Appointment>>(), "target-bob" to CompletableDeferred())
        val sources = CopySources { target -> answers.getValue(target).await() }

        sources.open("target-anna")
        launch { sources.load() }
        runCurrent()

        // Anna's sheet closes and Bob's opens before the answer for Anna's has come.
        sources.close("target-anna")
        sources.open("target-bob")
        launch { sources.load() }
        runCurrent()
        assertEquals(LoadState.Loading, sources.state.value)

        answers.getValue("target-bob").complete(listOf(bobs))
        runCurrent()
        assertEquals(listOf(bobs), sources.sources.value)

        // The late answer for Anna's Appointment changes nothing.
        answers.getValue("target-anna").complete(listOf(annas))
        runCurrent()
        assertEquals(listOf(bobs), sources.sources.value)
        assertEquals(LoadState.Loaded, sources.state.value)
    }

    @Test
    fun `the list starts over when the Clients of the Appointment change under the open sheet`() = runTest {
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        var answer = listOf(annas)
        val sources = CopySources { answer }

        sources.open("target")
        sources.load()
        assertEquals(listOf(annas), sources.sources.value)

        // Anna was removed from the Appointment on another device: her Appointments are no sources for it any more.
        answer = emptyList()
        sources.open("target")
        assertTrue(sources.sources.value.isEmpty())
        assertEquals(LoadState.Loading, sources.state.value)

        sources.load()
        assertTrue(sources.sources.value.isEmpty())
        assertEquals(LoadState.Loaded, sources.state.value)
    }

    @Test
    fun `a closed sheet keeps no list, and closing an older sheet does not close the one that is open`() = runTest {
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val sources = CopySources { listOf(annas) }

        sources.open("target-anna")
        sources.load()
        sources.close("target-anna")

        assertTrue(sources.sources.value.isEmpty())
        assertEquals(LoadState.Loading, sources.state.value)

        sources.open("target-bob")
        sources.load()
        sources.close("target-anna")

        assertEquals(listOf(annas), sources.sources.value)
        assertEquals(LoadState.Loaded, sources.state.value)
    }

    @Test
    fun `a list that cannot be loaded says why, and one that is on screen stays when loading it again fails`() = runTest {
        var failing = true
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val sources = CopySources { if (failing) throw ApiException("offline", isNetwork = true) else listOf(annas) }

        sources.open("target")
        try {
            sources.load()
            fail("expected the failure to be thrown for the caller to report")
        } catch (_: ApiException) {
        }
        assertEquals(LoadState.Failed(R.string.network_error), sources.state.value)

        failing = false
        sources.load()
        assertEquals(LoadState.Loaded, sources.state.value)

        failing = true
        try {
            sources.load()
        } catch (_: ApiException) {
        }
        assertEquals(listOf(annas), sources.sources.value)
        assertEquals(LoadState.Loaded, sources.state.value)
    }

    @Test
    fun `a deleted Appointment is no source any more`() = runTest {
        val annas = source("annas", clients = listOf("anna" to "Anna Avery"))
        val older = source("older", clients = listOf("anna" to "Anna Avery"))
        val sources = CopySources { listOf(annas, older) }

        sources.open("target")
        sources.load()
        sources.forget("annas")

        assertEquals(listOf(older), sources.sources.value)
    }
}
