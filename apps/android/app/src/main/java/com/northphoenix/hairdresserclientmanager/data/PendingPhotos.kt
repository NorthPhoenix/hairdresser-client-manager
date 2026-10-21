package com.northphoenix.hairdresserclientmanager.data

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/**
 * A picture in photo storage, as the upload answered. It is what `appointment.addPhoto` records,
 * together with the size of the picture that was uploaded, when the app could read it: null in a
 * record written before the app read it.
 */
@Serializable
data class StoredFile(
    val fileKey: String,
    val url: String,
    val thumbnailUrl: String,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * An Appointment Photo on its way to the server. The prepared picture waits in the app's own
 * storage next to this record until the server has the photo or the Stylist removes it, so a
 * failed upload and one that Android interrupted can both be tried again.
 * The device is still not where Appointment Photos live: this is a queue, emptied by every success.
 */
@Serializable
data class PendingPhoto(
    /** Also sent as the request key of `appointment.addPhoto`: the same picture sent again is the same photo. */
    val id: String,
    /** Whose it is. Nobody else who signs in on this device sees or uploads it. */
    val stylistId: String,
    val appointmentId: String,
    val clientId: String,
    val category: PhotoCategory,
    /** A failed photo on the server that this one takes the place of. */
    val replacingPhotoId: String? = null,
    val createdAt: Long = 0,
    /**
     * The picture is still the file that was picked or captured, copied as it was so that the
     * selection is on the device at once. It is made into the JPEG to upload before it is sent.
     */
    val raw: Boolean = false,
    /** The last try failed. Such a photo waits for the Stylist; any other is tried again when the app starts. */
    val failed: Boolean = false,
    /** Set once the picture is in photo storage. What is left then is to record it, and a new try does only that. */
    val stored: StoredFile? = null,
)

/**
 * Keeps the photos on their way in [directory]: the picture `<id>.jpg` and its record `<id>.json`.
 * Everything here reads or writes files, so call it off the main thread.
 */
class PendingPhotoStore(private val directory: File) {
    /** The prepared picture of a photo, for uploading it and for showing it. */
    fun picture(id: String): File = File(directory, "$id$PICTURE")

    private fun record(id: String): File = File(directory, "$id$RECORD")

    /** Every photo that has both its record and its picture, oldest first. Whatever else lies in the directory is deleted. */
    @Synchronized
    fun all(): List<PendingPhoto> {
        val photos = directory.listFiles().orEmpty().filter { it.name.endsWith(RECORD) }.mapNotNull { file ->
            try {
                hcmJson.decodeFromString(PendingPhoto.serializer(), file.readText())
                    .takeIf { it.id == file.name.removeSuffix(RECORD) && picture(it.id).isFile }
            } catch (_: Exception) {
                null
            }
        }
        val kept = photos.flatMap { listOf(picture(it.id), record(it.id)) }.toSet()

        // A picture whose record was never written, a record that cannot be read, a half-written file.
        directory.listFiles().orEmpty().filterNot { it in kept }.forEach { it.delete() }

        return photos.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    /** The picture first: a record always has its picture. */
    @Synchronized
    fun add(photo: PendingPhoto, bytes: ByteArray) {
        directory.mkdirs()
        picture(photo.id).writeBytes(bytes)
        write(photo)
    }

    /** Does nothing for a photo that has been removed. */
    @Synchronized
    fun update(photo: PendingPhoto) {
        if (picture(photo.id).isFile) {
            write(photo)
        }
    }

    /** Puts the JPEG to upload in the place of the picture as it was picked. Does nothing for a photo that has been removed. */
    @Synchronized
    fun prepared(photo: PendingPhoto, jpeg: ByteArray) {
        if (record(photo.id).isFile) {
            replace(picture(photo.id)) { it.writeBytes(jpeg) }
            write(photo.copy(raw = false))
        }
    }

    @Synchronized
    fun remove(id: String) {
        record(id).delete()
        picture(id).delete()
    }

    /** Deletes every photo of this Stylist. Only for their sign-out: the device then keeps no picture of their Clients. */
    @Synchronized
    fun removeAllOf(stylistId: String) {
        all().filter { it.stylistId == stylistId }.forEach { remove(it.id) }
    }

    private fun write(photo: PendingPhoto) = replace(record(photo.id)) { it.writeText(hcmJson.encodeToString(PendingPhoto.serializer(), photo)) }

    // Written beside the file and moved over it, so the file is never half of one.
    private fun replace(file: File, write: (File) -> Unit) {
        val draft = File(directory, "${file.name}.tmp")

        write(draft)
        if (!draft.renameTo(file)) {
            draft.delete()
            throw IOException("Could not keep ${file.name}.")
        }
    }

    private companion object {
        const val PICTURE = ".jpg"
        const val RECORD = ".json"
    }
}
