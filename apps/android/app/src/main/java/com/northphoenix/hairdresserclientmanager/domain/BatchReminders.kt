package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.Language
import java.time.Instant

/** One Client Reminder ready to go: [number] is what the SMS is sent to, [text] is exactly what it says. */
data class ReminderMessage(
    val appointmentId: String,
    val startsAt: String,
    val recipientName: String,
    val phone: String,
    val number: String,
    val language: Language,
    val text: String,
)

/** 'Remind everyone': which of the day's Appointments the sheet lists, and which of them it starts with checked. */
object BatchReminders {
    /** The Appointments of [day] a Client Reminder can be sent for in one go: the ones the card's own button is offered for. */
    fun remindable(day: List<Appointment>, now: Instant): List<Appointment> = day.filter { ClientReminder.canRemindInOneTap(it, now) }

    /**
     * The Appointments the sheet opens with checked: all but those already reminded for their current
     * time, so a second go does not send the same reminder again unless the Stylist checks it.
     */
    fun initiallyChecked(day: List<Appointment>, log: Map<String, Reminded>): Set<String> =
        day.filter { ReminderLog.remindedAt(log, it) == null }.map { it.id }.toSet()
}
