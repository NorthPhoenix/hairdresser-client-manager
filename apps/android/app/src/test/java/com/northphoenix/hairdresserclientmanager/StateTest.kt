package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.data.UpdatedAppointment
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.state.LatestWins
import com.northphoenix.hairdresserclientmanager.state.LoadedAppointments
import com.northphoenix.hairdresserclientmanager.state.SessionWatch
import com.northphoenix.hairdresserclientmanager.state.withClient
import com.northphoenix.hairdresserclientmanager.state.withSaved
import com.northphoenix.hairdresserclientmanager.state.withTimeAndPlace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StateTest {
    // --- status taps ---

    /** Stands in for the server: records each request and answers it only when the test says so. */
    private class Requests {
        val sent = mutableListOf<String>()
        private val replies = ArrayDeque<CompletableDeferred<Unit>>()

        suspend fun send(value: String) {
            sent += value
            CompletableDeferred<Unit>().also(replies::addLast).await()
        }

        fun answer() = replies.removeFirst().complete(Unit)

        fun refuse() = replies.removeFirst().completeExceptionally(ApiException("Appointment not found.", code = "NOT_FOUND"))
    }

    @Test
    fun `the last status tapped while a request is in flight is the one saved last`() = runTest {
        val changes = LatestWins<String, String>()
        val requests = Requests()

        assertTrue(changes.offer("a1", "completed"))
        val sending = launch { changes.send("a1", requests::send) }
        runCurrent()

        // Tapped while "completed" is on its way: only the last of them is sent.
        assertFalse(changes.offer("a1", "canceled"))
        assertFalse(changes.offer("a1", "noShow"))
        assertEquals("noShow", changes.pending("a1"))

        requests.answer()
        runCurrent()
        assertEquals(listOf("completed", "noShow"), requests.sent)

        requests.answer()
        runCurrent()
        assertTrue(sending.isCompleted)
        assertNull(changes.pending("a1"))
    }

    @Test
    fun `tapping back to the status already on its way sends nothing more`() = runTest {
        val changes = LatestWins<String, String>()
        val requests = Requests()

        changes.offer("a1", "canceled")
        val sending = launch { changes.send("a1", requests::send) }
        runCurrent()
        changes.offer("a1", "noShow")
        changes.offer("a1", "canceled")

        requests.answer()
        runCurrent()

        assertEquals(listOf("canceled"), requests.sent)
        assertTrue(sending.isCompleted)
    }

    @Test
    fun `status taps on different Appointments do not wait for each other`() = runTest {
        val changes = LatestWins<String, String>()
        val requests = Requests()

        assertTrue(changes.offer("a1", "completed"))
        assertTrue(changes.offer("a2", "canceled"))
        launch { changes.send("a1", requests::send) }
        launch { changes.send("a2", requests::send) }
        runCurrent()

        assertEquals(listOf("completed", "canceled"), requests.sent)
        requests.answer()
        requests.answer()
    }

    @Test
    fun `a refused status request forgets the tap that was waiting behind it`() = runTest {
        val changes = LatestWins<String, String>()
        val requests = Requests()
        var failure: ApiException? = null

        changes.offer("a1", "completed")
        launch {
            try {
                changes.send("a1", requests::send)
                fail("expected the failure to reach the caller")
            } catch (error: ApiException) {
                failure = error
            }
        }
        runCurrent()
        changes.offer("a1", "noShow")

        requests.refuse()
        runCurrent()

        assertEquals("NOT_FOUND", failure?.code)
        assertEquals(listOf("completed"), requests.sent)
        assertNull(changes.pending("a1"))
        // The next tap starts from scratch.
        assertTrue(changes.offer("a1", "noShow"))
    }

    @Test
    fun `tapping the value that is already saved sends nothing`() {
        val changes = LatestWins<String, String>()

        assertFalse(changes.offer("p1", "after", saved = "after"))
        assertNull(changes.pending("p1"))
        assertTrue(changes.offer("p1", "before", saved = "after"))
    }

    @Test
    fun `a tap back to the value on screen while another is being saved is the one that stays`() = runTest {
        val changes = LatestWins<String, String>()
        val requests = Requests()

        // A photo shown as After. Before is tapped and, while that is on its way, After again.
        assertTrue(changes.offer("p1", "before", saved = "after"))
        val sending = launch { changes.send("p1", requests::send) }
        runCurrent()
        assertFalse(changes.offer("p1", "after", saved = "after"))

        requests.answer()
        runCurrent()
        assertEquals(listOf("before", "after"), requests.sent)

        requests.answer()
        runCurrent()
        assertTrue(sending.isCompleted)
    }

    @Test
    fun `a value tapped again after another one in between is not lost`() = runTest {
        val changes = LatestWins<String, String>()
        val requests = Requests()

        // Before, Other, Before within one request: a key per value used to drop the third tap and save Other.
        assertTrue(changes.offer("p1", "before", saved = "after"))
        val sending = launch { changes.send("p1", requests::send) }
        runCurrent()
        assertFalse(changes.offer("p1", "other", saved = "after"))
        assertFalse(changes.offer("p1", "before", saved = "after"))

        requests.answer()
        runCurrent()
        assertEquals(listOf("before"), requests.sent)
        assertTrue(sending.isCompleted)

        // Before is saved and on screen now, so the next tap starts from it.
        assertFalse(changes.offer("p1", "before", saved = "before"))
        assertTrue(changes.offer("p1", "other", saved = "before"))
    }

    @Test
    fun `a value cleared while it is being saved is cleared after it`() = runTest {
        val changes = LatestWins<String, Int?>()
        val requests = Requests()

        // No final total override yet: 50.00 is saved and, before the reply, cleared again.
        assertTrue(changes.offer("a1", 5000, saved = null))
        val sending = launch { changes.send("a1") { requests.send(it?.toString() ?: "cleared") } }
        runCurrent()
        assertFalse(changes.offer("a1", null, saved = null))

        requests.answer()
        runCurrent()
        assertEquals(listOf("5000", "cleared"), requests.sent)

        requests.answer()
        runCurrent()
        assertTrue(sending.isCompleted)
    }

    // --- why the sign-in screen appeared ---

    private val signedIn = AuthState.SignedIn(userId = "user_1", email = "stylist@example.com")

    @Test
    fun `a session that ends on its own is remembered until someone signs in`() {
        val watch = SessionWatch()

        watch.onState(AuthState.Loading)
        watch.onState(signedIn)
        assertFalse(watch.ended.value)

        watch.onState(AuthState.SignedOut)
        assertTrue(watch.ended.value)

        // Restarting the provider does not explain anything away.
        watch.onState(AuthState.Loading)
        watch.onState(AuthState.SignedOut)
        assertTrue(watch.ended.value)

        watch.onState(signedIn)
        assertFalse(watch.ended.value)
    }

    @Test
    fun `signing out on purpose is not an ended session`() {
        val watch = SessionWatch()

        watch.onState(signedIn)
        watch.signOutRequested()
        watch.onState(AuthState.SignedOut)
        assertFalse(watch.ended.value)

        // The request covered that one sign-out only.
        watch.onState(signedIn)
        watch.onState(AuthState.SignedOut)
        assertTrue(watch.ended.value)
    }

    @Test
    fun `starting signed out is not an ended session`() {
        val watch = SessionWatch()

        watch.onState(AuthState.Loading)
        watch.onState(AuthState.SignedOut)

        assertFalse(watch.ended.value)
    }

    // --- a saved Client in the loaded list ---

    private fun client(id: String, name: String, share: ProfileShare? = null) =
        ClientProfile(id = id, name = name, language = Language.EN, activeProfileShare = share)

    @Test
    fun `a new Client is in the list straight after the save`() {
        val clients = listOf(client("c1", "Anna")).withSaved(client("c2", "Mode Olga"))

        assertEquals(listOf("c2", "c1"), clients.map { it.id })
    }

    @Test
    fun `a saved Client replaces its old copy and keeps its Profile Share`() {
        val share = ProfileShare(id = "s1", token = "token", language = Language.EN, createdAt = "2026-01-01T00:00:00.000Z")
        // The reply to a save carries no Profile Share.
        val clients = listOf(client("c1", "Anna"), client("c2", "Olga", share)).withSaved(client("c2", "Olga Petrova"))

        assertEquals(listOf("c2", "c1"), clients.map { it.id })
        assertEquals("Olga Petrova", clients.first().name)
        assertEquals(share, clients.first().activeProfileShare)
    }

    // --- a saved Client on the Appointments that are already loaded ---

    private fun participant(clientId: String, name: String, phone: String, isPrimary: Boolean = false) =
        AppointmentParticipant(clientId = clientId, name = name, phone = phone, language = Language.RU, isPrimary = isPrimary, subtotalCents = 4500)

    private fun appointment(id: String, primary: AppointmentParticipant, vararg others: AppointmentParticipant) =
        Appointment(
            id = id,
            primaryClientId = primary.clientId,
            primaryClientName = primary.name,
            participants = listOf(primary) + others,
            startsAt = "2026-10-14T15:00:00.000Z",
            status = AppointmentStatus.SCHEDULED,
            locationType = LocationType.IN_SALON,
        )

    @Test
    fun `an edited Appointment has its new time and place before it is loaded again, and everything else it had`() {
        val alice = participant("c1", "Alice Home", "5550001", isPrimary = true)
        val loaded = appointment("a1", alice).copy(locationAddress = "12 Salon Street", mapUrl = "https://maps.example/salon", note = "Bring swatches")
        val saved = UpdatedAppointment(
            startsAt = "2026-10-21T20:00:00.000Z",
            endsAt = "2026-10-21T21:00:00.000Z",
            locationType = LocationType.AT_HOME,
            locationAddress = "9 Lamar Blvd, Austin",
            customLocationAddress = "9 Lamar Blvd, Austin",
            mapUrl = "https://maps.example/lamar",
        )

        val shown = loaded.withTimeAndPlace(saved)

        // The reload looks the Appointment up at its start time: it has to be the new one.
        assertEquals("2026-10-21T20:00:00.000Z", shown.startsAt)
        assertEquals("2026-10-21T21:00:00.000Z", shown.endsAt)
        assertEquals(LocationType.AT_HOME, shown.locationType)
        assertEquals("9 Lamar Blvd, Austin", shown.locationAddress)
        assertEquals("9 Lamar Blvd, Austin", shown.customLocationAddress)
        assertEquals("https://maps.example/lamar", shown.mapUrl)
        // The reply has no photos and is not the Appointment: the rest stays as loaded.
        assertEquals(loaded.participants, shown.participants)
        assertEquals("Bring swatches", shown.note)

        // The end cleared, back in the salon without an address: nothing of the old place is left.
        val cleared = shown.withTimeAndPlace(UpdatedAppointment(startsAt = "2026-10-21T20:00:00.000Z", locationType = LocationType.IN_SALON))
        assertNull(cleared.endsAt)
        assertEquals("", cleared.locationAddress)
        assertEquals("", cleared.customLocationAddress)
        assertNull(cleared.mapUrl)
    }

    @Test
    fun `a saved Client's new phone is on every loaded Appointment they are in`() {
        val appointments = LoadedAppointments()
        val phoneTest = participant("c1", "Phone Test", "5550001", isPrimary = true)
        val other = participant("c2", "Carol Other", "5550100")

        // One opened from the Client's history, one as an additional Client, one without them.
        appointments.remember(
            listOf(
                appointment("next-week", phoneTest),
                appointment("group", other.copy(isPrimary = true), phoneTest.copy(isPrimary = false)),
                appointment("unrelated", other.copy(isPrimary = true)),
            ),
        )
        val unrelated = appointments["unrelated"]

        val saved = ClientProfile(id = "c1", name = "Phone Test", language = Language.EN, phone = "5550002", address = "1 New St")
        appointments.updateAll { it.withClient(saved) }

        // The SMS reminder goes to the primary Client's phone, in their language.
        val recipient = appointments["next-week"]!!.participants.single { it.isPrimary }
        assertEquals("5550002", recipient.phone)
        assertEquals(Language.EN, recipient.language)
        assertEquals("1 New St", recipient.address)
        assertEquals("5550002", ClientReminder.recipient(recipient.phone))

        val inGroup = appointments["group"]!!.participants
        assertEquals(listOf("5550100", "5550002"), inGroup.map { it.phone })
        assertEquals("Carol Other", appointments["group"]!!.primaryClientName)
        // What the Appointment itself holds about the Client stays.
        assertEquals(listOf(4500L, 4500L), inGroup.map { it.subtotalCents })
        assertFalse(inGroup.last().isPrimary)

        assertTrue(unrelated === appointments["unrelated"])
    }

    @Test
    fun `a renamed primary Client is renamed on their Appointments`() {
        val before = appointment("a1", participant("c1", "Olga", "5550001", isPrimary = true), participant("c2", "Anna", "5550100"))

        val after = before.withClient(ClientProfile(id = "c1", name = "Olga Petrova", language = Language.RU, phone = "5550001"))

        assertEquals("Olga Petrova", after.primaryClientName)
        assertEquals(listOf("Olga Petrova", "Anna"), after.participants.map { it.name })
        // Another Client's save changes nothing here.
        assertEquals(before, before.withClient(ClientProfile(id = "c9", name = "Someone Else", language = Language.RU)))
    }
}
