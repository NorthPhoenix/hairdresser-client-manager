package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.state.EarlyReads
import com.northphoenix.hairdresserclientmanager.state.ListsOf
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Once the settings have come at a start of the app, the reads that went out with them are the reload: only what failed or moved is asked for again. */
@OptIn(ExperimentalCoroutinesApi::class)
class EarlyReadsTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val asked = mutableListOf<String>()
    private var failing = emptySet<String>()

    private val reloads = object : EarlyReads.Reloads {
        override suspend fun stylistLists() = ask("stylist lists")

        override suspend fun dayLists() = ask("day lists")

        override suspend fun todayLists() = ask("today")

        override suspend fun calendarDay() = ask("calendar day")

        override suspend fun releases() = ask("releases")

        private fun ask(what: String) {
            asked += what
            if (what in failing) {
                throw ApiException("No connection.", isNetwork = true)
            }
        }
    }

    private fun answered(): Deferred<Unit> = CompletableDeferred(Unit)

    private fun failed(): Deferred<Unit> = CompletableDeferred<Unit>().apply { completeExceptionally(ApiException("No connection.", isNetwork = true)) }

    private fun early(
        ofStylist: Deferred<Unit> = answered(),
        ofDays: Deferred<Unit> = answered(),
        releases: Deferred<Boolean> = CompletableDeferred(true),
    ) = EarlyReads(ListsOf("stylist-1", "America/Chicago"), days = today to today, ofStylist, ofDays, releases)

    @Test
    fun whatWentOutIsTheReloadWhenNothingFailedOrMoved() = runTest {
        early().reloadAfter(daysAgain = false, now = today to today, reloads)

        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun aPartThatFailedIsAskedForAgainWithoutWaitingForTheOther() = runTest {
        val days = CompletableDeferred<Unit>()
        val reload = launch { early(ofStylist = failed(), ofDays = days).reloadAfter(daysAgain = false, now = today to today, reloads) }

        runCurrent()
        assertEquals(listOf("stylist lists"), asked)
        assertTrue(reload.isActive)

        days.complete(Unit)
        reload.join()
        assertEquals(listOf("stylist lists"), asked)
    }

    @Test
    fun dayListsThatFailedAreAskedForAgain() = runTest {
        early(ofDays = failed()).reloadAfter(daysAgain = false, now = today to today, reloads)

        assertEquals(listOf("day lists"), asked)
    }

    @Test
    fun aReadThatEndedWithoutAnAnswerIsAskedForAgain() = runTest {
        early(ofStylist = CompletableDeferred<Unit>().apply { cancel() }).reloadAfter(daysAgain = false, now = today to today, reloads)

        assertEquals(listOf("stylist lists"), asked)
    }

    @Test
    fun anotherTimezoneAsksForTheDayListsAtOnceAndTheStylistsOwnListsStand() = runTest {
        val days = CompletableDeferred<Unit>()

        early(ofDays = days).reloadAfter(daysAgain = true, now = today to today, reloads)

        assertEquals(listOf("day lists"), asked)
        assertTrue(days.isCancelled)
    }

    @Test
    fun aDayTheSettingsMovedToIsAskedForAtOnce() = runTest {
        val days = CompletableDeferred<Unit>()
        val reload = launch { early(ofDays = days).reloadAfter(daysAgain = false, now = today.plusDays(1) to today.plusDays(1), reloads) }

        // Midnight passed while the settings were on their way: with the day's own watcher, not after the early day lists.
        runCurrent()
        assertEquals(setOf("today", "calendar day"), asked.toSet())

        days.complete(Unit)
        reload.join()
        assertEquals(2, asked.size)
    }

    @Test
    fun theCalendarsRestoredDayAloneIsAskedForWhenTodayStayed() = runTest {
        early().reloadAfter(daysAgain = false, now = today to today.plusDays(5), reloads)

        assertEquals(listOf("calendar day"), asked)
    }

    @Test
    fun aFailureOfWhatIsAskedForAgainIsThrownOnceEverythingHasEnded() = runTest {
        failing = setOf("day lists")

        try {
            early(ofStylist = failed(), ofDays = failed()).reloadAfter(daysAgain = false, now = today to today, reloads)
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertTrue(error.isNetwork)
        }
        assertEquals(setOf("stylist lists", "day lists"), asked.toSet())
    }

    @Test
    fun releasesThatWereNotLoadedAreAskedForAgain() = runTest {
        early(releases = CompletableDeferred(true)).releasesAgain(reloads)
        assertEquals(emptyList<String>(), asked)

        early(releases = CompletableDeferred(false)).releasesAgain(reloads)
        assertEquals(listOf("releases"), asked)
    }

    @Test
    fun listsOfOtherKeysAreNotWaitedForWhileTheReleasesGoOn() {
        val ofStylist = CompletableDeferred<Unit>()
        val ofDays = CompletableDeferred<Unit>()
        val releases = CompletableDeferred<Boolean>()

        early(ofStylist, ofDays, releases).cancelLists()

        assertTrue(ofStylist.isCancelled)
        assertTrue(ofDays.isCancelled)
        assertFalse(releases.isCancelled)
    }
}
