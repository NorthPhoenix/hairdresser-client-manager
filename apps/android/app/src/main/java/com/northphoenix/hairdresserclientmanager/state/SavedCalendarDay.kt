package com.northphoenix.hairdresserclientmanager.state

import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate

/**
 * The day the Stylist has selected in the Calendar, kept in saved state: after Android has killed
 * the app in the background the Calendar comes back on it, as the selected tab does.
 *
 * Today is never kept. A Calendar that was left on today comes back on whatever day is today by then.
 * The day is kept together with the Stylist who selected it and is handed out once after a start,
 * so that a sign-in that follows, by whomever, starts on today.
 */
internal class SavedCalendarDay(private val state: SavedStateHandle) {
    private var taken = false

    /** The day [stylistId] had selected when the app was last saved, or null when the Calendar is to start on today. */
    fun take(stylistId: String): LocalDate? {
        val day = state.get<String>(DAY)
            ?.takeIf { !taken && state.get<String>(STYLIST) == stylistId }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        taken = true
        if (day == null) {
            keep(stylistId, null)
        }

        return day
    }

    /** [day] is the day now selected, or null when it is today. */
    fun keep(stylistId: String, day: LocalDate?) {
        if (day == null) {
            state.remove<String>(DAY)
            state.remove<String>(STYLIST)
        } else {
            state[DAY] = day.toString()
            state[STYLIST] = stylistId
        }
    }

    private companion object {
        const val DAY = "calendarDay"
        const val STYLIST = "calendarDayStylist"
    }
}
