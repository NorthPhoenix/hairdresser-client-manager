package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.auth.askingAgainWhenDropped
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.PendingPhoto
import com.northphoenix.hairdresserclientmanager.data.PendingPhotoStore
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.PhotoStatus
import com.northphoenix.hairdresserclientmanager.data.StoredFile
import com.northphoenix.hairdresserclientmanager.state.Notice
import com.northphoenix.hairdresserclientmanager.state.PhotoTarget
import com.northphoenix.hairdresserclientmanager.state.PhotoUploads
import com.northphoenix.hairdresserclientmanager.state.UploadState
import com.northphoenix.hairdresserclientmanager.state.hasStored
import com.northphoenix.hairdresserclientmanager.state.unreadPhotosNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** The photos on their way to the server: what is kept on the device, and what each outcome of an upload leaves behind. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoUploadsTest {
    @get:Rule
    val device = TemporaryFolder()

    private val directory by lazy { File(device.root, "photo-uploads") }
    private val store by lazy { PendingPhotoStore(directory) }

    private val target = PhotoTarget("appointment-1", "client-1", PhotoCategory.AFTER)
    private val failures = mutableListOf<ApiException>()
    private val sent = mutableListOf<String>()
    private val sentPictures = mutableListOf<String>()
    private val recorded = mutableListOf<Pair<String, StoredFile>>()
    private val reloads = mutableListOf<Pair<String, Boolean>>()

    /** The addresses of the photos that the Appointments, as they are loaded, have. */
    private val loadedPhotos = mutableSetOf<String>()
    private var ids = 0

    // What becomes of a picture. Each test replaces what it needs to go wrong.
    private var prepare: suspend (File) -> ByteArray = { it.readBytes() }
    private var send: suspend (PendingPhoto) -> StoredFile = ::file
    private var record: suspend (PendingPhoto) -> Unit = {}
    private var reload: suspend () -> Unit = {}

    private fun file(photo: PendingPhoto) = StoredFile("key-${photo.id}", "https://files.example/${photo.id}", "https://files.example/${photo.id}")

    private fun offline() = ApiException("Network request failed.", code = "NETWORK", isNetwork = true)

    private fun TestScope.uploads(stylistId: String? = "stylist-1") =
        PhotoUploads(
            backgroundScope,
            store,
            prepare = { prepare(it) },
            send = { photo, bytes ->
                sent += photo.id
                sentPictures += String(bytes)
                send(photo)
            },
            record = { photo, stored ->
                record(photo)
                recorded += photo.id to stored
            },
            reload = { photo, saved ->
                reloads += photo.appointmentId to saved
                reload()
            },
            isRecorded = { it.stored?.url in loadedPhotos },
            onFailure = { failures += it },
            io = UnconfinedTestDispatcher(testScheduler),
            newId = { "photo-${++ids}" },
            now = { ids.toLong() },
        ).also { queue ->
            stylistId?.let(queue::open)
            // As in the app, where what is on the device has been read long before the Stylist can do anything.
            runCurrent()
        }

    private suspend fun PhotoUploads.addPictures(count: Int, to: PhotoTarget = target) =
        add(to, List(count) { "picture-${it + 1}" }) { it.toByteArray() }

    private fun PhotoUploads.states() = uploads.value.map { it.id to it.state }

    // --- the device ---

    @Test
    fun `a photo is kept with its picture until it is removed`() {
        val photo = PendingPhoto("photo-1", "stylist-1", "appointment-1", "client-1", PhotoCategory.BEFORE, createdAt = 5)

        store.add(photo, "jpeg".toByteArray())
        store.update(photo.copy(failed = true, stored = StoredFile("key", "https://files.example/a", "https://files.example/a")))

        val kept = PendingPhotoStore(directory).all().single()

        assertEquals("photo-1", kept.id)
        assertTrue(kept.failed)
        assertEquals("key", kept.stored?.fileKey)
        assertEquals("jpeg", store.picture("photo-1").readText())

        // The JPEG to upload takes the place of the picture as it was picked.
        store.add(photo.copy(id = "photo-2", raw = true), "as picked".toByteArray())
        store.prepared(photo.copy(id = "photo-2", raw = true), "jpeg".toByteArray())
        assertEquals(listOf(false, false), store.all().map { it.raw })
        assertEquals("jpeg", store.picture("photo-2").readText())

        store.remove("photo-1")
        store.remove("photo-2")
        // What arrives after the removal does not bring the photo back.
        store.update(photo)
        store.prepared(photo, "jpeg".toByteArray())

        assertTrue(store.all().isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `what is not a photo with its record and its picture is cleared away`() {
        store.add(PendingPhoto("photo-2", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER, createdAt = 2), byteArrayOf(2))
        store.add(PendingPhoto("photo-1", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER, createdAt = 1), byteArrayOf(1))
        // A picture whose record was never written, a record that cannot be read, a record without its picture.
        File(directory, "photo-3.jpg").writeBytes(byteArrayOf(3))
        File(directory, "photo-4.jpg").writeBytes(byteArrayOf(4))
        File(directory, "photo-4.json").writeText("{ not a record")
        store.add(PendingPhoto("photo-5", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER), byteArrayOf(5))
        store.picture("photo-5").delete()

        // Oldest first.
        assertEquals(listOf("photo-1", "photo-2"), store.all().map { it.id })
        assertEquals(setOf("photo-1.jpg", "photo-1.json", "photo-2.jpg", "photo-2.json"), directory.list().orEmpty().toSet())
    }

    // --- one upload ---

    @Test
    fun `a picture is on the device until the server has recorded the photo`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val queue = uploads()

        record = { gate.await() }
        assertTrue(queue.addPictures(1).isEmpty())
        runCurrent()

        assertEquals(listOf("photo-1" to UploadState.UPLOADING), queue.states())
        assertEquals("picture-1", queue.uploads.value.single().picture.readText())
        // Kept as stored before it is recorded: the picture is not uploaded a second time.
        assertEquals(file(store.all().single()), store.all().single().stored)

        gate.complete(Unit)
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(store.all().isEmpty())
        assertEquals(listOf("photo-1"), recorded.map { it.first })
        assertEquals(listOf("appointment-1" to true), reloads)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a failed upload leaves a failed photo that can be uploaded again or removed`() = runTest {
        val queue = uploads()

        send = { throw offline() }
        queue.addPictures(2)
        runCurrent()

        // Nothing is lost and nothing goes on looking as if it were uploading.
        assertEquals(listOf("photo-1" to UploadState.FAILED, "photo-2" to UploadState.FAILED), queue.states())
        assertEquals(listOf(true, true), store.all().map { it.failed })
        assertTrue(recorded.isEmpty())

        send = ::file
        queue.retry("photo-1")
        runCurrent()

        assertEquals(listOf("photo-2" to UploadState.FAILED), queue.states())
        assertEquals(listOf("photo-1"), recorded.map { it.first })

        queue.remove("photo-2")
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(listOf("photo-1"), recorded.map { it.first })
    }

    @Test
    fun `a request cancelled by someone else leaves the photo failed and the next one is sent`() = runTest {
        val queue = uploads()

        // What the sign-in provider does to everyone who waits for the token request it drops.
        send = { photo -> if (photo.id == "photo-1") throw CancellationException("The token request was cancelled.") else file(photo) }
        queue.addPictures(2)
        runCurrent()

        assertEquals(listOf("photo-1" to UploadState.FAILED), queue.states())
        assertEquals(listOf("photo-2"), recorded.map { it.first })
        assertEquals(listOf("INTERRUPTED"), failures.map { it.code })

        send = ::file
        queue.retry("photo-1")
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertEquals(listOf("photo-2", "photo-1"), recorded.map { it.first })
    }

    @Test
    fun `whatever an upload throws, no photo stays uploading`() = runTest {
        val queue = uploads()

        send = { throw IllegalStateException("boom") }
        record = { throw IllegalStateException("boom") }
        queue.addPictures(1)
        runCurrent()

        assertEquals(listOf("photo-1" to UploadState.FAILED), queue.states())
        assertEquals(listOf("UNEXPECTED"), failures.map { it.code })
    }

    @Test
    fun `a picture that is in photo storage is only recorded again, as the same photo`() = runTest {
        val queue = uploads()

        record = { throw ApiException("No reply from the server.", code = ApiException.NO_REPLY) }
        queue.addPictures(1)
        runCurrent()

        assertEquals(listOf("photo-1" to UploadState.FAILED), queue.states())
        assertEquals(listOf("photo-1"), sent)
        // The photo may have been recorded: the Appointment is loaded again, and nothing is called saved.
        assertEquals(listOf("appointment-1" to false), reloads)

        record = {}
        queue.retry("photo-1")
        runCurrent()

        assertEquals(listOf("photo-1"), sent)
        assertEquals(listOf("photo-1" to file(PendingPhoto("photo-1", "", "", "", PhotoCategory.AFTER))), recorded)
        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a recorded photo is done with, whatever becomes of the reload after it`() = runTest {
        val queue = uploads()

        reload = { throw CancellationException("The token request was cancelled.") }
        queue.addPictures(1)
        runCurrent()
        reload = { throw offline() }
        queue.addPictures(1)
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(store.all().isEmpty())
        assertEquals(listOf("photo-1", "photo-2"), recorded.map { it.first })
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a photo the server already has counts as recorded`() = runTest {
        val queue = uploads()

        // An earlier try recorded it without its reply arriving, and its category was changed since.
        record = { throw ApiException("This request was already carried out with other values.", code = "CONFLICT") }
        queue.addPictures(1)
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(store.all().isEmpty())
        assertEquals(listOf("appointment-1" to true), reloads)
        assertTrue(failures.isEmpty())
    }

    // --- recorded although the try failed ---

    @Test
    fun `a photo recorded without its reply arriving is done with when the reload shows it`() = runTest {
        val queue = uploads()

        // The server recorded the photo and its reply was lost. The Appointment comes back with the photo.
        record = { throw ApiException("No reply from the server.", code = ApiException.NO_REPLY) }
        reload = { loadedPhotos += "https://files.example/photo-1" }
        queue.addPictures(1)
        runCurrent()

        // Not a photo that is stored and a picture that says it was not saved.
        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(listOf("appointment-1" to false), reloads)
        // What happened is still said: the reply did not come.
        assertEquals(listOf(ApiException.NO_REPLY), failures.map { it.code })
    }

    @Test
    fun `a failed picture is done with when its Appointment is loaded with the photo`() = runTest {
        val queue = uploads()

        // Recorded without a reply while the device was offline, so the reload failed too.
        record = { throw ApiException("No reply from the server.", code = ApiException.NO_REPLY) }
        reload = { throw offline() }
        queue.addPictures(1)
        runCurrent()
        // This one never reached photo storage.
        send = { throw offline() }
        queue.addPictures(1)
        runCurrent()
        queue.dropRecorded()
        runCurrent()

        assertEquals(listOf("photo-1" to UploadState.FAILED, "photo-2" to UploadState.FAILED), queue.states())

        // Later the Appointment is loaded, and it has the first photo.
        loadedPhotos += "https://files.example/photo-1"
        queue.dropRecorded()
        runCurrent()

        assertEquals(listOf("photo-2" to UploadState.FAILED), queue.states())
        assertEquals(listOf("photo-2"), store.all().map { it.id })
        assertEquals(listOf("photo-2.jpg", "photo-2.json"), directory.list().orEmpty().sorted())
    }

    @Test
    fun `a try that fails after the photo was recorded and shown leaves no failed picture`() = runTest {
        val queue = uploads()

        // The photo is recorded and in the loaded Appointment, and then something else in the step fails.
        record = { photo ->
            loadedPhotos += file(photo).url
            throw offline()
        }
        queue.addPictures(1)
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a picture that is being sent is left to its own try when its photo shows`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val queue = uploads()

        record = { photo ->
            loadedPhotos += file(photo).url
            gate.await()
        }
        queue.addPictures(1)
        runCurrent()
        queue.dropRecorded()
        runCurrent()

        // Not stopped half-way: what follows the record step is still to come.
        assertEquals(listOf("photo-1" to UploadState.UPLOADING), queue.states())
        assertEquals(1, store.all().size)

        gate.complete(Unit)
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertEquals(listOf("photo-1"), recorded.map { it.first })
        assertEquals(listOf("appointment-1" to true), reloads)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a failed picture whose photo is loaded before the device is read is not shown again`() = runTest {
        val stored = StoredFile("key", "https://files.example/kept", "https://files.example/kept")

        store.add(PendingPhoto("recorded", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER, createdAt = 1, failed = true, stored = stored), byteArrayOf(1))
        store.add(PendingPhoto("failed", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER, createdAt = 2, failed = true), byteArrayOf(2))
        loadedPhotos += stored.url

        val queue = uploads()

        assertEquals(listOf("failed" to UploadState.FAILED), queue.states())
        assertEquals(listOf("failed"), store.all().map { it.id })
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `an Appointment has the photo of a picture when one of its photos is at the picture's address`() {
        val stored = StoredFile("key-1", "https://files.example/key-1", "https://files.example/key-1")
        val others = listOf(
            AppointmentPhoto("failed", "client-1", PhotoCategory.AFTER, PhotoStatus.FAILED),
            AppointmentPhoto("another", "client-1", PhotoCategory.AFTER, url = "https://files.example/key-2"),
        )

        assertFalse(others.hasStored(stored))
        assertTrue((others + AppointmentPhoto("this", "client-1", PhotoCategory.BEFORE, url = stored.url)).hasStored(stored))
    }

    @Test
    fun `a photo whose Appointment is gone is dropped and said to be`() = runTest {
        val queue = uploads()

        record = { throw ApiException("Appointment not found.", code = "NOT_FOUND") }
        queue.addPictures(1)
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(store.all().isEmpty())
        assertEquals(listOf("NOT_FOUND"), failures.map { it.code })
        assertEquals(listOf("appointment-1" to false), reloads)
    }

    @Test
    fun `a photo for a Client who is no longer in the Appointment stays on the device and the Appointment is loaded again`() = runTest {
        val queue = uploads()

        // L29: the Client was removed from the Appointment on another device while this one still listed them.
        record = { throw ApiException("Photo Client must be attached to the Appointment.", code = "BAD_REQUEST") }
        queue.addPictures(1)
        runCurrent()

        // The picture is not thrown away: it is the Stylist's, and the Client can be added again.
        assertEquals(listOf("photo-1" to UploadState.FAILED), queue.states())
        assertEquals(listOf(true), store.all().map { it.failed })
        assertEquals(listOf("Photo Client must be attached to the Appointment."), failures.map { it.message })
        // The screen stops listing the Client.
        assertEquals(listOf("appointment-1" to false), reloads)
    }

    // --- a selection ---

    @Test
    fun `a picture that cannot be read is left out and the others are uploaded`() = runTest {
        val queue = uploads()

        prepare = { file -> if (file.readText() == "not an image") throw ApiException("The selected image could not be read.", code = "BAD_IMAGE") else file.readBytes() }
        val unread = queue.add(target, listOf("good-1", "gone", "not an image", "good-2")) { source ->
            // What the gallery answers for a file that was deleted after it was picked.
            if (source == "gone") throw FileNotFoundException("open failed: ENOENT") else source.toByteArray()
        }
        runCurrent()

        assertEquals(listOf("UNEXPECTED", "BAD_IMAGE"), unread.map { it.code })
        assertEquals(listOf("good-1", "good-2"), sentPictures)
        assertEquals(2, recorded.size)
        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        // Told by whoever asked for the selection, with the count. Not as a failed upload.
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `the Stylist is told how many pictures of a selection were left out`() {
        val unreadable = ApiException("The selected image could not be read.", code = "BAD_IMAGE")
        val tooLarge = ApiException("The photo is larger than 8 MB.", code = "PHOTO_TOO_LARGE")

        assertNull(unreadPhotosNotice(selected = 3, unread = emptyList()))
        // The only picture: what was wrong with it.
        assertEquals(Notice(R.string.error_image_unreadable, isError = true), unreadPhotosNotice(1, listOf(unreadable)))
        assertEquals(Notice(R.string.appointment_photo_too_large, isError = true), unreadPhotosNotice(1, listOf(tooLarge)))
        // Some of several: how many of how many, because the others were added.
        assertEquals(
            Notice(R.string.appointment_photos_some_unread, isError = true, args = listOf(1, 3)),
            unreadPhotosNotice(3, listOf(unreadable)),
        )
        assertEquals(
            Notice(R.string.appointment_photos_some_unread, isError = true, args = listOf(2, 9)),
            unreadPhotosNotice(9, listOf(unreadable, tooLarge)),
        )
        assertEquals(Notice(R.string.appointment_photos_none_read, isError = true), unreadPhotosNotice(2, listOf(unreadable, unreadable)))
    }

    @Test
    fun `a whole selection is on the device and on screen before the first picture is prepared`() = runTest {
        val queue = uploads()
        val kept = mutableListOf<Int>()
        val shown = mutableListOf<Int>()

        send = { awaitCancellation() }
        prepare = { file ->
            kept += store.all().size
            shown += queue.uploads.value.size
            "jpeg of ${file.readText()}".toByteArray()
        }
        queue.addPictures(3)

        // Copied as picked first, which is quick: closing the app while they are prepared loses none.
        assertEquals(listOf(3, 3, 3), kept)
        assertEquals(listOf(3, 3, 3), shown)
        assertEquals(listOf(false, false, false), store.all().map { it.raw })
        assertEquals("jpeg of picture-2", store.picture("photo-2").readText())

        runCurrent()
        assertEquals(listOf(UploadState.UPLOADING, UploadState.WAITING, UploadState.WAITING), queue.uploads.value.map { it.state })
        assertEquals(listOf("jpeg of picture-1"), sentPictures)
    }

    @Test
    fun `a picture removed while its selection is prepared is not counted as unreadable`() = runTest {
        val queue = uploads()

        prepare = { file ->
            if (file.readText() == "picture-1") queue.remove("photo-2")
            file.readBytes()
        }
        val unread = queue.addPictures(3)
        runCurrent()

        assertTrue(unread.isEmpty())
        assertEquals(listOf("photo-1", "photo-3"), recorded.map { it.first })
        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a failed photo on the server is replaced by the first picture only`() = runTest {
        val queue = uploads()

        send = { awaitCancellation() }
        queue.addPictures(2, to = target.copy(replacingPhotoId = "failed-photo"))

        assertEquals(listOf("failed-photo", null), queue.uploads.value.map { it.photo.replacingPhotoId })
    }

    @Test
    fun `of the failures after one selection only the first is reported, and again after Upload again`() = runTest {
        val queue = uploads()

        send = { throw offline() }
        queue.addPictures(3)
        runCurrent()

        assertEquals(3, queue.uploads.value.count { it.state == UploadState.FAILED })
        assertEquals(1, failures.size)

        queue.retryAll { it.appointmentId == "appointment-1" }
        runCurrent()

        assertEquals(3, queue.uploads.value.count { it.state == UploadState.FAILED })
        assertEquals(2, failures.size)
        assertEquals(6, sent.size)
    }

    // --- removing ---

    @Test
    fun `removing the photo that is being sent stops it and the next one goes on`() = runTest {
        val queue = uploads()
        val never = CompletableDeferred<StoredFile>()

        send = { photo -> if (photo.id == "photo-1") never.await() else file(photo) }
        queue.addPictures(2)
        runCurrent()
        assertEquals(listOf("photo-1" to UploadState.UPLOADING, "photo-2" to UploadState.WAITING), queue.states())

        queue.remove("photo-1")
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(listOf("photo-2"), recorded.map { it.first })
        assertTrue(failures.isEmpty())
    }

    // --- the app closes, the Stylist signs out ---

    @Test
    fun `what was interrupted goes on when the app is opened again, and what had failed stays failed`() = runTest {
        // Left by the app as Android killed it: one photo was on its way, one had failed, one had not been
        // prepared yet, one is another Stylist's.
        store.add(PendingPhoto("interrupted", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER, createdAt = 1), "jpeg".toByteArray())
        store.add(PendingPhoto("failed", "stylist-1", "appointment-2", "client-2", PhotoCategory.BEFORE, createdAt = 2, failed = true), byteArrayOf(2))
        store.add(PendingPhoto("theirs", "stylist-2", "appointment-9", "client-9", PhotoCategory.OTHER, createdAt = 3), byteArrayOf(3))
        store.add(PendingPhoto("unprepared", "stylist-1", "appointment-1", "client-1", PhotoCategory.AFTER, createdAt = 4, raw = true), "as picked".toByteArray())
        prepare = { file -> "jpeg of ${file.readText()}".toByteArray() }

        val queue = uploads()

        assertEquals(listOf("interrupted", "unprepared"), recorded.map { it.first })
        // Only what was not prepared yet is prepared, when its turn comes.
        assertEquals(listOf("jpeg", "jpeg of as picked"), sentPictures)
        assertEquals(listOf("failed" to UploadState.FAILED), queue.states())
        assertEquals("appointment-2", queue.uploads.value.single().photo.appointmentId)
        // Nobody else's photo is shown, sent or deleted.
        assertEquals(listOf("failed", "theirs"), store.all().map { it.id })
    }

    @Test
    fun `a sign-out stops the uploads and keeps the pictures for their Stylist`() = runTest {
        val queue = uploads()
        val never = CompletableDeferred<StoredFile>()

        send = { never.await() }
        queue.addPictures(2)
        runCurrent()

        queue.close()
        runCurrent()
        never.complete(file(PendingPhoto("photo-1", "", "", "", PhotoCategory.AFTER)))
        runCurrent()

        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(recorded.isEmpty())
        assertEquals(2, store.all().size)
        // Nothing is added for nobody.
        assertTrue(queue.addPictures(1).isEmpty())
        assertEquals(2, store.all().size)

        // Someone else signs in: not theirs.
        queue.open("stylist-2")
        runCurrent()
        assertTrue(queue.uploads.value.isEmpty())
        assertTrue(recorded.isEmpty())

        send = ::file
        queue.open("stylist-1")
        runCurrent()

        assertEquals(listOf("photo-1", "photo-2"), recorded.map { it.first })
        assertTrue(store.all().isEmpty())
    }

    // --- the token request ---

    @Test
    fun `a token request the provider dropped is asked again`() = runTest {
        var asked = 0

        val token = askingAgainWhenDropped {
            if (++asked == 1) throw CancellationException("The shared token request was cancelled.")
            "token"
        }

        assertEquals("token", token)
        assertEquals(2, asked)
    }

    @Test
    fun `a token request dropped twice is a failure, not the end of whoever asked`() = runTest {
        var failure: ApiException? = null
        var reached = false

        val job = launch {
            try {
                askingAgainWhenDropped<String> { throw CancellationException("The shared token request was cancelled.") }
            } catch (error: ApiException) {
                failure = error
            }
            reached = true
        }
        runCurrent()

        assertEquals("TOKEN_REQUEST_DROPPED", failure?.code)
        assertTrue(reached)
        assertFalse(job.isCancelled)
    }

    @Test
    fun `a request that is cancelled itself does not ask for a token again`() = runTest {
        var asked = 0
        var failure: ApiException? = null

        val job = launch {
            try {
                askingAgainWhenDropped<String> {
                    asked++
                    awaitCancellation()
                }
            } catch (error: ApiException) {
                failure = error
            }
        }
        runCurrent()
        job.cancel()
        runCurrent()

        assertEquals(1, asked)
        assertNull(failure)
        assertTrue(job.isCancelled)
    }
}
