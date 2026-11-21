package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.KeptStylists
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.state.KeptStylist
import com.northphoenix.hairdresserclientmanager.state.StartingReads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A start of the app asks for the lists of the Stylist it kept together with her settings, only when they are hers. */
class StartingReadsTest {
    private val onboarded = Stylist(
        id = "stylist-1",
        language = Language.RU,
        timezone = "America/Chicago",
        salonAddress = "1 Main St",
        onboardingCompletedAt = "2026-10-01T15:00:00.000Z",
        updatedAt = "2026-11-19T18:00:00.000Z",
    )

    @Test
    fun theListsGoOutWithTheSettingsForTheAccountTheStylistWasKeptFor() {
        assertEquals(onboarded, StartingReads.early(KeptStylist("user_a", onboarded), "user_a"))
    }

    @Test
    fun nothingGoesOutEarlyForAnotherAccountOrNobody() {
        val kept = KeptStylist("user_a", onboarded)

        assertNull(StartingReads.early(kept, "user_b"))
        assertNull(StartingReads.early(kept, null))
        assertNull(StartingReads.early(null, "user_a"))
    }

    @Test
    fun aStylistStillInOnboardingHasNoListsToAskFor() {
        assertNull(StartingReads.early(KeptStylist("user_a", onboarded.copy(onboardingCompletedAt = null)), "user_a"))
    }

    @Test
    fun theListsAskedForEarlyAreTheConfirmedStylistsWhenOnlyOtherSettingsChanged() {
        val confirmed = onboarded.copy(language = Language.EN, salonAddress = "2 Oak Ave", updatedAt = "2026-11-20T18:00:00.000Z")

        assertTrue(StartingReads.sameLists(onboarded, onboarded))
        assertTrue(StartingReads.sameLists(onboarded, confirmed))
    }

    @Test
    fun anotherTimezoneOrStylistMakesThemOtherLists() {
        assertFalse(StartingReads.sameLists(onboarded, onboarded.copy(timezone = "America/New_York")))
        assertFalse(StartingReads.sameLists(onboarded, onboarded.copy(id = "stylist-2")))
        assertFalse(StartingReads.sameLists(onboarded, onboarded.copy(onboardingCompletedAt = null)))
    }

    @Test
    fun theKeptStylistReadsBackAsItWasWritten() {
        for (kept in listOf(KeptStylist("user_a", onboarded), KeptStylist("user_b", onboarded.copy(onboardingCompletedAt = null, photoStorageConfigured = false)))) {
            assertEquals(kept, KeptStylists.read(KeptStylists.write(kept)))
        }
    }

    @Test
    fun aKeptStylistThisVersionCannotReadCountsAsNone() {
        assertNull(KeptStylists.read(null))
        assertNull(KeptStylists.read(""))
        assertNull(KeptStylists.read("not json"))
        assertNull(KeptStylists.read("""{"account":"user_a"}"""))
        assertNull(KeptStylists.read("""{"account":"user_a","stylist":{"id":"stylist-1"}}"""))
    }

    @Test
    fun aKeptStylistWithFieldsOfALaterVersionIsRead() {
        val text = """{"account":"user_a","stylist":{"id":"stylist-1","language":"ru","timezone":"America/Chicago","onboardingCompletedAt":"2026-10-01T15:00:00.000Z","later":1},"later":true}"""

        assertEquals(KeptStylist("user_a", Stylist("stylist-1", Language.RU, "America/Chicago", onboardingCompletedAt = "2026-10-01T15:00:00.000Z")), KeptStylists.read(text))
    }
}
