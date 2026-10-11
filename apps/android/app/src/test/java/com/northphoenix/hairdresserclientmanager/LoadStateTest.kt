package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.state.CacheLimits
import com.northphoenix.hairdresserclientmanager.state.Changes
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.state.ReadCache
import com.northphoenix.hairdresserclientmanager.state.showLatest
import com.northphoenix.hairdresserclientmanager.state.together
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No screen may show a spinner for ever, or "empty" for data that failed to load. The state of
 * every read comes from the read cache ([ReadCache]); these are its rules for one list and for a
 * place that shows one key at a time, with each request answered by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadStateTest {
    private fun offline() = ApiException("Network request failed.", code = "NETWORK", isNetwork = true)

    private val failedOffline = LoadState.Failed(R.string.network_error)
    private val loading = Read<List<String>>(LoadState.Loading)

    /** A read whose requests are answered by the test: [replies] holds one for each request, in the order they were sent. */
    private class Asked {
        val replies = mutableListOf<CompletableDeferred<List<String>>>()

        suspend fun fetch(): List<String> = CompletableDeferred<List<String>>().also { replies += it }.await()
    }

    private fun TestScope.cache(asked: Asked) = ReadCache<String, List<String>>(backgroundScope, Changes(), CacheLimits(answers = 10)) { asked.fetch() }

    /** What a screen that watches [key] is shown, value by value. */
    private fun TestScope.shownOf(cache: ReadCache<String, List<String>>, key: String): List<Read<List<String>>> {
        val shown = mutableListOf<Read<List<String>>>()

        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { cache.watch(key).collect { shown += it } }

        return shown
    }

    /** Runs a load and gives back what it threw, if anything. A load that was given up threw nothing to report. */
    private suspend fun failureOf(load: suspend () -> Unit): Exception? =
        try {
            load()
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }

    // --- a list ---

    @Test
    fun `a list is loading until its first load ends and loaded from then on`() = runTest {
        val asked = Asked()
        val cache = cache(asked)

        // Not asked for yet: still not "empty".
        assertEquals(loading, cache.now("clients"))

        val shown = shownOf(cache, "clients")
        runCurrent()
        assertEquals(listOf(loading), shown)

        asked.replies.single().complete(listOf("Anna"))
        runCurrent()
        assertEquals(listOf(loading, Read(LoadState.Loaded, listOf("Anna"))), shown)
    }

    @Test
    fun `a first load that fails says why, and trying again shows the spinner again`() = runTest {
        val asked = Asked()
        val cache = cache(asked)
        val shown = shownOf(cache, "clients")
        runCurrent()

        asked.replies.single().completeExceptionally(offline())
        runCurrent()
        assertEquals(listOf(loading, Read(failedOffline)), shown)

        // 'Try again': thrown on when it fails too, so the caller can raise a notice as well.
        var thrown: Exception? = null
        val retry = launch { thrown = failureOf { cache.refresh("clients") } }
        runCurrent()
        assertEquals(loading, shown.last())
        asked.replies.last().completeExceptionally(offline())
        runCurrent()
        assertTrue(retry.isCompleted)
        assertTrue((thrown as ApiException).isNetwork)
        assertEquals(Read<List<String>>(failedOffline), shown.last())

        val again = launch { cache.refresh("clients") }
        runCurrent()
        assertEquals(loading, shown.last())

        asked.replies.last().complete(emptyList())
        runCurrent()
        assertTrue(again.isCompleted)
        assertEquals(Read(LoadState.Loaded, emptyList<String>()), shown.last())
    }

    @Test
    fun `what is on screen stays loaded while it is loaded again and when that fails`() = runTest {
        val asked = Asked()
        val cache = cache(asked)
        val shown = shownOf(cache, "clients")
        runCurrent()
        asked.replies.single().complete(listOf("Anna"))
        runCurrent()

        val reload = launch { assertTrue(failureOf { cache.refresh("clients") } is ApiException) }
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf("Anna")), shown.last())

        asked.replies.last().completeExceptionally(offline())
        runCurrent()
        assertTrue(reload.isCompleted)
        assertEquals(listOf(loading, Read(LoadState.Loaded, listOf("Anna"))), shown)
    }

    @Test
    fun `a load that an unexpected error ends is failed too, never a spinner`() = runTest {
        val asked = Asked()
        val cache = cache(asked)
        val shown = shownOf(cache, "clients")
        runCurrent()

        asked.replies.single().completeExceptionally(IllegalStateException("Element class JsonLiteral is not a JsonObject"))
        runCurrent()

        assertEquals(Read<List<String>>(LoadState.Failed(R.string.error_generic)), shown.last())
    }

    // --- a Calendar day: two days tapped in quick succession, a day that failed ---

    @Test
    fun `the load of a day the Stylist has left changes nothing when it is cancelled`() = runTest {
        val asked = Asked()
        val cache = cache(asked)
        val day = MutableStateFlow("monday")
        val calendar = mutableListOf<Pair<String, Read<List<String>>>>()

        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { showLatest(day, cache::watch) { key, read -> calendar += key to read } }
        runCurrent()

        // The Stylist taps another day: its load starts and the first one is given up.
        day.value = "tuesday"
        runCurrent()

        // The day is still on its way: a spinner, not "No Appointments on this day".
        assertEquals("tuesday" to loading, calendar.last())

        asked.replies.last().complete(listOf("8:33 AM"))
        runCurrent()
        assertEquals(listOf("monday" to loading, "tuesday" to loading, "tuesday" to Read(LoadState.Loaded, listOf("8:33 AM"))), calendar)
    }

    @Test
    fun `what arrives for a day the Stylist has left is dropped, whether it loaded or failed`() = runTest {
        val asked = Asked()
        val cache = cache(asked)
        val day = MutableStateFlow("monday")
        val calendar = mutableListOf<Pair<String, Read<List<String>>>>()
        var thrown: Exception? = null

        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { showLatest(day, cache::watch) { key, read -> calendar += key to read } }
        // Each tap asks for its day as well, as the Calendar does, and would report what that throws.
        val monday = launch { thrown = failureOf { cache.refresh("monday") } }
        runCurrent()
        day.value = "tuesday"
        val tuesday = launch { thrown = failureOf { cache.refresh("tuesday") } }
        runCurrent()
        day.value = "wednesday"
        runCurrent()
        monday.cancel()
        tuesday.cancel()
        runCurrent()

        // Whatever still comes of the days before: neither their Appointments nor their failure reach the day on screen.
        asked.replies[0].complete(listOf("another day's Appointment"))
        asked.replies[1].completeExceptionally(offline())
        runCurrent()
        assertEquals("wednesday" to loading, calendar.last())
        assertTrue(calendar.none { (key, read) -> key != "wednesday" && read.state != LoadState.Loading })
        assertEquals(null, thrown)

        asked.replies.last().complete(emptyList())
        runCurrent()
        assertEquals("wednesday" to Read(LoadState.Loaded, emptyList<String>()), calendar.last())
    }

    @Test
    fun `a day that failed to load stays failed until it is loaded again`() = runTest {
        val asked = Asked()
        val cache = cache(asked)
        val shown = shownOf(cache, "monday")
        runCurrent()
        asked.replies.single().completeExceptionally(offline())
        runCurrent()
        assertEquals(Read<List<String>>(failedOffline), shown.last())
        assertEquals(Read<List<String>>(failedOffline), cache.shown("monday"))

        // Tapping the same day again.
        val tap = launch { cache.refresh("monday") }
        runCurrent()
        asked.replies.last().complete(listOf("9:00 AM"))
        runCurrent()
        assertTrue(tap.isCompleted)
        assertEquals(Read(LoadState.Loaded, listOf("9:00 AM")), shown.last())
    }

    // --- several lists at once ---

    @Test
    fun `one list that fails does not stop the others, and its failure is thrown at the end`() = runTest {
        val clientsAsked = Asked()
        val todayAsked = Asked()
        val clients = cache(clientsAsked)
        val today = cache(todayAsked)
        val clientList = shownOf(clients, "clients")
        val cards = shownOf(today, "today")
        var thrown: Exception? = null

        val loadingAll = launch { thrown = failureOf { together({ clients.refresh("clients") }, { today.refresh("today") }) } }
        runCurrent()
        clientsAsked.replies.last().completeExceptionally(offline())
        runCurrent()

        // The Client list has failed; Today is still on its way and was not cancelled.
        assertEquals(Read<List<String>>(failedOffline), clientList.last())
        assertEquals(loading, cards.last())
        assertFalse(loadingAll.isCompleted)

        todayAsked.replies.last().complete(listOf("8:33 AM"))
        runCurrent()
        assertEquals(Read(LoadState.Loaded, listOf("8:33 AM")), cards.last())
        assertTrue(loadingAll.isCompleted)
        assertTrue((thrown as ApiException).isNetwork)
    }

    // One Appointment, and what a change makes of what is loaded: see ReadCacheTest. The other lists: see ClientReadsTest.
}
