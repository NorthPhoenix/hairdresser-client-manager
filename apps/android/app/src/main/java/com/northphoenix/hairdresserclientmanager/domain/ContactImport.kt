package com.northphoenix.hairdresserclientmanager.domain

/**
 * Raw details read from one device contact. [fullName] is the name the contact is saved under;
 * a contact saved without one has none, whatever Android lists it under.
 */
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

object ContactImport {
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** A name and a phone number are one line each, as they are in a Client Profile. */
    private fun oneLine(value: String?): String? = value?.let(OneLine::clean)?.takeIf { it.isNotEmpty() }

    /** The first usable value of each kind. */
    fun normalize(contact: DeviceContact): ImportedContact {
        val name = oneLine(contact.fullName)
            ?: oneLine(listOf(contact.givenName, contact.familyName).mapNotNull(::oneLine).joinToString(" "))
        val address = contact.addresses.firstNotNullOfOrNull { postal ->
            clean(
                listOf(postal.street, postal.city, postal.region, postal.postcode, postal.country)
                    .mapNotNull(::clean)
                    .joinToString(", "),
            )
        }

        return ImportedContact(
            name = name,
            phone = contact.phones.firstNotNullOfOrNull(::oneLine),
            email = contact.emails.firstNotNullOfOrNull(::clean),
            address = address,
        )
    }

    /**
     * Whether the Stylist is asked before the contact is copied into the Client Profile form.
     * A Client who already exists is always asked about. A new one is when the contact would
     * replace something that is in the form already; the form's values are the last four arguments.
     * Copying only ever replaces the fields the contact has a value for, so nothing else is at stake.
     */
    fun asksFirst(
        imported: ImportedContact,
        existingClient: Boolean,
        name: String,
        phone: String,
        email: String,
        address: String,
    ): Boolean =
        existingClient || listOf(imported.name to name, imported.phone to phone, imported.email to email, imported.address to address)
            .any { (fromContact, entered) -> fromContact != null && entered.isNotBlank() && entered.trim() != fromContact }
}
