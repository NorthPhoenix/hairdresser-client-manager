package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppRelease
import com.northphoenix.hairdresserclientmanager.data.AppReleases
import com.northphoenix.hairdresserclientmanager.data.Language

/**
 * App Update and Release Notes (CONTEXT.md): when Home offers a newer version of the app, and when
 * it shows what the last update changed ("Что нового"). The app knows its own version
 * (`BuildConfig.VERSION_CODE`); the server knows the latest release and the notes of every release.
 */
object AppUpdates {
    /** How many lines of notes a Home card shows before "more…". */
    const val COLLAPSED_LINES = 3

    /** A newer version than the one running, with the releases it is newer by, newest first. */
    data class Offer(val versionCode: Int, val versionName: String, val releases: List<AppRelease>, val downloadUrl: String?)

    /**
     * The update Home offers: the latest release when it is newer than [current] and the Stylist
     * has not said "Later" to that version ([dismissed]). "Later" holds for that version only: the
     * next one is offered again. Null without the releases (not loaded, offline).
     */
    fun offer(releases: AppReleases?, current: Int, dismissed: Int?): Offer? {
        val latest = releases?.latest ?: return null

        if (latest.versionCode <= current || latest.versionCode == dismissed) {
            return null
        }

        return Offer(
            versionCode = latest.versionCode,
            versionName = latest.versionName,
            releases = releases.releases.filter { it.versionCode > current }.sortedByDescending { it.versionCode },
            downloadUrl = releases.downloadUrl?.takeIf { it.isNotBlank() },
        )
    }

    /** Whether "What's new" is due, and what it shows. */
    sealed interface WhatsNew {
        /** Nothing to show for this version: it counts as seen. */
        data object Nothing : WhatsNew

        /** Not known yet: the releases are not loaded, or the server does not know this version yet. Asked again on the next load. */
        data object Waiting : WhatsNew

        /** The releases the app was updated past, newest first. Shown until the Stylist taps "Got it". */
        data class Show(val releases: List<AppRelease>) : WhatsNew
    }

    /**
     * "What's new" after an update: the releases in ([lastSeen], [current]], where [lastSeen] is the
     * version that last counted as seen on this device. When none is stored yet, as on the first
     * start of the first version that keeps one, the app was either updated ([updated]: then the
     * notes of the [current] version are shown) or installed afresh (nothing is shown).
     */
    fun whatsNew(releases: AppReleases?, current: Int, lastSeen: Int?, updated: Boolean): WhatsNew {
        if ((lastSeen != null && lastSeen >= current) || (lastSeen == null && !updated)) {
            return WhatsNew.Nothing
        }

        if (releases == null) {
            return WhatsNew.Waiting
        }

        val after = lastSeen ?: (current - 1)
        val shown = releases.releases.filter { it.versionCode in (after + 1)..current }.sortedByDescending { it.versionCode }

        return when {
            shown.isNotEmpty() -> WhatsNew.Show(shown)
            // The server knows this version and has no notes for it: there is nothing to tell.
            releases.latest.versionCode >= current -> WhatsNew.Nothing
            // A server that has not been told of this release yet: its notes may come later.
            else -> WhatsNew.Waiting
        }
    }

    /** What Home shows at its top: at most one card, and "What's new" before an update offer. */
    sealed interface HomeCard {
        data class WhatsNew(val releases: List<AppRelease>) : HomeCard

        data class Update(val offer: Offer) : HomeCard
    }

    fun homeCard(whatsNew: WhatsNew, offer: Offer?): HomeCard? =
        when {
            whatsNew is WhatsNew.Show -> HomeCard.WhatsNew(whatsNew.releases)
            offer != null -> HomeCard.Update(offer)
            else -> null
        }

    /** The notes of [release] in [language], falling back to the other language when that one has none. */
    fun notes(release: AppRelease, language: Language): List<String> {
        val (wanted, other) = when (language) {
            Language.RU -> release.notes.ru to release.notes.en
            Language.EN -> release.notes.en to release.notes.ru
        }

        return wanted.ifEmpty { other }.map(String::trim).filter(String::isNotEmpty)
    }

    /** The lines of several releases one after the other, newest release first. */
    fun lines(releases: List<AppRelease>, language: Language): List<String> = releases.flatMap { notes(it, language) }

    /** The first [COLLAPSED_LINES] of [lines] and how many more there are, or all of them when [expanded]. */
    fun collapsed(lines: List<String>, expanded: Boolean): Pair<List<String>, Int> =
        if (expanded || lines.size <= COLLAPSED_LINES) lines to 0 else lines.take(COLLAPSED_LINES) to lines.size - COLLAPSED_LINES

    /** The versions a list of releases spans, oldest to newest ("1.2.0" or "1.2.0" to "1.4.0"). */
    fun versionSpan(releases: List<AppRelease>): Pair<String, String?> {
        val newest = releases.maxByOrNull { it.versionCode }?.versionName.orEmpty()
        val oldest = releases.minByOrNull { it.versionCode }?.versionName.orEmpty()

        return if (oldest == newest) newest to null else oldest to newest
    }
}
