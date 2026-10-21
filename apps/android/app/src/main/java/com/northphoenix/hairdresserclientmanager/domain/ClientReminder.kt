package com.northphoenix.hairdresserclientmanager.domain

import android.net.Uri
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The manual Client Reminder SMS: when it is offered, whom it goes to and what it says. The wording
 * is the `client_reminder_message…` strings; `clientReminderTemplate` (i18n) picks the one to fill in.
 */
object ClientReminder {
    private val spaces = Regex("\\p{Zs}")
    private val dashes = Regex("[‐-―−]")
    private val singleQuotes = Regex("[‘-‛]")
    private val doubleQuotes = Regex("[“-‟«»]")

    /**
     * A Client Reminder is a message before an Appointment, and its text says the Appointment is
     * scheduled. Neither is true of a completed, canceled or no-show Appointment, nor of one whose
     * start time has come by [now]: it is too late to remind anyone of it.
     */
    fun isOffered(appointment: Appointment, now: Instant): Boolean =
        appointment.status == AppointmentStatus.SCHEDULED && Instant.parse(appointment.startsAt).isAfter(now)

    /**
     * Whether a button that composes the reminder in one tap is worth showing on [appointment]: it
     * is offered at [now], and its primary Client has a number to send to. The Appointment screen shows the
     * button anyway and says what is missing; a list of cards has no room for that.
     */
    fun canRemindInOneTap(appointment: Appointment, now: Instant): Boolean =
        isOffered(appointment, now) && appointment.participants.firstOrNull { it.isPrimary }?.let { recipient(it.phone) } != null

    /**
     * The number an SMS to [phone] goes to: its digits and '+'. Null when there is no digit to
     * dial, so a phone such as "-" or "n/a" counts as no phone.
     */
    fun recipient(phone: String): String? = phone.filter { it.isDigit() || it == '+' }.takeIf { it.any(Char::isDigit) }

    fun smsUri(recipient: String): Uri = Uri.parse("smsto:" + Uri.encode(recipient))

    /**
     * The reminder text: [template] in the Client's language ([locale]), filled in with the day and
     * time of [appointment] in the Stylist's [zone] and, where the template names it, the address.
     */
    fun message(template: String, appointment: Appointment, locale: Locale, zone: ZoneId): String {
        val text = template.format(locale, TimeFormat(locale, zone).dateAndTimeRange(appointment), appointment.locationAddress)

        // Russian is sent as a Unicode SMS whatever its spaces and dashes are.
        return if (locale.language == "en") plain(text) else text
    }

    /**
     * [text] with the spaces, dashes and quotes of the plain SMS alphabet. A single character from
     * outside that alphabet turns the whole message into a Unicode SMS, which holds 70 characters
     * instead of 160: the narrow no-break space Android writes before "AM", or the dash of "9:00 – 10:30".
     */
    private fun plain(text: String): String =
        text.replace(spaces, " ").replace(dashes, "-").replace(singleQuotes, "'").replace(doubleQuotes, "\"")
}
