package com.northphoenix.hairdresserclientmanager.i18n

import android.content.Context
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.domain.MirrorWords

/** The words of the mirrored events' descriptions in [language], the Stylist's: the calendar is hers. */
fun Context.mirrorWords(language: Language): MirrorWords {
    val resources = inLanguage(language).resources

    return object : MirrorWords {
        override fun services(names: String): String = resources.getString(R.string.calendar_event_services, names)

        override fun clients(names: String): String = resources.getString(R.string.calendar_event_clients, names)

        override fun phone(number: String): String = resources.getString(R.string.calendar_event_phone, number)

        override val footer: String = resources.getString(R.string.calendar_event_footer, resources.getString(R.string.app_name))
    }
}
