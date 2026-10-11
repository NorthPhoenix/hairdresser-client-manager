package com.northphoenix.hairdresserclientmanager.data

import java.io.File

/**
 * What the app hands to other apps through the share sheet: the Share Image, one file in the app's
 * cache that every share writes anew. Another app reads it through the file provider
 * (`res/xml/file_paths.xml` names the same directory), so the file has to outlive the share. It
 * lies there until the next share, or until the Stylist signs out.
 *
 * Plain `java.io`: call it off the main thread.
 */
class SharedPictures(private val directory: File) {
    /** The file to write the Share Image of this share into. */
    fun shareImage(): File {
        directory.mkdirs()

        return File(directory, "share-image.png")
    }

    /** Deletes what was shared. Only for a sign-out: nothing of the Stylist's Clients stays on the device. */
    fun deleteAll() {
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    companion object {
        /** The directory's name in the app's cache. */
        const val DIRECTORY = "shared"
    }
}
