package com.northphoenix.hairdresserclientmanager.data

import java.io.File

/**
 * The files the camera writes its captures into, in the app's cache. A capture is needed only
 * until the upload queue has its own copy of it, which it takes as soon as the Stylist comes back
 * from the camera: the file is deleted then, and also when they come back without a photo.
 *
 * A capture nothing came back for (the app was closed in between) is deleted the next time the
 * app starts, once it is older than [STALE_AFTER_MILLIS]. A newer one is left: it may be the photo
 * the Stylist is taking right now, with the app's process ended behind the camera.
 *
 * Plain `java.io`. The deletes block: call them off the main thread.
 */
class CameraCaptures(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    /** A file for the camera to write the next capture into. */
    fun newFile(): File {
        directory.mkdirs()

        return File(directory, "capture-${now()}.jpg")
    }

    /** Deletes the capture called [name]. Any other name is left alone: a photo picked from the gallery has one too. */
    fun delete(name: String?) {
        if (name != null && CAPTURE_NAME.matches(name)) {
            File(directory, name).delete()
        }
    }

    /** Deletes what the camera left here and nothing came back for. */
    fun deleteStale() {
        val before = now() - STALE_AFTER_MILLIS

        directory.listFiles()?.filter { it.isFile && it.lastModified() < before }?.forEach { it.delete() }
    }

    companion object {
        /** A day: far longer than anyone stays in the camera, so a capture that is on its way back is never taken for a leftover. */
        const val STALE_AFTER_MILLIS = 24 * 60 * 60 * 1000L

        /** What [newFile] calls a capture. */
        private val CAPTURE_NAME = Regex("""capture-\d+\.jpg""")
    }
}
