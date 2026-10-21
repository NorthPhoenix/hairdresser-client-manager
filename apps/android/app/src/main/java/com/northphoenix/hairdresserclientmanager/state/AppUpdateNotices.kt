package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.AppReleases
import com.northphoenix.hairdresserclientmanager.data.toApiException
import com.northphoenix.hairdresserclientmanager.domain.AppUpdates
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What this device remembers about app updates. It is about the app on this device, not about a Stylist, so a sign-out keeps it. */
interface AppUpdateStore {
    /** The version whose "What's new" counts as seen; null before the first version that keeps it. */
    var lastSeenVersionCode: Int?

    /** The version the Stylist said "Later" to. */
    var dismissedVersionCode: Int?
}

/**
 * The update offer and "What's new" of Home, and the release list of the Profile tab, for the app
 * running as [current]. [updated] says whether this install was an update of an earlier one
 * (only asked while no version is stored as seen). A failure to load the releases shows nothing
 * on Home and is never reported: the app works as before without them.
 */
class AppUpdateNotices(
    private val store: AppUpdateStore,
    private val current: Int,
    private val updated: () -> Boolean,
    scope: CoroutineScope,
) {
    private val _releases = MutableStateFlow(Read<AppReleases>())
    val releases: StateFlow<Read<AppReleases>> = _releases.asStateFlow()

    private val lastSeen = MutableStateFlow(store.lastSeenVersionCode)
    private val dismissed = MutableStateFlow(store.dismissedVersionCode)
    private val wasUpdated by lazy(updated)

    /** The card at the top of Home, if any. */
    val homeCard: StateFlow<AppUpdates.HomeCard?> =
        combine(_releases, lastSeen, dismissed) { read, seen, later ->
            AppUpdates.homeCard(
                AppUpdates.whatsNew(read.data, current, seen, seen == null && wasUpdated),
                AppUpdates.offer(read.data, current, later),
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The newer version, if any, whether or not the Stylist said "Later" to it: the Profile tab names it anyway. */
    val newer: StateFlow<AppUpdates.Offer?> =
        _releases.map { AppUpdates.offer(it.data, current, dismissed = null) }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        settle()
    }

    /** Asks the server for the releases. What was loaded before stays when this fails. */
    suspend fun load(fetch: suspend () -> AppReleases) {
        if (_releases.value.data == null) {
            _releases.value = Read(LoadState.Loading)
        }

        try {
            _releases.value = Read(LoadState.Loaded, fetch())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (_releases.value.data == null) {
                _releases.value = Read(LoadState.Failed(error.toApiException().messageRes()))
            }
        }

        settle()
    }

    /** "Got it" on "What's new": this version's notes count as seen. */
    fun acknowledge() = seen(current)

    /** "Later" on the update offer: not offered again until a newer version than [versionCode] is out. */
    fun later(versionCode: Int) {
        store.dismissedVersionCode = versionCode
        dismissed.value = versionCode
    }

    /** When there is nothing to show for this version (a fresh install, no notes), it counts as seen at once. */
    private fun settle() {
        val seen = lastSeen.value
        val whatsNew = AppUpdates.whatsNew(_releases.value.data, current, seen, seen == null && wasUpdated)

        if (whatsNew == AppUpdates.WhatsNew.Nothing && seen != current) {
            seen(current)
        }
    }

    private fun seen(versionCode: Int) {
        store.lastSeenVersionCode = versionCode
        lastSeen.value = versionCode
    }
}
