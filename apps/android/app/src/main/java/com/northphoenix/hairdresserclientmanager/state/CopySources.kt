package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Appointment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What Appointment Copying can copy from: the Completed Appointments that have something to copy
 * into the Appointment whose copy sheet is open. The list is that Appointment's own, because a
 * copy takes only the Services of Clients attached to both. So a list is never shown for another
 * Appointment than the one it was loaded for, nor for Clients that Appointment no longer has:
 * [open] drops what was loaded, and whatever was still on its way.
 *
 * Not thread-safe: use it from one thread.
 */
internal class CopySources(private val fetch: suspend (targetAppointmentId: String) -> List<Appointment>) {
    private var targetId: String? = null
    private val loading = LoadTracker()
    private val _sources = MutableStateFlow<List<Appointment>>(emptyList())

    /** Newest first. The first is the source a copy takes when it names none. */
    val sources: StateFlow<List<Appointment>> = _sources.asStateFlow()

    /** How the load of [sources] stands. Loading while no copy sheet is open. */
    val state: StateFlow<LoadState> = loading.state

    /**
     * The copy sheet of [targetAppointmentId] has opened, or the Clients of that Appointment have
     * changed while it is open. The list starts over; the caller loads it with [load].
     */
    fun open(targetAppointmentId: String) {
        targetId = targetAppointmentId
        startOver()
    }

    /** The copy sheet of [targetAppointmentId] has closed. Nothing happens when another Appointment's sheet has opened since. */
    fun close(targetAppointmentId: String) {
        if (targetId == targetAppointmentId) {
            clear()
        }
    }

    /** No copy sheet is open any more, whichever was. */
    fun clear() {
        targetId = null
        startOver()
    }

    /** Loads the sources for the Appointment whose copy sheet is open. Does nothing while none is. */
    suspend fun load() {
        val target = targetId ?: return

        loading.load({ fetch(target) }) { _sources.value = it }
    }

    /** An Appointment the server has just deleted is no source any more. */
    fun forget(appointmentId: String) = _sources.update { sources -> sources.filterNot { it.id == appointmentId } }

    private fun startOver() {
        _sources.value = emptyList()
        loading.reset()
    }
}
