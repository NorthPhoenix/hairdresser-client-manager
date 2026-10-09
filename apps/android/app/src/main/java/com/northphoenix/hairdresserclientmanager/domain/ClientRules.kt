package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.ClientProfile

object ClientRules {
    fun searchablePhone(phone: String): String = phone.filter(Char::isDigit)

    /** Matches by name, by phone digits, or by the phone as typed. */
    fun filter(clients: List<ClientProfile>, search: String): List<ClientProfile> {
        val query = search.trim().lowercase()

        if (query.isEmpty()) {
            return clients
        }

        val phoneQuery = searchablePhone(query)

        return clients.filter { client ->
            client.name.lowercase().contains(query) ||
                (phoneQuery.isNotEmpty() && searchablePhone(client.phone).contains(phoneQuery)) ||
                client.phone.lowercase().contains(query)
        }
    }

    /** True when another Client already uses this phone number. Saving is still allowed after a warning. */
    fun isDuplicatePhone(clients: List<ClientProfile>, editingClientId: String?, phone: String): Boolean {
        val digits = searchablePhone(phone)

        if (phone.isBlank()) {
            return false
        }

        return clients.any { it.id != editingClientId && searchablePhone(it.phone) == digits }
    }
}
