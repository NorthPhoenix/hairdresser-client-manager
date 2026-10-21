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
    /** The contact's note, when it has one and Android lets the app read it. */
    val note: String? = null,
    /** The contact's id on the device: only the list of all contacts has it. */
    val id: Long? = null,
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

/** The fields of the Client Profile form that a contact is copied into. */
data class ContactFields(val name: String = "", val phone: String = "", val email: String = "", val address: String = "")

/**
 * A Contact Import that was made: the fields as they would be [before] without any contact copied
 * into them, and [after], as the import left them.
 */
data class CopiedContact(val before: ContactFields, val after: ContactFields)

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
     * Copies [imported] into the fields of the form. Only what the contact has is copied: every
     * other field stays as it is, and so does all that is not a field here, the Client Note first.
     *
     * [last] is the Contact Import made before this one, as long as no save has made it the
     * Client's own. A field that still holds what it put there goes back to what it held before,
     * so a second contact takes the place of the first one and a form never mixes the details of
     * two people. What the Stylist changed after that import stays.
     */
    fun copy(form: ContactFields, imported: ImportedContact, last: CopiedContact? = null): CopiedContact {
        fun withoutLast(field: (ContactFields) -> String): String =
            if (last != null && field(form) == field(last.after)) field(last.before) else field(form)

        val before = ContactFields(withoutLast(ContactFields::name), withoutLast(ContactFields::phone), withoutLast(ContactFields::email), withoutLast(ContactFields::address))

        return CopiedContact(
            before = before,
            after = ContactFields(
                name = imported.name ?: before.name,
                phone = imported.phone ?: before.phone,
                email = imported.email ?: before.email,
                address = imported.address ?: before.address,
            ),
        )
    }

    /**
     * Whether the Stylist is asked before the contact is copied into the Client Profile form.
     * A Client who already exists is always asked about. A new one is when [copy] would replace
     * or take out something that is in the form already; a field it leaves as it is, is not at stake.
     */
    fun asksFirst(form: ContactFields, imported: ImportedContact, existingClient: Boolean, last: CopiedContact? = null): Boolean {
        val after = copy(form, imported, last).after

        return existingClient || listOf(form.name to after.name, form.phone to after.phone, form.email to after.email, form.address to after.address)
            .any { (held, becomes) -> held.isNotBlank() && held.trim() != becomes.trim() }
    }
}
