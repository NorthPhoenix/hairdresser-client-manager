package com.northphoenix.hairdresserclientmanager.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import java.util.Locale

// UI copy lives in res/values/strings.xml (English) and res/values-ru/strings.xml (Russian).
// The app language is the Stylist's saved setting, applied through Android's per-app
// language support so resources, pickers and date formats all switch together.

fun Language.toLocale(): Locale = if (this == Language.RU) Locale.forLanguageTag("ru-RU") else Locale.US

/** Russian is the primary language; anything that is not English falls back to it (ADR 0005). */
fun normalizeLanguage(tag: String?): Language =
    if (tag?.lowercase()?.startsWith("en") == true) Language.EN else Language.RU

/** The device's own language, ignoring any per-app language already applied. */
fun deviceLanguage(): Language = normalizeLanguage(Resources.getSystem().configuration.locales[0].toLanguageTag())

object AppLanguage {
    /** The language the app is showing right now. */
    fun current(context: Context): Language = normalizeLanguage(context.resources.configuration.locales[0].toLanguageTag())

    /** Switches the whole app to [language] and remembers it across restarts. */
    fun apply(language: Language) {
        val locales = LocaleListCompat.forLanguageTags(language.tag)

        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

/**
 * Resources in a specific language regardless of the app language. Client-facing text,
 * such as a Client Reminder, is written in the Client's language rather than the Stylist's.
 */
fun Context.inLanguage(language: Language): Context =
    createConfigurationContext(Configuration(resources.configuration).apply { setLocale(language.toLocale()) })

@get:StringRes
val AppointmentStatus.label: Int
    get() = when (this) {
        AppointmentStatus.SCHEDULED -> R.string.appointment_status_scheduled
        AppointmentStatus.COMPLETED -> R.string.appointment_status_completed
        AppointmentStatus.CANCELED -> R.string.appointment_status_canceled
        AppointmentStatus.NO_SHOW -> R.string.appointment_status_no_show
    }

@get:StringRes
val PhotoCategory.label: Int
    get() = when (this) {
        PhotoCategory.BEFORE -> R.string.appointment_photo_before
        PhotoCategory.AFTER -> R.string.appointment_photo_after
        PhotoCategory.OTHER -> R.string.appointment_photo_other
    }

@get:StringRes
val LocationType.label: Int
    get() = when (this) {
        LocationType.IN_SALON -> R.string.appointment_in_salon
        LocationType.AT_HOME -> R.string.appointment_at_home
    }

/** Each language is always named in itself, so it can be found from either language. */
@get:StringRes
val Language.label: Int
    get() = if (this == Language.RU) R.string.language_russian else R.string.language_english
