package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Appointment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The overlap warnings the Stylist has not dismissed, by the Appointment each is about. A warning
 * is what the server answered when that Appointment was created, moved or set back to Scheduled:
 * the other Scheduled Appointments at its time. It stays until the Stylist dismisses it or a later
 * answer about the same Appointment takes its place. Tested in `OverlapWarningsTest`.
 *
 * Not thread-safe: use it from one thread.
 */
internal class OverlapWarnings {
    private val _shown = MutableStateFlow<Map<String, List<Appointment>>>(emptyMap())
    val shown: StateFlow<Map<String, List<Appointment>>> = _shown.asStateFlow()

    /**
     * The server's answer to a change of the time or the status of [appointmentId]. It takes the
     * place of everything said about that Appointment before: of its own warning, which ends when
     * nothing overlaps it any more, and of its line in the warnings of other Appointments, which
     * was about the time and the status it had until now.
     */
    fun answered(appointmentId: String, overlapping: List<Appointment>) {
        _shown.update { shown ->
            val others = shown.without(appointmentId)

            if (overlapping.isEmpty()) others else others + (appointmentId to overlapping)
        }
    }

    /** The Appointment was deleted: it has no warning and overlaps nothing. */
    fun gone(appointmentId: String) = answered(appointmentId, emptyList())

    /**
     * A Client was deleted. An Appointment that had no other Client went with them and overlaps
     * nothing; one that remains is named by the Clients it still has. Worked out from the named
     * Appointments themselves: one on a day that is not loaded is known nowhere else in the app.
     */
    fun clientDeleted(clientId: String) {
        _shown.update { shown ->
            shown
                .mapValues { (_, overlapping) -> overlapping.mapNotNull { it.withoutClient(clientId) } }
                .filterValues { it.isNotEmpty() }
        }
    }

    /** The Stylist has read the warning of [appointmentId]. */
    fun dismiss(appointmentId: String) {
        _shown.update { it - appointmentId }
    }

    /** Starts over for another Stylist. */
    fun clear() {
        _shown.value = emptyMap()
    }

    /** Without the warning of [appointmentId] and without that Appointment in the warnings of the others. A warning that names nothing is none. */
    private fun Map<String, List<Appointment>>.without(appointmentId: String): Map<String, List<Appointment>> =
        (this - appointmentId)
            .mapValues { (_, overlapping) -> overlapping.filterNot { it.id == appointmentId } }
            .filterValues { it.isNotEmpty() }

    /** This Appointment after [clientId] was deleted, as the server leaves it: null when it had no other Client. */
    private fun Appointment.withoutClient(clientId: String): Appointment? {
        val others = participants.filterNot { it.clientId == clientId }

        return when {
            others.size == participants.size && primaryClientId != clientId -> this
            others.isEmpty() -> null
            else -> copy(participants = others)
        }
    }
}
