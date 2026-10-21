package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ClientProfile

/**
 * The Families of a Stylist's Clients (CONTEXT.md: Family), read from the Client list
 * ([ClientProfile.familyId], [ClientProfile.familyRole]). A Family always has two Clients or more:
 * the server takes the one who would be left alone out of it, and the list here does the same
 * after a change it applies before the reload. Tested in `FamiliesTest`.
 */
object Families {
    /** The other Clients in [client]'s Family, by name. None when the Client is in no Family. */
    fun members(client: ClientProfile, clients: List<ClientProfile>): List<ClientProfile> {
        val familyId = client.familyId ?: return emptyList()

        return clients.filter { it.familyId == familyId && it.id != client.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * The family of the Clients of an Appointment ([participantIds], primary first) who are not in it,
     * each once: offered to add when Services are added. Per Client in that order, each one's family by name.
     */
    fun suggestions(participantIds: List<String>, clients: List<ClientProfile>): List<ClientProfile> {
        val taken = participantIds.toSet()
        val byId = clients.associateBy { it.id }

        return participantIds
            .mapNotNull { byId[it] }
            .flatMap { members(it, clients) }
            .filter { it.id !in taken }
            .distinctBy { it.id }
    }

    /**
     * Whether adding [picked] to the Family of [client] takes [picked] out of another Family: only when both are
     * in one and the two are not the same. A [client] without a Family joins the one [picked] is in instead.
     */
    fun movesFromOtherFamily(client: ClientProfile, picked: ClientProfile): Boolean =
        client.familyId != null && picked.familyId != null && picked.familyId != client.familyId

    /** Whether taking [member] out of their Family leaves only one Client in it, so that it is gone. */
    fun leavingEndsFamily(member: ClientProfile, clients: List<ClientProfile>): Boolean {
        val familyId = member.familyId ?: return false

        return clients.count { it.familyId == familyId } <= 2
    }

    /** "сын · 2 визита": the role, when there is one, and the visits, when they are known. */
    fun subtitle(role: String, visits: String?): String = listOfNotNull(role.trim().ifEmpty { null }, visits).joinToString(" · ")

    /** The list with the Clients a change of a Family answered, each in its place, and no Family left with one Client. */
    fun withChanged(clients: List<ClientProfile>, changed: List<ClientProfile>): List<ClientProfile> {
        val byId = changed.associateBy { it.id }

        return withoutLoneFamilies(
            clients.map { known ->
                byId[known.id]?.let { it.copy(activeProfileShare = it.activeProfileShare ?: known.activeProfileShare, distinctFromIds = it.distinctFromIds ?: known.distinctFromIds) }
                    ?: known
            },
        )
    }

    /** [clients] without the Client [id]: a Family that would keep one Client is gone, as on the server. */
    fun withoutClient(clients: List<ClientProfile>, id: String): List<ClientProfile> = withoutLoneFamilies(clients.filterNot { it.id == id })

    /**
     * [clients] once [removedId] is merged into [kept], as the server answered [kept]: the kept Client is in their Family,
     * the Clients of the removed Client's Family are in it too when it was another one, and no Family keeps one Client.
     */
    fun afterMerge(clients: List<ClientProfile>, removedId: String, kept: ClientProfile): List<ClientProfile> {
        val removedFamily = clients.firstOrNull { it.id == removedId }?.familyId

        return withoutLoneFamilies(
            clients.filterNot { it.id == removedId }.map {
                when {
                    it.id == kept.id -> it.copy(familyId = kept.familyId, familyRole = kept.familyRole)
                    removedFamily != null && it.familyId == removedFamily && kept.familyId != null -> it.copy(familyId = kept.familyId)
                    else -> it
                }
            },
        )
    }

    /** No Client is alone in a Family: one who is has no Family and no role. */
    private fun withoutLoneFamilies(clients: List<ClientProfile>): List<ClientProfile> {
        val sizes = clients.mapNotNull { it.familyId }.groupingBy { it }.eachCount()

        return clients.map { if (it.familyId != null && sizes[it.familyId] == 1) it.copy(familyId = null, familyRole = "") else it }
    }
}
