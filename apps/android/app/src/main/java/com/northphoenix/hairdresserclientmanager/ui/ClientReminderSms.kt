package com.northphoenix.hairdresserclientmanager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.ReminderMessage
import com.northphoenix.hairdresserclientmanager.i18n.clientReminderTemplate
import com.northphoenix.hairdresserclientmanager.i18n.inLanguage
import com.northphoenix.hairdresserclientmanager.i18n.toLocale
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import java.time.ZoneId

/**
 * The Client Reminder of [appointment] as it is sent: to the primary Client's number, in the Client's
 * language, whatever language the app is in. Null when the Appointment has no primary Client, or that Client has no number.
 */
fun clientReminderMessage(context: Context, appointment: Appointment, stylistZone: ZoneId): ReminderMessage? {
    val recipient = appointment.participants.firstOrNull { it.isPrimary } ?: return null
    val number = ClientReminder.recipient(recipient.phone) ?: return null
    val template = context.inLanguage(recipient.language).getString(clientReminderTemplate(appointment))

    return ReminderMessage(
        appointmentId = appointment.id,
        startsAt = appointment.startsAt,
        recipientName = recipient.name,
        phone = recipient.phone,
        number = number,
        language = recipient.language,
        text = ClientReminder.message(template, appointment, recipient.language.toLocale(), stylistZone),
    )
}

/** Opens the SMS app with [message] ready to send. False when the device has no SMS app. */
fun openInMessages(context: Context, message: ReminderMessage): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, ClientReminder.smsUri(message.number)).putExtra("sms_body", message.text))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/**
 * Opens the SMS app with a reminder in the primary Client's language. Nothing is sent automatically.
 * The Appointment screen's button and the Home View's 'Remind' button both come here; the Home View's
 * 'Remind everyone' sends from the phone instead. The Appointment counts as reminded from here on.
 */
fun composeClientReminder(context: Context, viewModel: AppViewModel, appointment: Appointment, stylistZone: ZoneId) {
    val message = clientReminderMessage(context, appointment, stylistZone)

    if (message == null) {
        // No primary Client among the Appointment's Clients is not a Client without a phone.
        val hasPrimary = appointment.participants.any { it.isPrimary }
        viewModel.notify(if (hasPrimary) R.string.client_reminder_missing_phone else R.string.client_reminder_no_primary_client, isError = true)
        return
    }

    if (openInMessages(context, message)) {
        viewModel.reminders.record(message)
    } else {
        viewModel.notify(R.string.sms_app_unavailable, isError = true)
    }
}
