package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.CameraCaptures
import com.northphoenix.hairdresserclientmanager.data.PendingPhoto
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.PhotoStatus
import com.northphoenix.hairdresserclientmanager.state.PhotoUpload
import com.northphoenix.hairdresserclientmanager.state.UploadState
import com.northphoenix.hairdresserclientmanager.ui.screens.hasPicture
import com.northphoenix.hairdresserclientmanager.ui.screens.photoTileDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The files the camera leaves in the cache, and what a screen reader is told about a photo tile. */
class PhotoDetailsTest {
    @get:Rule
    val device = TemporaryFolder()

    private val directory by lazy { File(device.root, "camera") }
    private var clock = 1_760_000_000_000L
    private val captures by lazy { CameraCaptures(directory) { clock } }

    private fun names() = directory.list().orEmpty().sorted()

    /** What the camera does with the file it was handed. */
    private fun File.written(at: Long = clock) = apply {
        writeText("jpeg")
        setLastModified(at)
    }

    // --- camera captures ---

    @Test
    fun `a capture is written into a file of its own in the camera's directory`() {
        val first = captures.newFile()
        clock += 1
        val second = captures.newFile()

        assertEquals(directory, first.parentFile)
        assertTrue(directory.isDirectory)
        assertNotEquals(first.name, second.name)
        // Handing the file out does not create it: the camera does, when there is a photo.
        assertFalse(first.exists())
    }

    @Test
    fun `a capture is deleted once it is no longer needed, and the others stay`() {
        val uploaded = captures.newFile().written()
        clock += 5_000
        val beingTaken = captures.newFile().written()

        captures.delete(uploaded.name)

        assertEquals(listOf(beingTaken.name), names())
    }

    @Test
    fun `deleting a capture that is gone or was never written is not an error`() {
        val cancelled = captures.newFile()

        captures.delete(cancelled.name)
        captures.delete(cancelled.name)

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `only a capture is deleted, whatever name it is asked with`() {
        val capture = captures.newFile().written()
        val other = File(directory, "notes.txt").written()
        val outside = File(device.root, "capture-1.jpg").written()
        val queued = File(File(device.root, "photo-uploads").apply { mkdirs() }, "capture-2.jpg").written()

        // The last part of a gallery address, a path out of the directory, no name at all.
        listOf(null, "", "1000000033", other.name, "../capture-1.jpg", "../photo-uploads/capture-2.jpg", "capture-.jpg", "capture-12.jpg.bak").forEach(captures::delete)

        assertEquals(listOf(capture.name, other.name).sorted(), names())
        assertTrue(outside.exists())
        assertTrue(queued.exists())
    }

    @Test
    fun `at start-up the captures nothing came back for are deleted`() {
        val leftBehind = captures.newFile().written()
        clock += 1
        val fromAnEarlierVersion = File(directory, "capture-1700000000000.jpg").written(at = 1_700_000_000_000L)

        clock += CameraCaptures.STALE_AFTER_MILLIS + 1
        captures.deleteStale()

        assertFalse(leftBehind.exists())
        assertFalse(fromAnEarlierVersion.exists())
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `at start-up the capture the Stylist is in the camera for is kept`() {
        // The process was ended behind the camera: the app starts again as the Stylist comes back with the photo.
        val old = captures.newFile().written()
        clock += CameraCaptures.STALE_AFTER_MILLIS + 60_000
        val beingTaken = captures.newFile().written(at = clock - 20 * 60_000)

        captures.deleteStale()

        assertFalse(old.exists())
        assertEquals(listOf(beingTaken.name), names())
    }

    @Test
    fun `start-up cleaning is not an error before the camera was ever used`() {
        captures.deleteStale()

        assertFalse(directory.exists())
    }

    // --- what a screen reader is told a photo tile is ---

    private fun photo(status: PhotoStatus, url: String = "") = AppointmentPhoto("photo-1", "client-1", PhotoCategory.AFTER, status, url)

    private fun upload(state: UploadState) =
        PhotoUpload(PendingPhoto("upload-1", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER), state, File(device.root, "upload-1.jpg"))

    @Test
    fun `a stored photo is told as a photo`() {
        assertEquals(R.string.appointment_photo_description, photoTileDescription(photo(PhotoStatus.STORED, "https://files.example/1")))
    }

    @Test
    fun `a photo recorded as failed does not sound like a stored one`() {
        assertEquals(R.string.appointment_photo_failed_description, photoTileDescription(photo(PhotoStatus.FAILED)))
        // Whatever the record carries: there is a picture to show only for a stored photo with an address.
        assertEquals(R.string.appointment_photo_failed_description, photoTileDescription(photo(PhotoStatus.FAILED, "https://files.example/1")))
        assertEquals(R.string.appointment_photo_failed_description, photoTileDescription(photo(PhotoStatus.PENDING_UPLOAD)))
        assertEquals(R.string.appointment_photo_failed_description, photoTileDescription(photo(PhotoStatus.STORED, url = " ")))
        assertFalse(photo(PhotoStatus.FAILED, "https://files.example/1").hasPicture)
        assertTrue(photo(PhotoStatus.STORED, "https://files.example/1").hasPicture)
    }

    @Test
    fun `a photo on its way is told as uploading and one that failed as not saved`() {
        assertEquals(R.string.appointment_photo_uploading_description, photoTileDescription(upload(UploadState.PREPARING)))
        assertEquals(R.string.appointment_photo_uploading_description, photoTileDescription(upload(UploadState.WAITING)))
        assertEquals(R.string.appointment_photo_uploading_description, photoTileDescription(upload(UploadState.UPLOADING)))
        assertEquals(R.string.appointment_photo_failed_description, photoTileDescription(upload(UploadState.FAILED)))
    }

    @Test
    fun `the three states of a tile are three different descriptions`() {
        val descriptions = setOf(
            photoTileDescription(photo(PhotoStatus.STORED, "https://files.example/1")),
            photoTileDescription(upload(UploadState.UPLOADING)),
            photoTileDescription(upload(UploadState.FAILED)),
        )

        assertEquals(3, descriptions.size)
    }
}
