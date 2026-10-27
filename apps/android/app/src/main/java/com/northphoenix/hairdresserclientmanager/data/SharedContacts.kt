package com.northphoenix.hairdresserclientmanager.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.northphoenix.hairdresserclientmanager.domain.ImportedContact
import com.northphoenix.hairdresserclientmanager.domain.SharedContact
import com.northphoenix.hairdresserclientmanager.domain.VCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads a contact shared to the app from another one ([SharedContact]). Nothing is synced or stored. */
object SharedContacts {
    /**
     * A shared file is read only when it is a contact card: a text file can be a whole chat that
     * WhatsApp exports, with the numbers of everyone in it. A shared text is read as it is.
     */
    suspend fun read(context: Context, intent: Intent): ImportedContact =
        withContext(Dispatchers.IO) {
            val card = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { uri -> readText(context, uri) }
                ?.takeIf(VCard::isCard)
            val text = card ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

            text?.let(SharedContact::read) ?: ImportedContact()
        }

    /** The start of the file, as far as [SharedContact.MAX_LENGTH] bytes; none when the app sharing it does not let it be read. */
    private fun readText(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readAtMost(SharedContact.MAX_LENGTH).toString(Charsets.UTF_8)
            }
        }.getOrNull()

    /** InputStream.readNBytes is newer than the oldest Android the app runs on. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)

        while (bytes.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - bytes.size()))

            if (read < 0) {
                break
            }
            bytes.write(buffer, 0, read)
        }
        return bytes.toByteArray()
    }
}
