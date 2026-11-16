package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppointmentService

/**
 * Services by name on one line, as an Appointment card, the event of the Calendar Integration and
 * the sources Appointment Copying offers show them: in the order they were added, each name once,
 * with how many there are when there are more ("Стрижка ×2, Укладка"), as when one Service Menu
 * Item was added for two people under one Client (two children's haircuts). A blank name is left out.
 *
 * On screen the count is joined to its name by a no-break space ([beforeCount]), so a wrapped line
 * never starts with it.
 */
object ServiceNames {
    fun line(services: List<AppointmentService>, beforeCount: String = "\u00A0"): String =
        services
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .entries
            .joinToString(", ") { (name, count) -> if (count == 1) name else "$name$beforeCount×$count" }
}
