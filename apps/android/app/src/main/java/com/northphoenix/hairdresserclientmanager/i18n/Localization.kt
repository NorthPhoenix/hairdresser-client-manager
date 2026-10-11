package com.northphoenix.hairdresserclientmanager.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.ShareChoice
import java.util.Locale

// UI copy lives in res/values/strings.xml (English) and res/values-ru/strings.xml (Russian).
// The app language is Android's per-app language, so resources, pickers and date formats all
// switch together. Which language that is, is decided in one place: state/StylistLanguage.kt.

fun Language.toLocale(): Locale = if (this == Language.RU) Locale.forLanguageTag("ru-RU") else Locale.US

/** Russian is the primary language; anything that is not English falls back to it (ADR 0005). */
fun normalizeLanguage(tag: String?): Language =
    if (tag?.lowercase()?.startsWith("en") == true) Language.EN else Language.RU

/**
 * The language of the strings Android shows for a locale. English is the default set of strings,
 * so every locale but a Russian one is shown in English. This is what is on screen, which is not
 * always what [normalizeLanguage] would choose: a German locale is shown in English until the app
 * has chosen Russian for it.
 */
fun stringsLanguage(tag: String?): Language =
    if (tag != null && Locale.forLanguageTag(tag).language == "ru") Language.RU else Language.EN

/**
 * The language the strings of this context are in. The language selector shows this one, taken
 * from the context its own labels come from, so the selected option and the screen cannot disagree.
 */
fun Context.shownLanguage(): Language = stringsLanguage(resources.configuration.locales[0].toLanguageTag())

/**
 * What Android and this device keep about the app's language, as language tags.
 * [AppLanguageSetting] gives them their meaning.
 */
interface AppLocales {
    /** Android's per-app language. Null when none is chosen for the app, which then follows the device. */
    var app: String?

    /** The device's own language. */
    val device: String

    /** The language the app itself chose last. Null when it has not chosen one on this device yet. */
    var chosenByApp: String?
}

/**
 * Android's per-app language: the language the app is shown in. Android keeps it across restarts,
 * and the Stylist can change it in two places, the app's own selector and Android's settings.
 */
class AppLanguageSetting(private val locales: AppLocales) {
    /** The language chosen for the app, here or in Android's settings. Null when none is, and the app follows the device. */
    fun chosen(): Language? = locales.app?.let(::normalizeLanguage)

    /** The device's own language, which is also what 'System default' in Android's settings chooses. */
    fun device(): Language = normalizeLanguage(locales.device)

    /** The language the app itself chose last. Another [chosen] than this one was chosen in Android's settings. */
    fun lastChosenByApp(): Language? = locales.chosenByApp?.let(::normalizeLanguage)

    /** Switches the whole app to [language] and remembers that the app chose it. */
    fun choose(language: Language) {
        // A locale chosen in Android's settings can name a region, such as 'English (United Kingdom)'.
        // It is left as it is when it is this language: shown in it, and read as it by [chosen].
        val app = locales.app

        if (app == null || stringsLanguage(app) != language || normalizeLanguage(app) != language) {
            locales.app = language.tag
        }
        // After the language itself: if the app is killed in between, the new language counts as
        // chosen in Android's settings and gets saved, instead of the old one being put back.
        locales.chosenByApp = language.tag
    }
}

class AndroidAppLocales(context: Context) : AppLocales {
    // Android has its own language settings per app since Android 13, and it is asked directly: AppCompat,
    // which keeps the language on earlier versions, answers "none" there whenever no Activity is around.
    private val localeManager: LocaleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.getSystemService(LocaleManager::class.java) else null

    // Not the Stylist's setting, which is saved on the server (ADR 0009): only what this device
    // needs to tell its own choice from one made in Android's settings.
    private val memory = context.getSharedPreferences("app_language", Context.MODE_PRIVATE)

    override var app: String?
        get() =
            if (localeManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                localeManager.applicationLocales[0]?.toLanguageTag()
            } else {
                AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()
            }
        set(value) {
            if (localeManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                localeManager.applicationLocales = LocaleList.forLanguageTags(value.orEmpty())
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value))
            }
        }

    override val device: String
        get() = Resources.getSystem().configuration.locales[0].toLanguageTag()

    // Only where Android has such settings. Elsewhere nobody but the app chooses its language, so
    // there is nothing to tell apart, and a note that outlived an update of Android would mislead.
    override var chosenByApp: String?
        get() = if (localeManager != null) memory.getString(CHOSEN_BY_APP, null) else null
        set(value) {
            if (localeManager != null) {
                memory.edit().putString(CHOSEN_BY_APP, value).apply()
            }
        }

    private companion object {
        const val CHOSEN_BY_APP = "chosenByApp"
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
val ShareChoice.label: Int
    get() = when (this) {
        ShareChoice.COLOR_FORMULAS -> R.string.profile_share_include_color_formulas
        ShareChoice.AFTER_PHOTOS -> R.string.profile_share_include_after_photos
        ShareChoice.OTHER_PHOTOS -> R.string.profile_share_include_other_photos
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

/**
 * The Client Reminder text to fill in. It gives the address when the Appointment has one; without
 * one it names the kind of place in a sentence of its own, where "Address: In salon" would not read.
 */
@StringRes
fun clientReminderTemplate(appointment: Appointment): Int = when {
    appointment.locationAddress.isNotBlank() -> R.string.client_reminder_message
    appointment.locationType == LocationType.AT_HOME -> R.string.client_reminder_message_at_home
    else -> R.string.client_reminder_message_in_salon
}

/**
 * What a Client picker with nothing to list says: the Stylist has no Clients, every one of them
 * is in the Appointment already (whatever is searched for), or none of the [offered] ones matches the search.
 */
@StringRes
fun emptyClientPickerText(clients: Int, offered: Int): Int = when {
    clients == 0 -> R.string.no_clients_title
    offered == 0 -> R.string.all_clients_in_appointment
    else -> R.string.no_client_results
}
