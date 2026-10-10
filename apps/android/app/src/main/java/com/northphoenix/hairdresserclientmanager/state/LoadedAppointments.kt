package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.toApiException
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every Appointment loaded so far, by id, so that a detail screen can render whichever list its
 * Appointment came from. For an Appointment that was asked for and is not here, [absent] says why:
 * [LoadState.Loading], [LoadState.Failed] or [LoadState.Missing]. The screen never has to guess.
 *
 * An Appointment leaves [loaded] only when the server no longer has it.
 * Not thread-safe: use it from one thread.
 */
internal class LoadedAppointments {
    private val _loaded = MutableStateFlow<Map<String, Appointment>>(emptyMap())
    val loaded: StateFlow<Map<String, Appointment>> = _loaded.asStateFlow()

    private val _absent = MutableStateFlow<Map<String, LoadState>>(emptyMap())
    val absent: StateFlow<Map<String, LoadState>> = _absent.asStateFlow()

    // Per Appointment, how many changes the server has confirmed. An answer that was asked for
    // before the latest of them may not show it.
    private val confirmed = mutableMapOf<String, Int>()
    private var cleared = 0

    operator fun get(id: String): Appointment? = _loaded.value[id]

    /** Keeps Appointments that arrived with a list. */
    fun remember(appointments: Collection<Appointment>) {
        _loaded.update { it + appointments.associateBy(Appointment::id) }
        _absent.update { it - appointments.map(Appointment::id).toSet() }
    }

    /** Applies a change the server has just accepted to the loaded copy. */
    fun update(id: String, change: (Appointment) -> Appointment) {
        val current = _loaded.value[id] ?: return

        _loaded.update { it + (id to change(current)) }
        changed(id)
    }

    /** Applies what the server has just confirmed about something Appointments share, such as a Client's phone number. */
    fun updateAll(change: (Appointment) -> Appointment) {
        _loaded.value.values.forEach { current ->
            val changed = change(current)

            if (changed != current) {
                update(current.id) { changed }
            }
        }
    }

    /** The server has just accepted a change to this Appointment that is not applied here: only an answer asked for from now on shows it. */
    fun changed(id: String) {
        confirmed[id] = (confirmed[id] ?: 0) + 1
    }

    /** The Stylist has just deleted this Appointment. */
    fun forget(id: String) {
        changed(id)
        gone(id)
    }

    /** Starts over for another Stylist. Whatever is still on its way for the previous one is dropped. */
    fun clear() {
        cleared++
        confirmed.clear()
        _loaded.value = emptyMap()
        _absent.value = emptyMap()
    }

    /**
     * Looks one Appointment up again. [find] answers null when the server has no such Appointment.
     * A copy on screen stays there while this runs and when it fails. A failure with nothing to
     * show is kept in [absent]. Either way it is thrown on for the caller to report.
     */
    suspend fun reload(id: String, find: suspend () -> Appointment?) {
        val session = cleared

        // An Appointment known to be gone stays "gone" while it is looked up again.
        if (id !in _loaded.value && _absent.value[id] != LoadState.Missing) {
            _absent.update { it + (id to LoadState.Loading) }
        }

        try {
            var found: Appointment?

            // Asked again when a change was confirmed while the answer was on its way.
            do {
                val asked = confirmed[id]

                found = find()
                if (session != cleared) {
                    return
                }
            } while (confirmed[id] != asked)

            if (found != null) remember(listOf(found)) else gone(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (session != cleared) {
                return
            }
            if (id !in _loaded.value && _absent.value[id] != LoadState.Missing) {
                _absent.update { it + (id to LoadState.Failed(error.toApiException().messageRes())) }
            }
            throw error
        }
    }

    private fun gone(id: String) {
        _loaded.update { it - id }
        _absent.update { it + (id to LoadState.Missing) }
    }
}
