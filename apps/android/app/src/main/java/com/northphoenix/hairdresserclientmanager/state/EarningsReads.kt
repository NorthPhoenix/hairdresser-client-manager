package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import com.northphoenix.hairdresserclientmanager.data.EarningsTrend
import kotlinx.coroutines.CoroutineScope

/** One period of Итоги: [period] is `week`, `month` or `year`, [date] a day of it ("2026-10-21"). For the bars, the newest period's. */
internal data class EarningsKey(val period: String, val date: String)

/**
 * The reads of Итоги (`stats.summary`, and `stats.trend` for its bars), through [ReadCache]s. It adds up Appointments, Services and
 * tips, so a change to any of them puts every answer out of date: after any change at all a
 * summary is loaded anew the next time it is shown.
 *
 * Not thread-safe: use it from the thread of the scope.
 */
internal class EarningsReads(
    scope: CoroutineScope,
    changes: Changes,
    fetch: suspend (EarningsKey) -> EarningsSummary,
    fetchTrend: suspend (EarningsKey) -> EarningsTrend,
) {
    /** The periods looked at of late: week, month and year of a few steps back. */
    val summary = ReadCache(scope, changes, CacheLimits(answers = 20), about = { _, _ -> listOf(Changes.ANY_CHANGE) }, fetch = fetch)

    /** The bars: what each of the last few periods earned, by the newest of them. */
    val trend = ReadCache(scope, changes, CacheLimits(answers = 10), about = { _, _ -> listOf(Changes.ANY_CHANGE) }, fetch = fetchTrend)

    /** Empties the caches, for a Stylist who has signed out. Call [Changes.startOver] first. */
    suspend fun clear() {
        summary.clear()
        trend.clear()
    }
}
