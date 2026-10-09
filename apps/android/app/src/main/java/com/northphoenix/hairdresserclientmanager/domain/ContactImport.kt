package com.northphoenix.hairdresserclientmanager.domain

/** Raw details read from one device contact. */
data class DeviceContact(
    val fullName: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val phones: List<String?> = emptyList(),
    val emails: List<String?> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
) {
    data class PostalAddress(
        val street: String? = null,
        val city: String? = null,
        val region: String? = null,
        val postcode: String? = null,
        val country: String? = null,
    )
}

data class ImportedContact(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
) {
    val hasFields: Boolean get() = name != null || phone != null || email != null || address != null
}

/** Mirrors `normalizeImportedContact` in packages/shared: first non-blank value of each kind. */
object ContactImport {
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun normalize(contact: DeviceContact): ImportedContact {
        val name = clean(contact.fullName)
            ?: clean(listOf(contact.givenName, contact.familyName).mapNotNull(::clean).joinToString(" "))
        val address = contact.addresses.firstNotNullOfOrNull { postal ->
            clean(
                listOf(postal.street, postal.city, postal.region, postal.postcode, postal.country)
                    .mapNotNull(::clean)
                    .joinToString(", "),
            )
        }

        return ImportedContact(
            name = name,
            phone = contact.phones.firstNotNullOfOrNull(::clean),
            email = contact.emails.firstNotNullOfOrNull(::clean),
            address = address,
        )
    }
}
