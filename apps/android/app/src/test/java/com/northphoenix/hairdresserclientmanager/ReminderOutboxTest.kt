package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.Reminded
import com.northphoenix.hairdresserclientmanager.domain.ReminderMessage
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.state.ReminderOutbox
import com.northphoenix.hairdresserclientmanager.state.ReminderStore
import com.northphoenix.hairdresserclientmanager.state.SendState
import com.northphoenix.hairdresserclientmanager.state.SmsFailure
import com.northphoenix.hairdresserclientmanager.state.SmsGateway
import com.northphoenix.hairdresserclientmanager.state.SmsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Sending the reminders of a batch one after another, what each row ends as, and what is remembered. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderOutboxTest {
    private class MemoryStore(var entries: Map<String, Reminded> = emptyMap()) : ReminderStore {
        override fun load() = entries

        override fun save(entries: Map<String, Reminded>) {
            this.entries = entries
        }
    }

    private val now = Instant.parse("2026-10-21T15:00:00Z")

    private fun message(id: String) = ReminderMessage(id, "2026-10-22T15:00:00Z", "Ann $id", "5125550100", "5125550100", Language.RU, "Text $id")

    @Test
    fun `every reminder is sent in order and remembered`() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<String>()
        val store = MemoryStore()
        val outbox = ReminderOutbox({ number, text -> sent += "$number:$text"; SmsResult.Sent }, store, backgroundScope, { now })

        outbox.send(listOf(message("a"), message("b")))

        assertEquals(listOf("5125550100:Text a", "5125550100:Text b"), sent)
        assertEquals(mapOf("a" to SendState.Sent, "b" to SendState.Sent), outbox.sends.value)
        assertEquals(setOf("a", "b"), store.entries.keys)
        assertEquals(now, outbox.log.value.getValue("a").at)
        assertFalse(outbox.busy)
    }

    @Test
    fun `a failed SMS is shown as failed, not remembered, and can be retried`() = runTest(UnconfinedTestDispatcher()) {
        var result: SmsResult = SmsResult.Failed(SmsFailure.AIRPLANE_MODE)
        val store = MemoryStore()
        val outbox = ReminderOutbox({ _, _ -> result }, store, backgroundScope, { now })

        outbox.send(listOf(message("a"), message("b")))

        assertEquals(SendState.Failed(SmsFailure.AIRPLANE_MODE), outbox.sends.value["a"])
        assertTrue(store.entries.isEmpty())

        result = SmsResult.Sent
        outbox.send(listOf(message("a")))

        assertEquals(SendState.Sent, outbox.sends.value["a"])
        assertEquals(SendState.Failed(SmsFailure.AIRPLANE_MODE), outbox.sends.value["b"])
        assertEquals(setOf("a"), store.entries.keys)
    }

    @Test
    fun `one failure does not stop the rest`() = runTest(UnconfinedTestDispatcher()) {
        val outbox = ReminderOutbox(
            { _, text -> if (text == "Text a") SmsResult.Failed(SmsFailure.NO_SERVICE) else SmsResult.Sent },
            MemoryStore(),
            backgroundScope,
            { now },
        )

        outbox.send(listOf(message("a"), message("b")))

        assertEquals(SendState.Failed(SmsFailure.NO_SERVICE), outbox.sends.value["a"])
        assertEquals(SendState.Sent, outbox.sends.value["b"])
    }

    @Test
    fun `reminders wait for their turn and the outbox is busy meanwhile`() = runTest(UnconfinedTestDispatcher()) {
        val release = CompletableDeferred<Unit>()
        val outbox = ReminderOutbox({ _, _ -> release.await(); SmsResult.Sent }, MemoryStore(), backgroundScope, { now })

        outbox.send(listOf(message("a"), message("b")))

        assertEquals(mapOf("a" to SendState.Sending, "b" to SendState.Waiting), outbox.sends.value)
        assertTrue(outbox.busy)

        // A reset while it goes changes nothing.
        outbox.reset()
        assertEquals(2, outbox.sends.value.size)

        release.complete(Unit)

        assertFalse(outbox.busy)
        outbox.reset()
        assertTrue(outbox.sends.value.isEmpty())
    }

    @Test
    fun `opening a reminder in the SMS app remembers it`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore()
        val outbox = ReminderOutbox({ _, _ -> error("not sent from here") }, store, backgroundScope, { now })

        outbox.opened(message("a"))

        assertEquals(SendState.Opened, outbox.sends.value["a"])
        assertEquals(setOf("a"), store.entries.keys)
    }

    @Test
    fun `what was remembered is there again after a restart, without what is too old`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(
            mapOf(
                "recent" to Reminded("recent", "2026-10-22T15:00:00Z", Instant.parse("2026-10-20T10:00:00Z")),
                "old" to Reminded("old", "2026-08-01T15:00:00Z", Instant.parse("2026-08-01T10:00:00Z")),
            ),
        )
        val outbox = ReminderOutbox({ _, _ -> SmsResult.Sent }, store, backgroundScope, { now })

        assertEquals(setOf("recent"), outbox.log.value.keys)
    }
}
