package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Stylist

/** Whose lists the screens read, and the timezone their days are in: an onboarded Stylist's id and timezone. */
data class ListsOf(val stylistId: String, val timezone: String)

/** What the device keeps of the Stylist the server last sent: whose lists to ask for at the next start, and the sign-in account she came for. */
data class KeptStylist(val account: String, val lists: ListsOf)

/** Where the device keeps [KeptStylist]: one, of the account that was signed in last. */
interface KeptStylistStore {
    var kept: KeptStylist?
}

/** What becomes of the lists that went out with the settings once those have come (see [StartingReads.afterSettings]). */
enum class AfterSettings {
    /** The same Stylist in the same timezone: they are the reload. */
    STAND,

    /** The same Stylist in another timezone: the Client list and the Service Menu stand, the day lists are asked for again. */
    DAYS_AGAIN,

    /** Another Stylist of the account (made anew on the server): all are asked for again under her keys. */
    ALL_AGAIN,

    /** She is in onboarding: there are no lists. */
    NO_LISTS,
}

/**
 * A start of the app asks for the lists of the Stylist it kept for the signed-in account together
 * with her settings (`stylist.bootstrap`), not once those have answered: after a pause the server
 * is cold, and each round trip then takes a second or more. What is kept says which lists to ask
 * for (whose, and in which timezone), and nothing else: it is not the Stylist of the session, the
 * app waits for the settings as before, no screen shows anything of it, and the settings the
 * server sends replace it. The server answers each read for the account that is signed in, so
 * what was kept decides only the keys the answers are cached under: answers under keys that the
 * settings then do not have (another Stylist id for the Client list and the Service Menu, another
 * timezone for the day lists) are not shown, and those lists are asked for again, as when nothing
 * was kept (docs/adr/0016).
 */
object StartingReads {
    /** Whose lists [stylist]'s are; null while her onboarding is not complete (there are no lists before that). */
    fun listsOf(stylist: Stylist): ListsOf? = stylist.takeIf { it.onboardingComplete }?.let { ListsOf(it.id, it.timezone) }

    /**
     * Whose lists the screens read: those of the Stylist the server sent ([stylist]) once she is
     * there, also none while she is in onboarding. Before that, while her settings are on their
     * way, those asked for early ([early]).
     */
    fun listsOf(stylist: Stylist?, early: ListsOf?): ListsOf? = if (stylist != null) listsOf(stylist) else early

    /** The lists that go out with the settings: those kept for [account], when it is the account they were kept for. */
    fun early(kept: KeptStylist?, account: String?): ListsOf? = kept?.takeIf { account != null && it.account == account }?.lists

    /** What becomes of the lists asked for as [early] once the server has sent [settings]. */
    fun afterSettings(early: ListsOf, settings: Stylist): AfterSettings {
        val hers = listsOf(settings)

        return when {
            hers == null -> AfterSettings.NO_LISTS
            hers.stylistId != early.stylistId -> AfterSettings.ALL_AGAIN
            hers.timezone != early.timezone -> AfterSettings.DAYS_AGAIN
            else -> AfterSettings.STAND
        }
    }

    /** What to keep for [account] once the server has sent [stylist]: null (nothing) for nobody, and while she is in onboarding. */
    fun toKeep(account: String?, stylist: Stylist): KeptStylist? = account?.let { signedIn -> listsOf(stylist)?.let { KeptStylist(signedIn, it) } }
}
