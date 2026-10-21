package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.Instant

/** The Home View's 'Tomorrow': the Appointments the Stylist can still remind her Clients of. */
object HomeTomorrow {
    /**
     * The Scheduled Appointments of [day]'s list, earliest first. A canceled, completed or
     * no-show Appointment is not shown: there is nothing to remind anyone of.
     */
    fun scheduled(day: List<Appointment>): List<Appointment> =
        day.filter { it.status == AppointmentStatus.SCHEDULED }.sortedBy { Instant.parse(it.startsAt) }
}
