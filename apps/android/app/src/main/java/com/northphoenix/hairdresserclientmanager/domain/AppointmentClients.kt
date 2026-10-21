package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.ClientProfile

/**
 * The rules about the Clients of an Appointment that the app applies to its own copy before it
 * asks the server, which decides again: an Appointment has one or more Clients, its primary Client
 * is one of them, and a Client leaves it only when none of their Services and photos are in it.
 */
object AppointmentClients {
    /**
     * An Appointment whose primary Client is not one of its Clients. The server no longer lets one
     * come about, but those that two changes at the same moment left behind earlier are still
     * there, until the Stylist puts them right.
     */
    enum class Fault {
        /** No Clients at all. */
        NO_CLIENTS,

        /** Clients, and none of them is the primary one. */
        NO_PRIMARY,
    }

    fun fault(appointment: Appointment): Fault? =
        when {
            appointment.participants.isEmpty() -> Fault.NO_CLIENTS
            appointment.participants.none { it.isPrimary } -> Fault.NO_PRIMARY
            else -> null
        }

    /** What a Client still has in an Appointment, which keeps them from being removed from it. */
    enum class Kept {
        SERVICES,
        PHOTOS,

        /** Photos that are not on the server yet, on their way or failed to upload. Only the app knows them. */
        UNSAVED_PHOTOS,
    }

    /** Why [clientId] cannot be removed from [appointment] yet, in the order the server checks. Null when nothing keeps them. */
    fun keptBy(appointment: Appointment, clientId: String, hasUnsavedPhotos: Boolean): Kept? =
        when {
            appointment.services.any { it.clientId == clientId } -> Kept.SERVICES
            appointment.photos.any { it.clientId == clientId } -> Kept.PHOTOS
            hasUnsavedPhotos -> Kept.UNSAVED_PHOTOS
            else -> null
        }

    /**
     * The Client a new Service is for: the one the Stylist [picked], for as long as they are one of
     * the Appointment's Clients, and otherwise the primary Client. An Appointment without a primary
     * among its Clients (see [Fault]) offers its first Client.
     */
    fun serviceClientId(appointment: Appointment, picked: String?): String {
        val clients = appointment.participants

        return (clients.firstOrNull { it.clientId == picked } ?: clients.firstOrNull { it.isPrimary } ?: clients.firstOrNull())?.clientId
            ?: appointment.primaryClientId
    }

    /**
     * The Services of one Client of an Appointment, as the Appointment screen lists them when it
     * has several Clients. [name] is empty only for Services of a Client who is no longer one of
     * the Appointment's Clients and was not its primary Client either (see [Fault]).
     */
    data class ClientServices(val clientId: String, val name: String, val subtotalCents: Long, val services: List<AppointmentService>)

    /**
     * The Services of [appointment] by Client: one entry per Client, with the primary Client first
     * and the others in the order they were added, each with their Services in the order they were
     * added, also a Client without any. Services of someone who is not among the Clients any more
     * come last, by whom they are for, so that none of them is left out.
     */
    fun servicesByClient(appointment: Appointment): List<ClientServices> {
        val clients = appointment.participants.sortedByDescending { it.isPrimary }
        val listed = clients.mapTo(HashSet()) { it.clientId }
        val ofClients = clients.map { client ->
            val services = appointment.services.filter { it.clientId == client.clientId }

            ClientServices(client.clientId, client.name, services.sumOf { it.priceCents }, services)
        }
        val ofOthers = appointment.services
            .filter { it.clientId !in listed }
            .groupBy { it.clientId }
            .map { (clientId, services) ->
                val name = if (clientId == appointment.primaryClientId) appointment.primaryClientName else ""

                ClientServices(clientId, name, services.sumOf { it.priceCents }, services)
            }

        return ofClients + ofOthers
    }

    /** Whether [clientId] is one of the Clients of [appointment] already: picking them adds nobody. */
    fun isIn(appointment: Appointment, clientId: String): Boolean = appointment.participants.any { it.clientId == clientId }

    /**
     * The name an Appointment goes by where there is room for one: its primary Client's. An
     * Appointment without a primary among its Clients (see [Fault]) goes by its first Client, not
     * by someone who is no longer in it.
     */
    fun leadName(appointment: Appointment): String =
        (appointment.participants.firstOrNull { it.isPrimary } ?: appointment.participants.firstOrNull())?.name ?: appointment.primaryClientName

    /** How many Clients an Appointment has besides the one it goes by ([leadName]). */
    fun othersCount(appointment: Appointment): Int = (appointment.participants.size - 1).coerceAtLeast(0)

    /**
     * The Clients picked for a new Appointment in the order they were picked, whatever order the
     * Clients list is in: the Appointment lists its Clients in the order they were added.
     */
    fun inPickedOrder(pickedIds: List<String>, clients: List<ClientProfile>): List<ClientProfile> =
        pickedIds.mapNotNull { id -> clients.firstOrNull { it.id == id } }

    /**
     * Of the Clients picked for a new Appointment, those the loaded Clients list still has. One
     * who was deleted on another device since is no Client any more, and the server refuses an
     * Appointment that names them for as long as it does.
     */
    fun stillListed(pickedIds: List<String>, clients: List<ClientProfile>): List<String> {
        val listed = clients.mapTo(HashSet()) { it.id }

        return pickedIds.filter { it in listed }
    }

    /**
     * The Appointments that deleting the Client [clientId] changes or deletes: those the Client
     * is in, and those that name them as primary without listing them (see [Fault]), which the
     * server puts right as part of the delete.
     */
    fun changedByDeleting(clientId: String, appointments: Collection<Appointment>): List<Appointment> =
        appointments.filter { appointment -> appointment.primaryClientId == clientId || appointment.participants.any { it.clientId == clientId } }

    /** Whether one of those Appointments is deleted together with the Client: nobody else is in it. */
    fun goesWith(clientId: String, appointment: Appointment): Boolean = appointment.participants.all { it.clientId == clientId }
}
