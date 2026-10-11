package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.state.ActionRunner
import com.northphoenix.hairdresserclientmanager.state.Ahead
import com.northphoenix.hairdresserclientmanager.state.LatestWins
import com.northphoenix.hairdresserclientmanager.state.clientsChangeKey
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
            key = "editAppointment:a1",
            change = { throw ApiException("End time must be after start time.", code = "BAD_REQUEST") },
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
    fun `a change refused because of the Clients the Appointment has now is reported and the Appointment loaded again`() = runTest {
        val reloads = mutableListOf<String>()
        val runner = ActionRunner(backgroundScope, { failures += it }, reloadAll = { reloads += "everything" })
        var saved = false
        // L29, H1, A1: what the screen offered is not what the server has. Another device removed the Client, or gave them a Service or a photo.
        val refusals = listOf(
            "Client must be attached to the Appointment.",
            "Primary Client must be attached to the Appointment.",
            "Service Client must be attached to the Appointment.",
            "Client has Services attached to this Appointment.",
            "Client has Appointment Photos attached to this Appointment.",
            "Appointment must keep at least one Client.",
        )

        refusals.forEach { message ->
            runner.run<Unit>(
                key = clientsChangeKey("a1"),
                change = { throw ApiException(message, code = "BAD_REQUEST") },
                reload = { reloads += message },
                onSaved = { saved = true },
            )
            runCurrent()
        }

        // Each is told to the Stylist, and the screen then shows the Clients the server has. Nothing else is reloaded.
        assertEquals(refusals, failures.map { it.message })
        assertEquals(refusals, reloads)
        assertFalse(saved)
        assertFalse(runner.busy.value)
    }

    @Test
    fun `a save from a form that is older than its record, and a creation that was already made, load what the server has`() = runTest {
        val reloads = mutableListOf<String>()
        val runner = ActionRunner(backgroundScope, { failures += it }, reloadAll = { reloads += "everything" })
        var saved = false
        val refusals = listOf(
            // A15, L67: the Client or the settings were saved on another device since the form was filled.
            "This record was changed since it was loaded.",
            // A4: the first try was saved without its reply arriving, and the form was changed before the second.
            "This request was already carried out with other values.",
        )

        refusals.forEach { message ->
            runner.run<Unit>(
                key = "saveClient:c1",
                change = { throw ApiException(message, code = "CONFLICT") },
                reload = { reloads += message },
                onSaved = { saved = true },
            )
            runCurrent()
        }

        // The Stylist is told, nothing counts as saved, and the screen then shows the record the server has.
        assertEquals(refusals, failures.map { it.message })
        assertEquals(refusals, reloads)
        assertFalse(saved)
        assertFalse(runner.busy.value)
    }

    @Test
    fun `a second change to the Clients of an Appointment waits until the first is answered and the Appointment loaded again`() = runTest {
        val runner = runner()
        val answered = CompletableDeferred<Unit>()
        val reloaded = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()

        // A2: 'Make primary' on a Client and, within a second, 'Remove from Appointment' on the same one.
        runner.run(key = clientsChangeKey("a1"), change = { sent += "make primary"; answered.await() }, reload = { reloaded.await() })
        runner.run(key = clientsChangeKey("a1"), change = { sent += "remove" })
        runCurrent()
        assertEquals(listOf("make primary"), sent)

        // Not while the Appointment is being loaded again either: the screen still shows the Clients of before.
        answered.complete(Unit)
        runCurrent()
        runner.run(key = clientsChangeKey("a1"), change = { sent += "remove" })
        // The Clients of another Appointment are not held back.
        runner.run(key = clientsChangeKey("a2"), change = { sent += "remove from another Appointment" })
        runCurrent()
        assertEquals(listOf("make primary", "remove from another Appointment"), sent)
        assertTrue(runner.busy.value)

        reloaded.complete(Unit)
        runCurrent()
        assertFalse(runner.busy.value)

        runner.run(key = clientsChangeKey("a1"), change = { sent += "remove" })
        runCurrent()
        assertEquals(listOf("make primary", "remove from another Appointment", "remove"), sent)
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
    fun `taps of which a later one fails still reload what an earlier one saved`() = runTest {
        val runner = runner()
        val primary = LatestWins<String, String>()
        val replies = ArrayDeque<CompletableDeferred<Unit>>()
        val sent = mutableListOf<String>()
        var savedAny = false
        var saved = false
        var reloads = 0

        // 'Make primary' on one Client, and on another before the first is answered.
        primary.offer("a1", "client-x")
        runner.run(
            key = null,
            change = {
                primary.send("a1") { next ->
                    sent += next
                    CompletableDeferred<Unit>().also(replies::addLast).await()
                    savedAny = true
                }
            },
            reload = { reloads++ },
            savedInPart = { savedAny },
            onSaved = { saved = true },
        )
        primary.offer("a1", "client-y")

        replies.removeFirst().complete(Unit)
        runCurrent()
        replies.removeFirst().completeExceptionally(ApiException("Network request failed.", code = "NETWORK", isNetwork = true))
        runCurrent()

        // The second tap failed and is reported as such. The server has the first one's Client, and the reload shows it.
        assertEquals(listOf("client-x", "client-y"), sent)
        assertEquals(listOf("NETWORK"), failures.map { it.code })
        assertFalse(saved)
        assertEquals(1, reloads)
        assertFalse(runner.busy.value)
    }

    @Test
    fun `taps of which the first fails have nothing to reload`() = runTest {
        val runner = runner()
        var reloads = 0

        runner.run<Unit>(
            key = null,
            change = { throw ApiException("Network request failed.", code = "NETWORK", isNetwork = true) },
            reload = { reloads++ },
            savedInPart = { false },
        )
        runCurrent()

        assertEquals(listOf("NETWORK"), failures.map { it.code })
        assertEquals(0, reloads)
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

    // --- changes that are shown ahead of the server's answer (optimistic changes) ---

    /** Notes what the runner does with a change that is shown ahead, in its order. */
    private fun ahead(name: String, steps: MutableList<String>) =
        Ahead(show = { steps += "$name shown" }, saved = { steps += "$name saved" }, settle = { steps += "$name settled" })

    @Test
    fun `a change that is shown ahead does not raise busy, and one that is waited for still does`() = runTest {
        val runner = runner()
        val steps = mutableListOf<String>()
        val reply = CompletableDeferred<Unit>()
        val reloaded = CompletableDeferred<Unit>()

        runner.run(key = "deleteService:s1", change = { reply.await() }, reload = { reloaded.await() }, ahead = ahead("delete", steps), onSaved = { steps += "told" })

        // On screen with the tap, and nothing is disabled: only the quiet sign is up.
        assertEquals(listOf("delete shown"), steps)
        assertFalse(runner.busy.value)
        assertTrue(runner.saving.value)

        // Saved: it is what the server has, and what was laid over the screen is taken away before the reload.
        reply.complete(Unit)
        runCurrent()
        assertEquals(listOf("delete shown", "delete saved", "delete settled", "told"), steps)
        assertFalse(runner.busy.value)
        assertTrue(runner.saving.value)

        reloaded.complete(Unit)
        runCurrent()
        assertFalse(runner.saving.value)

        // A change the Stylist waits for is as it was: busy from the tap until its reload has run.
        val waited = CompletableDeferred<Unit>()

        runner.run(key = "addService:a1", change = { waited.await() })
        assertTrue(runner.busy.value)
        assertTrue(runner.saving.value)

        waited.complete(Unit)
        runCurrent()
        assertFalse(runner.busy.value)
        assertFalse(runner.saving.value)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a change shown ahead that fails is taken off the screen and reported once, by the same rules`() = runTest {
        val reloads = mutableListOf<String>()
        val runner = ActionRunner(backgroundScope, { failures += it }, reloadAll = { reloads += "everything" })
        val steps = mutableListOf<String>()

        // Refused: nothing was saved, so nothing is reloaded.
        runner.run<Unit>(key = "deletePhoto:p1", change = { throw serverError() }, reload = { reloads += "the Appointment" }, ahead = ahead("refused", steps))
        runCurrent()
        assertEquals(listOf("refused shown", "refused settled"), steps)
        assertEquals(listOf("INTERNAL_SERVER_ERROR"), failures.map { it.code })
        assertTrue(reloads.isEmpty())

        // No reply: reported as unknown, and reloaded to show what became of it.
        runner.run<Unit>(
            key = "deletePhoto:p1",
            change = { throw ApiException("No reply from the server.", code = ApiException.NO_REPLY) },
            reload = { reloads += "the Appointment" },
            ahead = ahead("unanswered", steps),
        )
        runCurrent()
        assertEquals(listOf("unanswered shown", "unanswered settled"), steps.drop(2))
        assertTrue(failures.last().outcomeUnknown)
        assertEquals(listOf("the Appointment"), reloads)

        // No longer there: everything is reloaded.
        runner.run<Unit>(
            key = "deletePhoto:p1",
            change = { throw ApiException("Photo not found.", code = "NOT_FOUND") },
            reload = { reloads += "the Appointment" },
            ahead = ahead("gone", steps),
        )
        runCurrent()
        assertEquals(listOf("gone shown", "gone settled"), steps.drop(4))
        assertEquals(listOf("the Appointment", "everything", "the Appointment"), reloads)
        assertEquals(3, failures.size)
        assertFalse(runner.saving.value)
    }

    @Test
    fun `a reload that fails after a change shown ahead stays owed`() = runTest {
        val runner = runner()
        var reloads = 0
        var online = false

        runner.run(key = "deleteService:s1", change = { }, reload = { reloads++; if (!online) throw serverError() }, ahead = ahead("delete", mutableListOf()))
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(3, reloads)
        assertEquals(1, staleNotices)
        assertTrue(failures.isEmpty())

        online = true
        runner.reloadOwed()
        assertEquals(4, reloads)
        runner.reloadOwed()
        assertEquals(4, reloads)
    }

    @Test
    fun `a second call with the key of a change shown ahead shows nothing and sends nothing`() = runTest {
        val runner = runner()
        val steps = mutableListOf<String>()
        val reply = CompletableDeferred<Unit>()
        var requests = 0

        repeat(2) { runner.run(key = "deleteService:s1", change = { requests++; reply.await() }, ahead = ahead("delete", steps)) }

        assertEquals(1, requests)
        assertEquals(listOf("delete shown"), steps)

        reply.complete(Unit)
        runCurrent()
        assertEquals(listOf("delete shown", "delete saved", "delete settled"), steps)
    }

    @Test
    fun `changes shown ahead are all on screen at once and sent one at a time, in the order they were made`() = runTest {
        val runner = runner()
        val steps = mutableListOf<String>()
        val replies = List(3) { CompletableDeferred<Unit>() }

        // A Color Formula, then its Service, then a photo: the server must not get the Service's delete first.
        listOf("formula", "service", "photo").forEachIndexed { index, name ->
            runner.run(key = "delete:$name", change = { steps += "$name sent"; replies[index].await() }, ahead = ahead(name, steps))
        }
        assertEquals(listOf("formula shown", "formula sent", "service shown", "photo shown"), steps)

        replies[0].complete(Unit)
        runCurrent()
        assertEquals(listOf("formula saved", "formula settled", "service sent"), steps.drop(4))

        // One that fails does not hold up the next.
        replies[1].completeExceptionally(serverError())
        runCurrent()
        assertEquals(listOf("service settled", "photo sent"), steps.drop(7))

        replies[2].complete(Unit)
        runCurrent()
        assertEquals(listOf("photo saved", "photo settled"), steps.drop(9))
        assertEquals(1, failures.size)
        assertFalse(runner.saving.value)
    }

    @Test
    fun `a change that is waited for is sent after the changes shown ahead before it, and not after later ones`() = runTest {
        val runner = runner()
        val steps = mutableListOf<String>()
        val deleted = CompletableDeferred<Unit>()
        val removed = CompletableDeferred<Unit>()

        // The Stylist deletes a Client's last Service and then removes the Client, whom the screen now offers to remove.
        runner.run(key = "deleteService:s1", change = { steps += "delete sent"; deleted.await() }, ahead = ahead("delete", steps))
        runner.run(key = clientsChangeKey("a1"), change = { steps += "remove sent"; removed.await() })

        // Busy from the tap, also while it waits its turn.
        assertTrue(runner.busy.value)
        assertEquals(listOf("delete shown", "delete sent"), steps)

        deleted.complete(Unit)
        runCurrent()
        assertEquals(listOf("delete saved", "delete settled", "remove sent"), steps.drop(2))

        // A change shown ahead that is made while the other is in flight does not wait for it.
        runner.run(key = "deletePhoto:p1", change = { steps += "photo sent" }, ahead = ahead("photo", steps))
        assertEquals(listOf("photo shown", "photo sent", "photo saved", "photo settled"), steps.drop(5))

        removed.complete(Unit)
        runCurrent()
        assertFalse(runner.busy.value)
        assertFalse(runner.saving.value)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `what is shown ahead is taken away when the change is cancelled`() = runTest {
        val runner = runner()
        val steps = mutableListOf<String>()

        runner.run(key = "deleteService:s1", change = { CompletableDeferred<Unit>().await() }, ahead = ahead("delete", steps))
        backgroundScope.cancel()
        runCurrent()

        assertEquals(listOf("delete shown", "delete settled"), steps)
        assertFalse(runner.saving.value)
        assertTrue(failures.isEmpty())
    }
}
