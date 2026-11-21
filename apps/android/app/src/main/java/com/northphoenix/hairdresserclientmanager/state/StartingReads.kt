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

/**
 * A start of the app asks for the lists of the Stylist it kept for the signed-in account together
 * with her settings (`stylist.bootstrap`), not once those have answered: after a pause the server
 * is cold, and each round trip then takes a second or more. What is kept says which lists to ask
 * for (whose, and in which timezone), and nothing else: it is not the Stylist of the session, the
 * app waits for the settings as before, no screen shows anything of it, and the settings the
 * server sends replace it. Lists asked for under keys that the settings then do not have (another
 * Stylist, another timezone) are not shown, and the lists are asked for again, as when nothing was
 * kept (docs/adr/0016).
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

    /** What to keep for [account] once the server has sent [stylist]: null (nothing) for nobody, and while she is in onboarding. */
    fun toKeep(account: String?, stylist: Stylist): KeptStylist? = account?.let { signedIn -> listsOf(stylist)?.let { KeptStylist(signedIn, it) } }
}
