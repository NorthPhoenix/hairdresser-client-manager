package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import kotlinx.coroutines.CoroutineScope

/** One period of Итоги: [period] is `week`, `month` or `year`, [date] a day of it ("2026-10-21"). */
internal data class EarningsKey(val period: String, val date: String)

/**
 * The read of Итоги (`stats.summary`), through a [ReadCache]. It adds up Appointments, Services and
 * tips, so a change to any of them puts every answer out of date: after any change at all a
 * summary is loaded anew the next time it is shown.
 *
 * Not thread-safe: use it from the thread of the scope.
 */
internal class EarningsReads(
    scope: CoroutineScope,
    changes: Changes,
    fetch: suspend (EarningsKey) -> EarningsSummary,
) {
    /** The periods looked at of late: week, month and year of a few steps back. */
    val summary = ReadCache(scope, changes, CacheLimits(answers = 20), about = { _, _ -> listOf(Changes.ANY_CHANGE) }, fetch = fetch)

    /** Empties the cache, for a Stylist who has signed out. Call [Changes.startOver] first. */
    suspend fun clear() = summary.clear()
}
