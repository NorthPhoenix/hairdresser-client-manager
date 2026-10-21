package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.AppRelease
import com.northphoenix.hairdresserclientmanager.data.AppReleases
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LatestRelease
import com.northphoenix.hairdresserclientmanager.data.ReleaseNotes
import com.northphoenix.hairdresserclientmanager.data.hcmJson
import com.northphoenix.hairdresserclientmanager.domain.AppUpdates
import com.northphoenix.hairdresserclientmanager.domain.AppUpdates.WhatsNew
import com.northphoenix.hairdresserclientmanager.state.AppUpdateNotices
import com.northphoenix.hairdresserclientmanager.state.AppUpdateStore
import com.northphoenix.hairdresserclientmanager.state.LoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App Update and Release Notes: when Home offers a newer version, and when it shows what an update changed. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private fun release(code: Int, ru: List<String> = listOf("ru $code"), en: List<String> = listOf("en $code")) =
        AppRelease(code, "1.${code - 2}.0", "2026-10-21", ReleaseNotes(ru, en))

    private fun releases(vararg codes: Int, downloadUrl: String? = "https://example.com/hcm.apk"): AppReleases {
        val all = codes.sortedDescending().map { release(it) }

        return AppReleases(LatestRelease(all.first().versionCode, all.first().versionName, all.first().date), all, downloadUrl)
    }

    // --- the update offer ---

    @Test
    fun offersALatestReleaseNewerThanTheRunningApp() {
        val offer = AppUpdates.offer(releases(3, 4, 5, 6, 7, 8), current = 6, dismissed = null)

        assertEquals(8, offer?.versionCode)
        assertEquals("1.6.0", offer?.versionName)
        // Every release the app is behind by, newest first.
        assertEquals(listOf(8, 7), offer?.releases?.map { it.versionCode })
        assertEquals("https://example.com/hcm.apk", offer?.downloadUrl)
    }

    @Test
    fun offersNothingWhenTheAppIsTheLatestOrNewer() {
        assertNull(AppUpdates.offer(releases(5, 6), current = 6, dismissed = null))
        // A build ahead of the server, such as one installed before the release was deployed.
        assertNull(AppUpdates.offer(releases(5, 6), current = 7, dismissed = null))
        // Offline, or not loaded yet.
        assertNull(AppUpdates.offer(null, current = 6, dismissed = null))
    }

    @Test
    fun laterHoldsForThatVersionOnly() {
        assertNull(AppUpdates.offer(releases(6, 7), current = 6, dismissed = 7))
        assertEquals(8, AppUpdates.offer(releases(6, 7, 8), current = 6, dismissed = 7)?.versionCode)
    }

    @Test
    fun anOfferWithoutADownloadAddressHasNone() {
        assertNull(AppUpdates.offer(releases(6, 7, downloadUrl = null), current = 6, dismissed = null)?.downloadUrl)
        assertNull(AppUpdates.offer(releases(6, 7, downloadUrl = " "), current = 6, dismissed = null)?.downloadUrl)
    }

    // --- what's new ---

    @Test
    fun showsTheReleasesTheAppWasUpdatedPast() {
        val shown = AppUpdates.whatsNew(releases(3, 4, 5, 6, 7), current = 6, lastSeen = 3, updated = true)

        // Not 7: that one is not installed.
        assertEquals(listOf(6, 5, 4), (shown as WhatsNew.Show).releases.map { it.versionCode })
    }

    @Test
    fun aFirstStartAfterAnUpdateShowsTheNotesOfThisVersion() {
        // No version stored yet: the first start of the first version that keeps one, over 1.3.0.
        val shown = AppUpdates.whatsNew(releases(3, 4, 5, 6), current = 6, lastSeen = null, updated = true)

        assertEquals(listOf(6), (shown as WhatsNew.Show).releases.map { it.versionCode })
    }

    @Test
    fun aFreshInstallShowsNothing() {
        assertEquals(WhatsNew.Nothing, AppUpdates.whatsNew(releases(3, 4, 5, 6), current = 6, lastSeen = null, updated = false))
        assertEquals(WhatsNew.Nothing, AppUpdates.whatsNew(null, current = 6, lastSeen = null, updated = false))
    }

    @Test
    fun nothingOnceThisVersionWasSeen() {
        assertEquals(WhatsNew.Nothing, AppUpdates.whatsNew(releases(5, 6), current = 6, lastSeen = 6, updated = true))
        // Installed back to an older version.
        assertEquals(WhatsNew.Nothing, AppUpdates.whatsNew(releases(5, 6), current = 5, lastSeen = 6, updated = true))
    }

    @Test
    fun waitsForReleasesThatAreNotThereYet() {
        // Offline: shown once they load.
        assertEquals(WhatsNew.Waiting, AppUpdates.whatsNew(null, current = 6, lastSeen = 5, updated = true))
        // The server does not know this version yet: its notes may come with the next deploy.
        assertEquals(WhatsNew.Waiting, AppUpdates.whatsNew(releases(3, 4, 5), current = 6, lastSeen = 5, updated = true))
    }

    @Test
    fun nothingWhenTheServerKnowsTheVersionAndHasNoNotesForIt() {
        val gap = AppReleases(LatestRelease(8, "1.6.0"), listOf(release(8), release(5)))

        assertEquals(WhatsNew.Nothing, AppUpdates.whatsNew(gap, current = 7, lastSeen = 6, updated = true))
    }

    // --- Home's one card ---

    @Test
    fun homeShowsWhatsNewBeforeAnOffer() {
        val all = releases(5, 6, 7)
        val offer = AppUpdates.offer(all, current = 6, dismissed = null)
        val whatsNew = AppUpdates.whatsNew(all, current = 6, lastSeen = 5, updated = true)

        assertTrue(AppUpdates.homeCard(whatsNew, offer) is AppUpdates.HomeCard.WhatsNew)
        assertEquals(AppUpdates.HomeCard.Update(offer!!), AppUpdates.homeCard(WhatsNew.Nothing, offer))
        assertEquals(AppUpdates.HomeCard.Update(offer), AppUpdates.homeCard(WhatsNew.Waiting, offer))
        assertNull(AppUpdates.homeCard(WhatsNew.Nothing, null))
    }

    // --- notes ---

    @Test
    fun notesAreInTheLanguageOfTheAppAndFallBackToTheOther() {
        val both = release(6, ru = listOf("Строка", " "), en = listOf("Line"))
        val russianOnly = release(7, ru = listOf("Только по-русски"), en = emptyList())

        assertEquals(listOf("Строка"), AppUpdates.notes(both, Language.RU))
        assertEquals(listOf("Line"), AppUpdates.notes(both, Language.EN))
        assertEquals(listOf("Только по-русски"), AppUpdates.notes(russianOnly, Language.EN))
        assertEquals(listOf("en 7", "en 6"), AppUpdates.lines(listOf(release(7), release(6)), Language.EN))
    }

    @Test
    fun longNotesAreCollapsedToTheirFirstThreeLines() {
        val lines = (1..5).map { "line $it" }

        assertEquals(lines.take(3) to 2, AppUpdates.collapsed(lines, expanded = false))
        assertEquals(lines to 0, AppUpdates.collapsed(lines, expanded = true))
        assertEquals(lines.take(3) to 0, AppUpdates.collapsed(lines.take(3), expanded = false))
    }

    @Test
    fun aSpanOfVersionsNamesTheOldestAndTheNewest() {
        assertEquals("1.4.0" to null, AppUpdates.versionSpan(listOf(release(6))))
        assertEquals("1.2.0" to "1.4.0", AppUpdates.versionSpan(listOf(release(6), release(5), release(4))))
    }

    @Test
    fun readsTheServersAnswer() {
        val answer = hcmJson.decodeFromString(
            AppReleases.serializer(),
            """{"latest":{"versionCode":6,"versionName":"1.4.0","date":"2026-10-21"},"downloadUrl":null,"releases":[""" +
                """{"versionCode":6,"versionName":"1.4.0","date":"2026-10-21","notes":{"ru":["а"],"en":["a"]},"later":true}],"more":1}""",
        )

        assertEquals(6, answer.latest.versionCode)
        assertNull(answer.downloadUrl)
        assertEquals(listOf("а"), answer.releases.single().notes.ru)
    }

    // --- what the device remembers ---

    private class MemoryStore(override var lastSeenVersionCode: Int? = null, override var dismissedVersionCode: Int? = null) : AppUpdateStore

    @Test
    fun aFreshInstallCountsItsVersionAsSeenAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore()

        AppUpdateNotices(store, 6, { false }, backgroundScope)

        assertEquals(6, store.lastSeenVersionCode)
    }

    @Test
    fun whatsNewStaysUntilGotIt() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(lastSeenVersionCode = 5)
        val notices = AppUpdateNotices(store, 6, { true }, backgroundScope)

        notices.load { releases(4, 5, 6) }

        assertEquals(listOf(6), (notices.homeCard.value as AppUpdates.HomeCard.WhatsNew).releases.map { it.versionCode })
        assertEquals(5, store.lastSeenVersionCode)

        notices.acknowledge()

        assertEquals(6, store.lastSeenVersionCode)
        assertNull(notices.homeCard.value)
    }

    @Test
    fun anUpdateWhileOfflineIsShownOnceTheReleasesLoad() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(lastSeenVersionCode = null)
        val notices = AppUpdateNotices(store, 6, { true }, backgroundScope)

        notices.load { throw ApiException("offline", code = "NETWORK", isNetwork = true) }

        assertTrue(notices.releases.value.state is LoadState.Failed)
        assertNull(notices.homeCard.value)
        assertNull(store.lastSeenVersionCode)

        notices.load { releases(5, 6) }

        assertTrue(notices.homeCard.value is AppUpdates.HomeCard.WhatsNew)
    }

    @Test
    fun aFailedLoadKeepsWhatWasLoaded() = runTest(UnconfinedTestDispatcher()) {
        val notices = AppUpdateNotices(MemoryStore(lastSeenVersionCode = 6), 6, { true }, backgroundScope)

        notices.load { releases(6, 7) }
        notices.load { throw ApiException("offline", code = "NETWORK", isNetwork = true) }

        assertEquals(LoadState.Loaded, notices.releases.value.state)
        assertEquals(7, (notices.homeCard.value as AppUpdates.HomeCard.Update).offer.versionCode)
    }

    @Test
    fun laterHidesTheOfferAndKeepsItOnTheProfileTab() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(lastSeenVersionCode = 6)
        val notices = AppUpdateNotices(store, 6, { true }, backgroundScope)

        notices.load { releases(6, 7) }
        notices.later(7)

        assertEquals(7, store.dismissedVersionCode)
        assertNull(notices.homeCard.value)
        assertEquals(7, notices.newer.value?.versionCode)

        notices.load { releases(6, 7, 8) }

        assertEquals(8, (notices.homeCard.value as AppUpdates.HomeCard.Update).offer.versionCode)
    }

    @Test
    fun whatsNewComesBeforeTheOfferAndTheOfferAfterGotIt() = runTest(UnconfinedTestDispatcher()) {
        val notices = AppUpdateNotices(MemoryStore(lastSeenVersionCode = 5), 6, { true }, backgroundScope)

        notices.load { releases(5, 6, 7) }

        assertTrue(notices.homeCard.value is AppUpdates.HomeCard.WhatsNew)

        notices.acknowledge()

        assertEquals(7, (notices.homeCard.value as AppUpdates.HomeCard.Update).offer.versionCode)
    }
}
