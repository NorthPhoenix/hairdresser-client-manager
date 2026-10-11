package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.LoadTracker
import com.northphoenix.hairdresserclientmanager.state.together
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

/** No screen may show a spinner for ever, or "empty" for data that failed to load. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadStateTest {
    private fun offline() = ApiException("Network request failed.", code = "NETWORK", isNetwork = true)

    private val failedOffline = LoadState.Failed(R.string.network_error)

    /** Runs a load and gives back what it threw, if anything. */
    private suspend fun failureOf(load: suspend () -> Unit): Exception? =
        try {
            load()
            null
        } catch (error: Exception) {
            error
        }

    // --- a list ---

    @Test
    fun `a list is loading until its first load ends and loaded from then on`() = runTest {
        val tracker = LoadTracker()
        val reply = CompletableDeferred<List<String>>()
        var clients = emptyList<String>()

        // Not asked for yet: still not "empty".
        assertEquals(LoadState.Loading, tracker.state.value)

        launch { tracker.load({ reply.await() }) { clients = it } }
        runCurrent()
        assertEquals(LoadState.Loading, tracker.state.value)

        reply.complete(listOf("Anna"))
        runCurrent()
        assertEquals(LoadState.Loaded, tracker.state.value)
        assertEquals(listOf("Anna"), clients)
    }

    @Test
    fun `a first load that fails says why, and trying again shows the spinner again`() = runTest {
        val tracker = LoadTracker()

        val failure = failureOf { tracker.load<List<String>>({ throw offline() }) { fail("nothing to apply") } }

        // Thrown on, so the caller can raise a notice as well.
        assertTrue((failure as ApiException).isNetwork)
        assertEquals(failedOffline, tracker.state.value)

        val reply = CompletableDeferred<List<String>>()
        launch { tracker.load({ reply.await() }) { } }
        runCurrent()
        assertEquals(LoadState.Loading, tracker.state.value)

        reply.complete(emptyList())
        runCurrent()
        assertEquals(LoadState.Loaded, tracker.state.value)
    }

    @Test
    fun `what is on screen stays loaded while it is loaded again and when that fails`() = runTest {
        val tracker = LoadTracker()
        var clients = emptyList<String>()

        tracker.load({ listOf("Anna") }) { clients = it }

        val reply = CompletableDeferred<List<String>>()
        val reload = launch { assertTrue(failureOf { tracker.load({ reply.await() }) { clients = it } } is ApiException) }
        runCurrent()
        assertEquals(LoadState.Loaded, tracker.state.value)

        reply.completeExceptionally(offline())
        runCurrent()
        assertTrue(reload.isCompleted)
        assertEquals(LoadState.Loaded, tracker.state.value)
        assertEquals(listOf("Anna"), clients)
    }

    @Test
    fun `a load that an unexpected error ends is failed too, never a spinner`() = runTest {
        val tracker = LoadTracker()

        failureOf { tracker.load<Unit>({ error("Element class JsonLiteral is not a JsonObject") }) { } }

        assertEquals(LoadState.Failed(R.string.error_generic), tracker.state.value)
    }

    // --- a Calendar day: two days tapped in quick succession, a day that failed ---

    @Test
    fun `the load of a day the Stylist has left changes nothing when it is cancelled`() = runTest {
        val tracker = LoadTracker()
        var shown = listOf("left over")

        val firstDay = launch { tracker.load({ CompletableDeferred<List<String>>().await() }) { shown = it } }
        runCurrent()

        // The Stylist taps another day: its load starts and the first one is cancelled.
        tracker.reset()
        shown = emptyList()
        val reply = CompletableDeferred<List<String>>()
        launch { tracker.load({ reply.await() }) { shown = it } }
        firstDay.cancel()
        runCurrent()

        // The day is still on its way: a spinner, not "No Appointments on this day".
        assertEquals(LoadState.Loading, tracker.state.value)

        reply.complete(listOf("8:33 AM"))
        runCurrent()
        assertEquals(LoadState.Loaded, tracker.state.value)
        assertEquals(listOf("8:33 AM"), shown)
    }

    @Test
    fun `what arrives for a day the Stylist has left is dropped, whether it loaded or failed`() = runTest {
        val tracker = LoadTracker()
        val leftDay = CompletableDeferred<List<String>>()
        val failedDay = CompletableDeferred<List<String>>()
        var shown = emptyList<String>()
        var thrown: Exception? = null

        launch { tracker.load({ leftDay.await() }) { shown = it } }
        runCurrent()
        tracker.reset()
        launch { thrown = failureOf { tracker.load({ failedDay.await() }) { shown = it } } }
        runCurrent()
        tracker.reset()
        val reply = CompletableDeferred<List<String>>()
        launch { tracker.load({ reply.await() }) { shown = it } }
        runCurrent()

        leftDay.complete(listOf("another day's Appointment"))
        failedDay.completeExceptionally(offline())
        runCurrent()

        // Neither the other day's Appointments nor its failure reach the day on screen.
        assertEquals(emptyList<String>(), shown)
        assertNull(thrown)
        assertEquals(LoadState.Loading, tracker.state.value)

        reply.complete(emptyList())
        runCurrent()
        assertEquals(LoadState.Loaded, tracker.state.value)
    }

    @Test
    fun `a day that failed to load stays failed until it is loaded again`() = runTest {
        val tracker = LoadTracker()

        failureOf { tracker.load<List<String>>({ throw offline() }) { } }
        assertEquals(failedOffline, tracker.state.value)

        // Tapping the same day again.
        tracker.load({ listOf("9:00 AM") }) { }
        assertEquals(LoadState.Loaded, tracker.state.value)
    }

    // --- several lists at once ---

    @Test
    fun `one list that fails does not stop the others, and its failure is thrown at the end`() = runTest {
        val clients = LoadTracker()
        val today = LoadTracker()
        val todayReply = CompletableDeferred<List<String>>()
        var cards = emptyList<String>()
        var thrown: Exception? = null

        val loading = launch {
            thrown = failureOf {
                together(
                    { clients.load<List<String>>({ throw offline() }) { } },
                    { today.load({ todayReply.await() }) { cards = it } },
                )
            }
        }
        runCurrent()

        // The Client list has failed; Today is still on its way and was not cancelled.
        assertEquals(failedOffline, clients.state.value)
        assertEquals(LoadState.Loading, today.state.value)
        assertFalse(loading.isCompleted)

        todayReply.complete(listOf("8:33 AM"))
        runCurrent()
        assertEquals(LoadState.Loaded, today.state.value)
        assertEquals(listOf("8:33 AM"), cards)
        assertTrue((thrown as ApiException).isNetwork)
    }

    // One Appointment, and what a change makes of what is loaded: see ReadCacheTest.
}
