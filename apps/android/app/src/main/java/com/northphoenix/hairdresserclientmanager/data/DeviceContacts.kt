package com.northphoenix.hairdresserclientmanager.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import com.northphoenix.hairdresserclientmanager.domain.DeviceContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads one contact the Stylist picked. Requires READ_CONTACTS; nothing is synced or stored. */
object DeviceContacts {
    suspend fun read(context: Context, contactUri: Uri): DeviceContact? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val (contactId, displayName) = resolver.query(
                contactUri,
                arrayOf(Contacts._ID, Contacts.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
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
}
