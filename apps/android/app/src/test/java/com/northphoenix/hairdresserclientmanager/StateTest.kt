package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.state.LatestWins
import com.northphoenix.hairdresserclientmanager.state.SessionWatch
import com.northphoenix.hairdresserclientmanager.state.withSaved
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
}
