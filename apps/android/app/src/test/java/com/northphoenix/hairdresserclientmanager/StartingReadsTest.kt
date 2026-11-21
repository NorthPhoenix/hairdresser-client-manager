package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.KeptStylists
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.state.AfterSettings
import com.northphoenix.hairdresserclientmanager.state.KeptStylist
import com.northphoenix.hairdresserclientmanager.state.ListsOf
import com.northphoenix.hairdresserclientmanager.state.StartingReads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A start of the app asks for the lists it kept together with the settings, only for the account they were kept for, and only until the settings come. */
class StartingReadsTest {
    private val onboarded = Stylist(
        id = "stylist-1",
        language = Language.RU,
        timezone = "America/Chicago",
        salonAddress = "1 Main St",
        onboardingCompletedAt = "2026-10-01T15:00:00.000Z",
        updatedAt = "2026-11-19T18:00:00.000Z",
    )
    private val hers = ListsOf("stylist-1", "America/Chicago")

    @Test
    fun anOnboardedStylistsListsAreHersInHerTimezone() {
        assertEquals(hers, StartingReads.listsOf(onboarded))
        assertNull(StartingReads.listsOf(onboarded.copy(onboardingCompletedAt = null)))
    }

    @Test
    fun onlyWhatMakesTheListsKeysIsKeptForTheAccountSignedIn() {
        assertEquals(KeptStylist("user_a", hers), StartingReads.toKeep("user_a", onboarded))
        // Language, salon address and the settings' version are not kept: they are the server's to say.
        assertEquals(StartingReads.toKeep("user_a", onboarded), StartingReads.toKeep("user_a", onboarded.copy(language = Language.EN, salonAddress = "", updatedAt = null)))
        assertNull(StartingReads.toKeep(null, onboarded))
        // Still in onboarding: there are no lists to ask for, and what was kept before goes.
        assertNull(StartingReads.toKeep("user_a", onboarded.copy(onboardingCompletedAt = null)))
    }

    @Test
    fun theListsGoOutWithTheSettingsOnlyForTheAccountTheyWereKeptFor() {
        val kept = KeptStylist("user_a", hers)

        assertEquals(hers, StartingReads.early(kept, "user_a"))
        assertNull(StartingReads.early(kept, "user_b"))
        assertNull(StartingReads.early(kept, null))
        assertNull(StartingReads.early(null, "user_a"))
    }

    @Test
    fun theScreensReadTheEarlyListsOnlyUntilTheSettingsHaveCome() {
        val early = ListsOf("stylist-1", "Europe/Moscow")

        assertEquals(early, StartingReads.listsOf(null, early))
        assertNull(StartingReads.listsOf(null, null))
        // The settings decide from the moment they are there, also that there are no lists yet.
        assertEquals(hers, StartingReads.listsOf(onboarded, early))
        assertNull(StartingReads.listsOf(onboarded.copy(onboardingCompletedAt = null), early))
    }

    @Test
    fun theEarlyListsStandForTheSameStylistInTheSameTimezoneWhateverElseChanged() {
        assertEquals(AfterSettings.STAND, StartingReads.afterSettings(hers, onboarded))
        assertEquals(AfterSettings.STAND, StartingReads.afterSettings(hers, onboarded.copy(language = Language.EN, salonAddress = "2 Oak Ave", updatedAt = null)))
    }

    @Test
    fun anotherTimezoneAsksForTheDayListsAgainAndAnotherStylistForAll() {
        assertEquals(AfterSettings.DAYS_AGAIN, StartingReads.afterSettings(hers, onboarded.copy(timezone = "America/New_York")))
        assertEquals(AfterSettings.ALL_AGAIN, StartingReads.afterSettings(hers, onboarded.copy(id = "stylist-2")))
        // Another Stylist in another timezone: all of them.
        assertEquals(AfterSettings.ALL_AGAIN, StartingReads.afterSettings(hers, onboarded.copy(id = "stylist-2", timezone = "America/New_York")))
        // Onboarding again (a Stylist made anew): there are no lists.
        assertEquals(AfterSettings.NO_LISTS, StartingReads.afterSettings(hers, onboarded.copy(id = "stylist-2", onboardingCompletedAt = null)))
    }

    @Test
    fun theKeptListsReadBackAsTheyWereWritten() {
        for (kept in listOf(KeptStylist("user_a", hers), KeptStylist("user_b", ListsOf("stylist-2", "Europe/Moscow")))) {
            assertEquals(kept, KeptStylists.read(KeptStylists.write(kept)))
        }
    }

    @Test
    fun keptListsThisVersionCannotReadCountAsNone() {
        assertNull(KeptStylists.read(null))
        assertNull(KeptStylists.read(""))
        assertNull(KeptStylists.read("not json"))
        assertNull(KeptStylists.read("""{"account":"user_a"}"""))
        assertNull(KeptStylists.read("""{"account":"user_a","stylistId":"stylist-1"}"""))
    }

    @Test
    fun keptListsWithFieldsOfALaterVersionAreRead() {
        val text = """{"account":"user_a","stylistId":"stylist-1","timezone":"America/Chicago","later":true}"""

        assertEquals(KeptStylist("user_a", hers), KeptStylists.read(text))
    }
}
