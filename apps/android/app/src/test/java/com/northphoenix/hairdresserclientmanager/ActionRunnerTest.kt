package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.state.ActionRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionRunnerTest {
    private val failures = mutableListOf<ApiException>()
    private var staleNotices = 0

    private fun TestScope.runner() =
        ActionRunner(backgroundScope, { failures += it }, reloadRetryDelaysMillis = listOf(1_000, 5_000), onStale = { staleNotices++ })

    private fun serverError() = ApiException("Internal server error.", code = "INTERNAL_SERVER_ERROR")

    @Test
    fun `a change that was saved stays saved when the reload after it fails`() = runTest {
        val runner = runner()
        val saved = mutableListOf<String>()
        var reloads = 0

        runner.run(
            key = "saveClient:new",
            change = { "client-1" },
            reload = { if (++reloads == 1) throw serverError() },
            onSaved = { saved += it },
        )
        runCurrent()

        assertEquals(listOf("client-1"), saved)
        assertTrue(failures.isEmpty())
        assertFalse(runner.busy.value)
        assertEquals(1, reloads)

        // The reload is tried again in the background until it works, and then no more.
        advanceTimeBy(1_001)
        assertEquals(2, reloads)
        advanceTimeBy(60_000)
        runner.reloadOwed()
        assertEquals(2, reloads)
        assertTrue(failures.isEmpty())
        // A reload that worked on its first retry was a blip: nothing to tell the Stylist.
        assertEquals(0, staleNotices)
    }

    @Test
    fun `a reload that keeps failing after a saved change is told once and stays owed until it works`() = runTest {
        val runner = runner()
        var reloads = 0
        var online = false

        runner.run(key = "addService:a1", change = { }, reload = { reloads++; if (!online) throw serverError() })
        runCurrent()
        assertEquals(1, reloads)
        assertEquals(0, staleNotices)

        // The first retry has failed too: the screen does not show the Service that was added.
        advanceTimeBy(1_001)
        assertEquals(2, reloads)
        assertEquals(1, staleNotices)

        // The first try and one per retry delay. It is never a failure of the change, and it is told once.
        advanceTimeBy(60_000)
        assertEquals(3, reloads)
        assertTrue(failures.isEmpty())
        assertEquals(1, staleNotices)

        // Still owed: it is run again whenever there is reason to think it will work, until it has.
        runner.reloadOwed()
        assertEquals(4, reloads)
        online = true
        runner.reloadOwed()
        assertEquals(5, reloads)
        runner.reloadOwed()
        assertEquals(5, reloads)
    }

    @Test
    fun `a reload that works runs the ones that are still owed`() = runTest {
        val runner = runner()
        val reloaded = mutableListOf<String>()
        var online = false

        runner.run(key = "addService:a1", change = { }, reload = { if (!online) throw serverError(); reloaded += "the Appointment" })
        advanceTimeBy(60_000)
        assertTrue(reloaded.isEmpty())

        // The connection is back and the Stylist changes something else.
        online = true
        runner.run(key = "saveServiceMenuItem:new", change = { }, reload = { reloaded += "the Service Menu" })
        runCurrent()

        assertEquals(listOf("the Service Menu", "the Appointment"), reloaded)
        assertFalse(runner.busy.value)
    }

    @Test
    fun `an owed reload that cannot work costs one try and does not hold up the others`() = runTest {
        val runner = runner()
        val tried = mutableListOf<String>()
        var online = false

        // The server keeps failing to list Appointments. The Service Menu only failed while the connection was down.
        runner.run(key = "addService:a1", change = { }, reload = { tried += "a1"; throw serverError() })
        runner.run(key = "saveServiceMenuItem:new", change = { }, reload = { tried += "the Service Menu"; if (!online) throw serverError() })
        runner.run(key = "addService:a2", change = { }, reload = { tried += "a2"; throw serverError() })
        advanceTimeBy(60_000)
        tried.clear()
        online = true

        runner.reloadOwed()
        assertEquals(listOf("a1"), tried)

        // The one that failed went to the back, so the next occasion reaches the Service Menu.
        runner.reloadOwed()
        assertEquals(listOf("a1", "the Service Menu", "a2"), tried)

        runner.reloadOwed()
        assertEquals(listOf("a1", "the Service Menu", "a2", "a1"), tried)
    }

    @Test
    fun `a reload that keeps failing after an unknown outcome is owed, and nothing is called saved`() = runTest {
        val runner = runner()
        var reloads = 0
        var online = false

        runner.run<Unit>(
            key = "saveClient:new",
            change = { throw ApiException("No reply from the server.", code = ApiException.NO_REPLY) },
            reload = { reloads++; if (!online) throw serverError() },
        )
        advanceTimeBy(60_000)

        assertEquals(3, reloads)
        assertTrue(failures.single().outcomeUnknown)
        assertEquals(0, staleNotices)

        online = true
        runner.reloadOwed()
        assertEquals(4, reloads)
    }

    @Test
    fun `a reload that follows no change is dropped when its retries fail too`() = runTest {
        val runner = runner()
        var reloads = 0

        // The app came back to the foreground without a connection.
        runner.reloadWithRetry { reloads++; throw serverError() }
        advanceTimeBy(60_000)
        assertEquals(3, reloads)

        runner.reloadOwed()
        assertEquals(3, reloads)
        assertEquals(0, staleNotices)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `what is owed to a Stylist who has signed out is neither retried nor run later`() = runTest {
        val runner = runner()
        var reloads = 0

        runner.run(key = "addService:a1", change = { }, reload = { reloads++; throw serverError() })
        runCurrent()
        assertEquals(1, reloads)

        runner.forgetOwed()
        advanceTimeBy(60_000)
        runner.reloadOwed()

        assertEquals(1, reloads)
        assertEquals(0, staleNotices)

        // The same for a reload that was still running when they signed out.
        val reply = CompletableDeferred<Unit>()
        runner.run(key = "addService:a2", change = { }, reload = { reloads++; reply.await() })
        runner.forgetOwed()
        reply.completeExceptionally(serverError())
        advanceTimeBy(60_000)
        runner.reloadOwed()

        assertEquals(2, reloads)
        assertEquals(0, staleNotices)
        assertFalse(runner.busy.value)

        // And for a retry that was on its way: its failure is not told to whoever signs in next.
        val retry = CompletableDeferred<Unit>()
        var tries = 0
        runner.run(key = "addService:a3", change = { }, reload = { if (++tries == 1) throw serverError() else retry.await() })
        advanceTimeBy(1_001)
        assertEquals(2, tries)
        runner.forgetOwed()
        retry.completeExceptionally(serverError())
        advanceTimeBy(60_000)

        assertEquals(2, tries)
        assertEquals(0, staleNotices)
    }

    @Test
    fun `a refused change is reported, not saved and not reloaded`() = runTest {
        val runner = runner()
        var saved = false
        var reloads = 0

        runner.run<Unit>(
            key = "removeParticipant:a1:c1",
            change = { throw ApiException("Client has Services attached to this Appointment.", code = "BAD_REQUEST") },
            reload = { reloads++ },
            onSaved = { saved = true },
        )
        runCurrent()

        assertEquals(listOf("BAD_REQUEST"), failures.map { it.code })
        assertFalse(saved)
        assertEquals(0, reloads)
        assertFalse(runner.busy.value)
    }

    @Test
    fun `a change with no reply is reported as unknown and reloads to show what happened`() = runTest {
        val runner = runner()
        var saved = false
        var reloads = 0

        runner.run<Unit>(
            key = "saveClient:new",
            change = { throw ApiException("No reply from the server.", code = ApiException.NO_REPLY) },
            reload = { reloads++ },
            onSaved = { saved = true },
        )
        runCurrent()

        assertTrue(failures.single().outcomeUnknown)
        assertFalse(saved)
        assertEquals(1, reloads)
    }

    @Test
    fun `a change the server refuses because its target is gone reloads everything`() = runTest {
        val reloads = mutableListOf<String>()
        val runner = ActionRunner(backgroundScope, { failures += it }, reloadAll = { reloads += "everything" })
        var saved = false

        // The Appointment was deleted in another session, and the Stylist taps 'Completed' on it.
        runner.run<Unit>(
            key = "setAppointmentStatus:a1",
            change = { throw ApiException("Appointment not found.", code = "NOT_FOUND") },
            reload = { reloads += "the Appointment" },
            onSaved = { saved = true },
        )
        runCurrent()

        // The lists drop the card, and the Appointment's own reload finds out that it is gone.
        assertEquals(listOf("everything", "the Appointment"), reloads)
        assertEquals(listOf("NOT_FOUND"), failures.map { it.code })
        assertFalse(saved)
        assertFalse(runner.busy.value)
    }

    @Test
    fun `a second call with the same key does nothing until the first has finished`() = runTest {
        val runner = runner()
        val reply = CompletableDeferred<Unit>()
        val reloaded = CompletableDeferred<Unit>()
        var requests = 0

        repeat(2) {
            runner.run(key = "deleteService:s1", change = { requests++; reply.await() }, reload = { reloaded.await() })
        }
        assertEquals(1, requests)
        assertTrue(runner.busy.value)

        // Still the same action while its reload is running.
        reply.complete(Unit)
        runCurrent()
        runner.run(key = "deleteService:s1", change = { requests++ })
        assertEquals(1, requests)

        reloaded.complete(Unit)
        runCurrent()
        assertFalse(runner.busy.value)

        runner.run(key = "deleteService:s1", change = { requests++ })
        assertEquals(2, requests)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `other keys and calls without a key are not held back`() = runTest {
        val runner = runner()
        val reply = CompletableDeferred<Unit>()
        var requests = 0

        runner.run(key = "deleteService:s1", change = { requests++; reply.await() })
        runner.run(key = "deleteService:s2", change = { requests++; reply.await() })
        repeat(2) { runner.run(key = null, change = { requests++; reply.await() }) }

        assertEquals(4, requests)
        assertTrue(runner.busy.value)

        reply.complete(Unit)
        runCurrent()
        assertFalse(runner.busy.value)
    }

    @Test
    fun `an unexpected exception is reported instead of thrown`() = runTest {
        val runner = runner()

        runner.run<Unit>(key = "saveNote:a1", change = { throw IllegalArgumentException("Element class JsonLiteral is not a JsonObject") })
        runCurrent()

        assertEquals(listOf("UNEXPECTED"), failures.map { it.code })
        assertFalse(runner.busy.value)
    }

    @Test
    fun `a success callback that throws is reported and the reload still runs`() = runTest {
        val runner = runner()
        var reloads = 0

        runner.run(key = "createAppointment", change = { }, reload = { reloads++ }, onSaved = { error("navigation failed") })
        runCurrent()

        assertEquals(listOf("UNEXPECTED"), failures.map { it.code })
        assertEquals(1, reloads)
        assertFalse(runner.busy.value)
    }

    @Test
    fun `busy clears when a running change is cancelled`() = runTest {
        val runner = runner()

        runner.run(key = "createAppointment", change = { CompletableDeferred<Unit>().await() })
        assertTrue(runner.busy.value)

        backgroundScope.cancel()
        runCurrent()

        assertFalse(runner.busy.value)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `reloads run one at a time, in the order they were asked for`() = runTest {
        val runner = runner()
        val firstReload = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        runner.run(key = "a", change = { }, reload = { order += "a starts"; firstReload.await(); order += "a ends" })
        runner.run(key = "b", change = { }, reload = { order += "b starts" })
        runCurrent()
        assertEquals(listOf("a starts"), order)

        firstReload.complete(Unit)
        runCurrent()
        assertEquals(listOf("a starts", "a ends", "b starts"), order)
    }
}
