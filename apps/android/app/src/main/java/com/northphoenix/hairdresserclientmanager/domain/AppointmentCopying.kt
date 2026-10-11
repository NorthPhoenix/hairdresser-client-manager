package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentService

/**
 * Appointment Copying copies the Services of the Clients attached to both Appointments, with
 * their Color Formulas. What a source of the copy sheet's list stands for is read here: the
 * server names the Clients a copy from it is about (`copyableClientIds`), and the app shows that
 * part of the source and not the whole of a group Appointment.
 */
object AppointmentCopying {
    /** The Services a copy from [source] adds, in the order they are added. */
    fun services(source: Appointment): List<AppointmentService> = source.services.filter { it.clientId in source.copyableClientIds }

    /**
     * The Clients whose Services a copy from [source] adds, by name, in the source's order. For a
     * source that names none, the name the Appointment goes by.
     */
    fun clientNames(source: Appointment): List<String> =
        source.participants.filter { it.clientId in source.copyableClientIds }.map { it.name }.ifEmpty { listOf(AppointmentClients.leadName(source)) }
}
