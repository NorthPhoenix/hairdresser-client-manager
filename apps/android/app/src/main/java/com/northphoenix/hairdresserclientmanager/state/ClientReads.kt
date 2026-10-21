package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientFamily
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.FamilyChange
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.data.ShareChoice
import com.northphoenix.hairdresserclientmanager.domain.Families
import com.northphoenix.hairdresserclientmanager.domain.Referrals
import kotlinx.coroutines.CoroutineScope

/**
 * What is read of a Stylist as a whole: their Clients, their Service Menu. Another Stylist is
 * another key, so one Stylist's list is never shown to the next.
 */
internal data class StylistKey(val stylistId: String)

/** One Client, by their id: the key of their Appointment history. */
internal data class ClientKey(val id: String)

/**
 * Every read of Clients, each through a [ReadCache]: the Client list ([list],
 * `clientProfile.list`), which the pickers and the Client Profile read as well, and a Client's
 * Appointment history ([history], `clientProfile.appointmentHistory`), and a Client's Family with
 * each member's visits ([family], `clientProfile.family`).
 *
 * A change is told here, as in [AppointmentReads]: the list on screen gets what the server has
 * confirmed at once ([saved], [deleted], [profileShare]), so a saved Client is in the list and in
 * every picker before the reload, and a deleted one is gone from them. And the change is marked in
 * [changes], so that no answer from before it is shown or served again.
 *
 * Not thread-safe: use it from the thread of the scope.
 */
internal class ClientReads(
    scope: CoroutineScope,
    private val changes: Changes,
    fetchList: suspend (StylistKey) -> List<ClientProfile>,
    fetchHistory: suspend (ClientKey) -> List<ClientHistoryEntry>,
    fetchFamily: suspend (ClientKey) -> ClientFamily = { ClientFamily() },
) {
    /** Latest change first, as the server lists them. */
    val list = ReadCache(scope, changes, LIST_LIMITS, about = { _, clients -> clients.map { it.id } + CLIENTS }, fetch = fetchList)

    /**
     * A Client's Appointments, newest first, with that Client's Services and photos. It repeats
     * what Appointments hold, also of one the Client is only about to be in, and the name of
     * another Client who is primary: no list of ids covers that. So any change at all puts a
     * cached history out of date, and it is loaded anew the next time it is shown.
     */
    val history = ReadCache(scope, changes, HISTORY_LIMITS, about = { _, _ -> listOf(Changes.ANY_CHANGE) }, fetch = fetchHistory)

    /**
     * A Client's Family with each member's number of visits. Who is in it comes from [list]; this
     * adds the visits, which any change of an Appointment can change, so like [history] any change
     * at all puts it out of date.
     */
    val family = ReadCache(scope, changes, HISTORY_LIMITS, about = { _, _ -> listOf(Changes.ANY_CHANGE) }, fetch = fetchFamily)

    /** The server has just changed a Family: the Clients it touched are in the list on screen as they are now. */
    fun familyChanged(change: FamilyChange) {
        list.update { _, clients -> Families.withChanged(clients, change.clients) }
        changes.changed(*(change.clients.map { it.id } + CLIENTS).toTypedArray())
    }

    /** The server has just saved this Client, a new one or one it had: they are in the list on screen now, first. */
    fun saved(saved: ClientProfile) {
        list.update { _, clients -> clients.withSaved(saved) }
        changes.changed(saved.id, CLIENTS)
    }

    /** The server has just deleted this Client: they leave the list on screen, and with it every picker. */
    fun deleted(id: String) {
        // Whoever they recommended is recommended by nobody now, as on the server.
        // And a Family left with one Client is gone.
        list.update { _, clients -> Families.withoutClient(Referrals.withoutClient(clients, id), id) }
        changes.changed(id, CLIENTS)
    }

    /**
     * The server has just merged [removedId] into [kept]: whoever the removed Client recommended is
     * recommended by the kept Client now, who is never recommended by themselves, as on the server.
     * Call it before [deleted] and [saved], which then put the rest in.
     */
    fun referralsMoved(removedId: String, kept: ClientProfile) {
        list.update { _, clients -> Referrals.afterMerge(clients, removedId, kept) }
    }

    /**
     * The server has just merged [removedId] into [kept]: the kept Client is in the Family the server says, which the
     * removed Client's Family has joined, and no Family keeps one Client. Call it before [deleted] and [saved].
     */
    fun familiesMerged(removedId: String, kept: ClientProfile) {
        list.update { _, clients -> Families.afterMerge(clients, removedId, kept) }
    }

    /** The server has just recorded that these two Clients are different people: neither is the other's duplicate now. */
    fun distinct(clientId: String, otherClientId: String) {
        list.update { _, clients ->
            clients.map { client ->
                when (client.id) {
                    clientId -> client.copy(distinctFromIds = client.distinctFromIds.orEmpty() + otherClientId)
                    otherClientId -> client.copy(distinctFromIds = client.distinctFromIds.orEmpty() + clientId)
                    else -> client
                }
            }
        }
        changes.changed(clientId, otherClientId, CLIENTS)
    }

    /** What the server has just answered about a Client's Profile Share: [share], or none after a revoke. */
    fun profileShare(clientId: String, share: ProfileShare?) {
        list.update { _, clients -> clients.withProfileShare(clientId, share) }
        shareChanged()
    }

    /**
     * One choice of a Client's Profile Share made the other way, without the Stylist waiting for
     * it (see [Ahead]): the card shows it at once, whatever is loaded meanwhile. What the server
     * answers to it goes in with [profileShare].
     */
    fun shareChoiceAhead(change: String, clientId: String, choice: ShareChoice, included: Boolean) = Ahead(
        show = {
            list.expect(change) { _, clients ->
                clients.map { if (it.id == clientId) it.copy(activeProfileShare = it.activeProfileShare?.with(choice, included)) else it }
            }
        },
        settle = { list.settled(change) },
    )

    /**
     * Another status of an Appointment, in a history on screen: shown with the status chip that
     * was tapped (see [Ahead]), and kept once the server has accepted it. [AppointmentReads] marks
     * the change.
     */
    fun statusAhead(change: String, appointmentId: String, status: AppointmentStatus): Ahead {
        val edit = { _: ClientKey, entries: List<ClientHistoryEntry> -> entries.map { if (it.id == appointmentId) it.copy(status = status) else it } }

        return Ahead(show = { history.expect(change, edit) }, saved = { history.update(edit) }, settle = { history.settled(change) })
    }

    /**
     * Marks that the server has saved or deleted a Client, or may have. [id] is null for a new
     * Client, who is in no answer yet: the list is marked as a whole for them.
     */
    fun changed(id: String?) = changes.changed(*listOfNotNull(id, CLIENTS).toTypedArray())

    /** Marks that the server has changed a Profile Share, or may have. It shows in the Client list and nowhere else. */
    fun shareChanged() = changes.changed(CLIENTS)

    /** Empties the caches, for a Stylist who has signed out. Call [Changes.startOver] first. */
    suspend fun clear() {
        list.clear()
        history.clear()
        family.clear()
    }

    private companion object {
        /** One Stylist has one list. */
        val LIST_LIMITS = CacheLimits(answers = 1)

        /** The Client Profiles opened of late. A history is kept only until the next change (see [history]). */
        val HISTORY_LIMITS = CacheLimits(answers = 30)

        /** Names the Client list as a whole, in what an answer is about. */
        const val CLIENTS = "client-list"
    }
}
