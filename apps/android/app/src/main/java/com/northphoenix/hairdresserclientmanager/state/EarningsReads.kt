package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.EarningsSummary
import com.northphoenix.hairdresserclientmanager.data.EarningsTrend
import com.northphoenix.hairdresserclientmanager.domain.Earnings
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope

/**
 * The bars Итоги shows ([bars], null for none), and whether the summary under them may show
 * ([settled]) rather than wait a moment for bars still on their way. [answeredNone]: the last
 * answer had no bars to show (or failed), so the next ones are not waited for.
 */
internal data class TrendShown(val bars: EarningsTrend?, val settled: Boolean, val answeredNone: Boolean = false)

/**
 * What Итоги shows once [next] comes for the bars asked for, while the period starting [selected]
 * is selected and [before] is what it showed so far. Bars on screen stay while the next ones are
 * read, so the card does not blink out under the finger (also when the bars move to other periods);
 * bars that were not on screen never come back that way. The summary waits for bars on their way
 * only when bars are expected: not after an answer without any.
 */
internal fun trendShown(before: TrendShown?, next: Read<EarningsTrend>, selected: LocalDate): TrendShown {
    val answer = next.data
    val answeredNone = before?.answeredNone ?: false

    return when {
        answer != null -> answer.takeIf { Earnings.showsTrend(it, selected) }.let { TrendShown(it, settled = true, answeredNone = it == null) }
        next.state == LoadState.Loading -> TrendShown(before?.bars, settled = before?.bars != null || answeredNone, answeredNone)
        else -> TrendShown(null, settled = true, answeredNone = true)
    }
}

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
