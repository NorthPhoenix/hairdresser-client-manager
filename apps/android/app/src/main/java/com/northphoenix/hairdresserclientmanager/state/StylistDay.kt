package com.northphoenix.hairdresserclientmanager.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today for the Stylist: the day it is in the Stylist's timezone, as of the last look at the clock.
 *
 * Nothing in the app reads the date where it happens to need it: the date line, Today's list and
 * the day the Calendar starts on would then each change at a moment of their own. They all take
 * [today], which changes only in [follow], and whoever calls that brings the rest along.
 */
internal class StylistDay(private val now: () -> Instant = Instant::now) {
    private val _today = MutableStateFlow(now().atZone(ZoneId.systemDefault()).toLocalDate())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    /**
     * Looks at the clock. Returns the change when it is another day in [zone] than [today],
     * because midnight has passed or because the timezone is another one, and null otherwise.
     */
    fun follow(zone: ZoneId): Change? {
        val ended = _today.value
        val today = now().atZone(zone).toLocalDate()

        if (today == ended) {
            return null
        }
        _today.value = today

        return Change(ended, today)
    }

    /**
     * How long to wait before the next look at the clock: until the day ends in [zone], and never
     * longer than [longestWait], so that a clock that was set meanwhile is noticed as well.
     */
    fun millisUntilNextLook(zone: ZoneId): Long {
        val now = now()
        val nextDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

        // One more millisecond: the wait is counted in whole ones, and waking up just before midnight finds nothing.
        return (Duration.between(now, nextDay).toMillis() + 1).coerceAtMost(longestWait.toMillis())
    }

    /** The Stylist's day went from [ended] to [today]. */
    data class Change(val ended: LocalDate, val today: LocalDate) {
        /**
         * The Calendar's day after the change. A Calendar that was on today is on today again;
         * a day the Stylist picked stays selected.
         */
        fun calendarDay(selected: LocalDate): LocalDate = if (selected == ended) today else selected
    }

    private companion object {
        val longestWait: Duration = Duration.ofMinutes(1)
    }
}
