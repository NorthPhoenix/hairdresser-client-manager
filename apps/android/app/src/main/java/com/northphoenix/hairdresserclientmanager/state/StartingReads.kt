package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Stylist

/** The Stylist as the server last sent her to this device, and the sign-in account she came for. */
data class KeptStylist(val account: String, val stylist: Stylist)

/** Where the device keeps [KeptStylist]: one, of the account that was signed in last. */
interface KeptStylistStore {
    var kept: KeptStylist?
}

/**
 * A start of the app asks for the lists of the Stylist it kept for the signed-in account together
 * with her settings (`stylist.bootstrap`), not once those have answered: after a pause the server
 * is cold, and each round trip then takes a second or more. What is kept says which lists to ask
 * for, and nothing else: the app waits for the settings as before, no screen shows what was kept,
 * nothing is saved from it, and the settings the server sends replace it. Lists asked for under
 * keys that the settings then do not have (another Stylist, another timezone) are not shown, and
 * the lists are asked for again, as when nothing was kept (docs/adr/0016).
 */
object StartingReads {
    /** The Stylist whose lists go out with the settings: the one kept for [account], once her onboarding is complete (before that there are no lists). */
    fun early(kept: KeptStylist?, account: String?): Stylist? =
        kept?.takeIf { account != null && it.account == account }?.stylist?.takeIf { it.onboardingComplete }

    /** Whether the lists asked for as [early]'s are those of [confirmed]: the same Stylist, onboarded, in the same timezone. */
    fun sameLists(early: Stylist, confirmed: Stylist): Boolean =
        early.id == confirmed.id && early.onboardingComplete == confirmed.onboardingComplete && early.timezone == confirmed.timezone
}
