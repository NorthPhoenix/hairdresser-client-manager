package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import java.text.Normalizer

/**
 * Who recommended whom ("По рекомендации"): the links between Clients of one Stylist, read from the
 * Client list ([ClientProfile.referredById]).
 */
object Referrals {
    /** The Client who recommended [client], when they are in [clients]. */
    fun referrer(client: ClientProfile, clients: List<ClientProfile>): ClientProfile? =
        client.referredById?.let { id -> clients.firstOrNull { it.id == id } }

    /** The name to show for whoever recommended [client]: their name now, or the one the server last stated. */
    fun referrerName(client: ClientProfile, clients: List<ClientProfile>): String? =
        referrer(client, clients)?.name ?: client.referredByName?.takeIf { it.isNotBlank() }

    /** The Clients [client] recommended, by name. */
    fun referred(client: ClientProfile, clients: List<ClientProfile>): List<ClientProfile> =
        clients.filter { it.referredById == client.id && it.id != client.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * The Client a name says recommended it, when it says so: "Надя От Лены Балаяж" is recommended by
     * the one Client whose first name is Лена. Only a name with the word "от" before another word, and
     * only when no referrer is set and exactly one other Client matches that word by their first word
     * (case, "ё" and a Russian ending aside: «Лены» is «Лена»). A suggestion; nothing is saved from it.
     */
    fun suggestion(client: ClientProfile, clients: List<ClientProfile>): ClientProfile? {
        if (client.referredById != null) {
            return null
        }

        val wanted = wordAfterFrom(client.name) ?: return null
        val matches = clients.filter { other -> other.id != client.id && firstWord(other.name)?.let { sameName(it, wanted) } == true }

        return matches.singleOrNull()
    }

    /** The word after the first " от " (any case) of [name], without punctuation around it. */
    internal fun wordAfterFrom(name: String): String? {
        val words = name.trim().split(Regex("\\s+"))

        for (index in 1 until words.size - 1) {
            if (words[index].equals("от", ignoreCase = true)) {
                return words[index + 1].trim { !it.isLetter() }.takeIf { it.isNotEmpty() }
            }
        }

        return null
    }

    private fun firstWord(name: String): String? = name.trim().split(Regex("\\s+")).firstOrNull()?.trim { !it.isLetter() }?.takeIf { it.isNotEmpty() }

    /** One name as it is written for the Client («Лена») or after "от" («Лены»): the same up to the ending. */
    internal fun sameName(first: String, second: String): Boolean {
        val a = fold(first)
        val b = fold(second)

        if (a == b) {
            return true
        }

        val stemA = stem(a)
        val stemB = stem(b)

        return stemA.length >= 2 && stemA == stemB
    }

    private fun fold(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase().replace('ё', 'е')

    /** The name without its ending: the vowels and the soft sign or short i at its end. */
    private fun stem(word: String): String = word.trimEnd { it in "аяыиеуюоьй" }

    /** [clients] without the Client [id]. Whoever they recommended is recommended by nobody, as the server does. */
    fun withoutClient(clients: List<ClientProfile>, id: String): List<ClientProfile> =
        clients.filterNot { it.id == id }.map { if (it.referredById == id) it.copy(referredById = null, referredByName = null) else it }

    /**
     * [clients] once [removedId] is merged into [kept]: whoever the removed Client recommended is
     * recommended by the kept Client, who is never recommended by themselves, as the server does.
     * The removed Client is not taken out here.
     */
    fun afterMerge(clients: List<ClientProfile>, removedId: String, kept: ClientProfile): List<ClientProfile> =
        clients.map {
            when {
                it.referredById != removedId -> it
                it.id == kept.id -> it.copy(referredById = null, referredByName = null)
                else -> it.copy(referredById = kept.id, referredByName = kept.name)
            }
        }
}
