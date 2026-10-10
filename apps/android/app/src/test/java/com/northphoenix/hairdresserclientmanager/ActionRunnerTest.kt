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

    private fun TestScope.runner() = ActionRunner(backgroundScope, { failures += it }, reloadRetryDelaysMillis = listOf(1_000, 5_000))

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
        assertEquals(2, reloads)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a reload that keeps failing is given up quietly`() = runTest {
        val runner = runner()
        var reloads = 0

        runner.run(key = "addService:a1", change = { }, reload = { reloads++; throw serverError() })
        advanceTimeBy(60_000)

        // The first try and one per retry delay.
        assertEquals(3, reloads)
        assertTrue(failures.isEmpty())
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
