package com.northphoenix.hairdresserclientmanager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.i18n.clientReminderTemplate
import com.northphoenix.hairdresserclientmanager.i18n.inLanguage
import com.northphoenix.hairdresserclientmanager.i18n.toLocale
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import java.time.ZoneId

/**
 * Opens the SMS app with a reminder in the primary Client's language. Nothing is sent automatically.
 * The Appointment screen's button and the Home View's 'Remind' button both come here.
 */
fun composeClientReminder(context: Context, viewModel: AppViewModel, appointment: Appointment, stylistZone: ZoneId) {
    val recipient = appointment.participants.firstOrNull { it.isPrimary }
    val number = recipient?.let { ClientReminder.recipient(it.phone) }

    if (recipient == null || number == null) {
        // No primary Client among the Appointment's Clients is not a Client without a phone.
        viewModel.notify(if (recipient == null) R.string.client_reminder_no_primary_client else R.string.client_reminder_missing_phone, isError = true)
        return
    }

    // Written in the Client's language, whatever language the app is in.
    val template = context.inLanguage(recipient.language).getString(clientReminderTemplate(appointment))
    val message = ClientReminder.message(template, appointment, recipient.language.toLocale(), stylistZone)

    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, ClientReminder.smsUri(number)).putExtra("sms_body", message))
    } catch (_: ActivityNotFoundException) {
        viewModel.notify(R.string.sms_app_unavailable, isError = true)
    }
}
