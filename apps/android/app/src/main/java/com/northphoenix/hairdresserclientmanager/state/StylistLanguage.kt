package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.i18n.AppLanguageSetting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides which language the app is in, so that the screen, the language selector and what is
 * saved are always the same language. The app is shown in Android's per-app language
 * ([AppLanguageSetting]); this class is the only one that sets it.
 *
 * - An onboarded Stylist's language is the saved one, on every device. A language the Stylist
 *   chooses, in the app's selector or in Android's settings, is shown at once and saved; when it
 *   cannot be saved the app goes back to the saved one.
 * - During Stylist Onboarding nothing is saved yet. The language is the one chosen for the app on
 *   this device, which Android keeps across restarts, and until one is chosen the one the Stylist
 *   was created with. It is saved with the first settings.
 *
 * Not thread-safe: use it from one thread.
 */
internal class StylistLanguage(private val setting: AppLanguageSetting) {
    private val changes = LatestWins<Unit, Language>()

    /**
     * Brings the app's language in line with [stylist]. Call it whenever either may be another
     * one: the Stylist was loaded or saved, or the app's language changed.
     *
     * Returns a language the Stylist chose in Android's settings, which is to be handled like one
     * chosen in the app ([choose], then [save]); null when there is none.
     */
    fun follow(stylist: Stylist): Language? {
        // A choice is being saved. The app already shows it, and how its save ends decides what stays.
        if (changes.pending(Unit) != null) {
            return null
        }
        if (!stylist.onboardingComplete) {
            setting.choose(setting.chosen() ?: stylist.language)
            return null
        }

        chosenInAndroid()?.let { return it }
        setting.choose(stylist.language)

        return null
    }

    /**
     * The language chosen in Android's settings since the app last chose one itself: the per-app
     * language is no longer the one the app left there. 'System default' is a choice of the
     * device's language. Before the app has chosen a language on this device there is nothing to
     * compare with, and the saved language wins.
     */
    private fun chosenInAndroid(): Language? {
        val byApp = setting.lastChosenByApp() ?: return null

        return (setting.chosen() ?: setting.device()).takeIf { it != byApp }
    }

    /**
     * The Stylist chose [language]: the app shows it at once. True when the caller has to start
     * [save]; false when there is nothing to save, or a [save] that is running will pick it up.
     * [stylist] is the Stylist as last loaded or saved.
     */
    fun choose(stylist: Stylist, language: Language): Boolean {
        setting.choose(language)

        return stylist.onboardingComplete && changes.offer(Unit, language, saved = stylist.language)
    }

    /**
     * Saves what was chosen: one [request] at a time, ending on the language chosen last (see
     * [LatestWins]). [request] returns once the server has confirmed its language. When one fails,
     * the app goes back to [saved], the language the server is known to have, and the failure is
     * thrown on: the app never stays in a language that is not saved.
     */
    suspend fun save(saved: () -> Language?, request: suspend (Language) -> Unit) {
        try {
            changes.send(Unit, request)
        } catch (error: Exception) {
            saved()?.let(setting::choose)
            throw error
        }
    }

    /** The language to send with a save of the other settings. During onboarding it is the one the screen is in. */
    fun toSave(stylist: Stylist): Language = if (stylist.onboardingComplete) stylist.language else setting.chosen() ?: stylist.language
}

/**
 * Saves one setting on top of the others as they are saved now, for a change that the Stylist
 * made to that setting alone: the language switch.
 *
 * [save] sends the change together with the other settings and the version of the Stylist it is
 * given, first [known], the settings as this device has them. When the server refuses that
 * because they were saved elsewhere since ([ApiException.isChangedElsewhere]), they are loaded
 * again with [load] and the change is sent once more with those: what another device saved stays,
 * and the Stylist has nothing to decide. A second refusal, and any other failure, is thrown.
 */
internal suspend fun <T> onLatestSettings(known: Stylist, load: suspend () -> Stylist, save: suspend (Stylist) -> T): T =
    try {
        save(known)
    } catch (error: ApiException) {
        if (!error.isChangedElsewhere) {
            throw error
        }

        save(load())
    }

/**
 * Keeps the app's requests about the Stylist's settings from overtaking each other.
 *
 * A save sends all the settings, the language switch as well as the Settings form, so saves go
 * out one at a time: each can be built from what the one before it left. A copy that was fetched
 * is used only when no save ran while it was on its way, because the reply to that save is newer.
 *
 * Not thread-safe: use it from one thread.
 */
internal class SettingsRequests {
    private val saving = Mutex()
    private var saves = 0

    /**
     * Runs [request] once every save started before it has ended. Build the request inside it, and
     * put what the server answered in place inside it as well: the save that waits behind this one
     * can have its turn before the caller of this one runs again.
     */
    suspend fun <T> save(request: suspend () -> T): T {
        saves++

        return saving.withLock { request() }
    }

    /** What [request] fetched, or null when a save was running or was started in the meantime. */
    suspend fun <T : Any> fetch(request: suspend () -> T): T? {
        if (saving.isLocked) {
            return null
        }

        val before = saves

        return request().takeIf { before == saves }
    }
}
