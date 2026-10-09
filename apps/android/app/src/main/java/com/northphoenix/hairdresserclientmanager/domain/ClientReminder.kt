package com.northphoenix.hairdresserclientmanager.domain

import android.net.Uri

/** Addressing for the manual Client Reminder SMS. The text itself is the `client_reminder_message` string. */
object ClientReminder {
    fun recipient(phone: String): String = phone.trim().filter { it.isDigit() || it == '+' }

    fun smsUri(phone: String): Uri = Uri.parse("smsto:" + Uri.encode(recipient(phone)))
}
