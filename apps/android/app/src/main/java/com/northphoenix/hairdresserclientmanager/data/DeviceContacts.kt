package com.northphoenix.hairdresserclientmanager.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.DisplayNameSources
import com.northphoenix.hairdresserclientmanager.domain.DeviceContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the contacts the Stylist picked, or all of them. Requires READ_CONTACTS; nothing is synced or stored. */
object DeviceContacts {
    /**
     * The name a contact is saved under. Android lists a contact that has no name under its phone
     * number or its email address; [source] (`Contacts.DISPLAY_NAME_SOURCE`) tells, and that is no name.
     */
    internal fun savedName(displayName: String?, source: Int): String? =
        displayName.takeUnless { source == DisplayNameSources.PHONE || source == DisplayNameSources.EMAIL }

    suspend fun read(context: Context, contactUri: Uri): DeviceContact? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val (contactId, displayName) = resolver.query(
                contactUri,
                arrayOf(Contacts._ID, Contacts.DISPLAY_NAME, Contacts.DISPLAY_NAME_SOURCE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to savedName(cursor.getString(1), cursor.getInt(2)) else null
            } ?: return@withContext null

            var givenName: String? = null
            var familyName: String? = null
            val phones = mutableListOf<String?>()
            val emails = mutableListOf<String?>()
            val addresses = mutableListOf<DeviceContact.PostalAddress>()

            resolver.query(
                Data.CONTENT_URI,
                arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10),
                "${Data.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                "${Data.IS_SUPER_PRIMARY} DESC, ${Data.IS_PRIMARY} DESC, ${Data._ID} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    when (cursor.getString(0)) {
                        StructuredName.CONTENT_ITEM_TYPE -> {
                            // DATA2 = given name, DATA3 = family name.
                            givenName = givenName ?: cursor.getString(2)
                            familyName = familyName ?: cursor.getString(3)
                        }
                        Phone.CONTENT_ITEM_TYPE -> phones += cursor.getString(1)
                        Email.CONTENT_ITEM_TYPE -> emails += cursor.getString(1)
                        StructuredPostal.CONTENT_ITEM_TYPE ->
                            // DATA4 = street, DATA7 = city, DATA8 = region, DATA9 = postcode, DATA10 = country.
                            addresses += DeviceContact.PostalAddress(
                                street = cursor.getString(4),
                                city = cursor.getString(5),
                                region = cursor.getString(6),
                                postcode = cursor.getString(7),
                                country = cursor.getString(8),
                            )
                    }
                }
            }

            DeviceContact(
                fullName = displayName,
                givenName = givenName,
                familyName = familyName,
                phones = phones,
                emails = emails,
                addresses = addresses,
            )
        }

    /**
     * Every contact on the device that has a phone number, with the details a Client takes from it, in one query.
     * Null when Android refuses the read (the permission is gone).
     */
    suspend fun readAll(context: Context): List<DeviceContact>? =
        withContext(Dispatchers.IO) {
            try {
                readAllContacts(context)
            } catch (_: SecurityException) {
                null
            }
        }

    private class Draft(val id: Long, val name: String?) {
        var givenName: String? = null
        var familyName: String? = null
        var note: String? = null
        val phones = mutableListOf<String?>()
        val addresses = mutableListOf<DeviceContact.PostalAddress>()
    }

    private fun readAllContacts(context: Context): List<DeviceContact> {
        val drafts = LinkedHashMap<Long, Draft>()
        val types = arrayOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE)

        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(
                Data.CONTACT_ID, Contacts.DISPLAY_NAME, Contacts.DISPLAY_NAME_SOURCE, Data.MIMETYPE,
                Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10,
            ),
            "${Data.MIMETYPE} IN (${types.joinToString(",") { "?" }})",
            types,
            "${Data.CONTACT_ID} ASC, ${Data.IS_SUPER_PRIMARY} DESC, ${Data.IS_PRIMARY} DESC, ${Data._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val draft = drafts.getOrPut(id) { Draft(id, savedName(cursor.getString(1), cursor.getInt(2))) }

                when (cursor.getString(3)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        draft.givenName = draft.givenName ?: cursor.getString(5)
                        draft.familyName = draft.familyName ?: cursor.getString(6)
                    }
                    Phone.CONTENT_ITEM_TYPE -> draft.phones += cursor.getString(4)
                    Note.CONTENT_ITEM_TYPE -> draft.note = draft.note ?: cursor.getString(4)
                    StructuredPostal.CONTENT_ITEM_TYPE -> draft.addresses += DeviceContact.PostalAddress(
                        street = cursor.getString(7),
                        city = cursor.getString(8),
                        region = cursor.getString(9),
                        postcode = cursor.getString(10),
                        country = cursor.getString(11),
                    )
                }
            }
        }

        return drafts.values.filter { it.phones.isNotEmpty() }.map { draft ->
            DeviceContact(
                fullName = draft.name,
                givenName = draft.givenName,
                familyName = draft.familyName,
                phones = draft.phones,
                addresses = draft.addresses,
                note = draft.note,
                id = draft.id,
            )
        }
    }
}
