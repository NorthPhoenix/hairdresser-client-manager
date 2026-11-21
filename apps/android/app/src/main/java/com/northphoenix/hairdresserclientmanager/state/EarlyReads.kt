package com.northphoenix.hairdresserclientmanager.state

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The reads that went out with the settings at a start of the app (see [StartingReads]): the
 * lists of the Stylist alone ([ofStylist]: the Client list and the Service Menu), the day lists
 * ([ofDays], asked for on [days]: today and the Calendar's day), and the releases ([releases]:
 * true once they were loaded, since a failure to load them is not thrown, see `AppUpdateNotices`).
 */
internal class EarlyReads(
    val lists: ListsOf,
    val days: Pair<LocalDate, LocalDate>,
    private val ofStylist: Deferred<Unit>,
    private val ofDays: Deferred<Unit>,
    private val releases: Deferred<Boolean>,
) {
    /** What the reload asks for again. */
    interface Reloads {
        suspend fun stylistLists()

        suspend fun dayLists()

        /** Today's list and the day after it. */
        suspend fun todayLists()

        suspend fun calendarDay()

        suspend fun releases()
    }

    /** The settings have another Stylist, or none with lists: what went out for these keys is not waited for. The releases are the app's and go on. */
    fun cancelLists() {
        ofStylist.cancel()
        ofDays.cancel()
    }

    /**
     * The reload once the settings of the same Stylist have come ([AfterSettings.STAND], or
     * [AfterSettings.DAYS_AGAIN] with [daysAgain]), with today and the Calendar's day they put in
     * place ([now]): the lists that went out with them, and what they could not have known asked
     * for now, as it would have been then. Each part that failed is asked for again as soon as it
     * has: the server has just answered. The day lists are asked for again at once under another
     * timezone (the Stylist's own lists stand), and so is a day the settings moved to (midnight,
     * or the Calendar's day Android restores), together with its watcher: the watcher alone would
     * not say that it failed. A failure of what is asked for again is thrown when all have ended.
     */
    suspend fun reloadAfter(daysAgain: Boolean, now: Pair<LocalDate, LocalDate>, reloads: Reloads) {
        together(
            { if (!ofStylist.succeeded()) reloads.stylistLists() },
            {
                if (daysAgain) {
                    ofDays.cancel()
                    reloads.dayLists()
                } else {
                    together(
                        { if (!ofDays.succeeded()) reloads.dayLists() },
                        { if (now.first != days.first) reloads.todayLists() },
                        { if (now.second != days.second) reloads.calendarDay() },
                    )
                }
            },
        )
    }

    /** Asks for the releases again when those that went out early were not loaded, whatever the settings said: they are the app's. */
    suspend fun releasesAgain(reloads: Reloads) {
        if (!releases.await()) {
            reloads.releases()
        }
    }

    /** False when the read failed, or ended without an answer: then it is asked for again. The caller's own cancellation is passed on. */
    private suspend fun Deferred<Unit>.succeeded(): Boolean =
        try {
            await()
            true
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (_: Exception) {
            false
        }
}
