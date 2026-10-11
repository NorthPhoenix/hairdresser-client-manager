package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.PendingPhoto
import com.northphoenix.hairdresserclientmanager.data.PendingPhotoStore
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.StoredFile
import com.northphoenix.hairdresserclientmanager.data.toApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class UploadState {
    /** Just selected: the picture is on the device as it was picked and is being made into the JPEG to upload. */
    PREPARING,

    /** Its turn has not come yet: one picture is sent at a time. */
    WAITING,
    UPLOADING,

    /** The last try failed. It stays until the Stylist uploads it again or removes it. */
    FAILED,
}

/** A photo on its way to the server, as a screen shows it. [picture] is the prepared file on the device. */
data class PhotoUpload(val photo: PendingPhoto, val state: UploadState, val picture: File) {
    val id: String get() = photo.id
}

/** What pictures are added to. [replacingPhotoId] is a failed photo on the server whose place the first of them takes. */
data class PhotoTarget(
    val appointmentId: String,
    val clientId: String,
    val category: PhotoCategory,
    val replacingPhotoId: String? = null,
)

/**
 * The Stylist's photos on their way to the server. Each picture goes through:
 *
 *     selected -> PREPARING -> WAITING -> UPLOADING -> recorded by the server: gone from here
 *                                 ^           |
 *                                 |           v
 *                           'Upload again'  FAILED -> 'Remove photo': gone from here
 *
 * - A picture is kept by [store] from the moment it is selected until the server has recorded it
 *   or the Stylist removes it. So a picture that failed can be sent again, a camera capture is
 *   never only in a cache, and what Android interrupted goes on when the app is opened again.
 *   A selection is first copied as it is, which takes a moment, and only then made into JPEGs.
 * - Pictures are sent one at a time, oldest first, each as a try of its own: one that fails does
 *   not hold up the others.
 * - Nothing leaves a picture UPLOADING: a try that ends in any way other than success leaves it
 *   FAILED, also when a request it waited for was cancelled by someone else.
 * - The record's id is the request key of the photo, and a picture that is in photo storage is
 *   not uploaded a second time, so a try that is repeated never makes a second photo.
 * - The server can have recorded a photo whose try failed: the reply was lost. Such a picture is
 *   FAILED only until its Appointment is loaded with the photo ([isRecorded]); then it is gone
 *   from here like any recorded one, so no photo is shown as stored and as not saved at once.
 * - Of the failures that follow one thing the Stylist did (a selection, 'Upload again', opening
 *   the app), only the first is reported through [onFailure]: the pictures show the rest.
 * - A session that ends by itself keeps the pictures for when their Stylist is back ([close]). A
 *   sign-out the Stylist asked for deletes them ([forget]): the device is not where photos live.
 *
 * Not thread-safe: call it from the scope's own (main) thread.
 */
internal class PhotoUploads(
    private val scope: CoroutineScope,
    private val store: PendingPhotoStore,
    /** Makes the picked picture in this file into the JPEG to upload. Throws for a file that cannot be read as an image. */
    private val prepare: suspend (File) -> ByteArray,
    /** Puts the picture into photo storage. */
    private val send: suspend (PendingPhoto, ByteArray) -> StoredFile,
    /** Records the stored picture as an Appointment Photo. Asked again for the same photo, the server answers as the first time. */
    private val record: suspend (PendingPhoto, StoredFile) -> Unit,
    /** The Appointment of this photo has to be loaded again. [saved] says that the server has the photo for certain. */
    private val reload: suspend (PendingPhoto, saved: Boolean) -> Unit,
    /** Whether the Appointment of this photo, as it is loaded now, has the photo: then the server has recorded it, whatever became of the reply. */
    private val isRecorded: (PendingPhoto) -> Boolean,
    private val onFailure: (ApiException) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _uploads = MutableStateFlow<List<PhotoUpload>>(emptyList())

    /** Of the Stylist who is signed in, oldest first. */
    val uploads: StateFlow<List<PhotoUpload>> = _uploads.asStateFlow()

    private var stylistId: String? = null
    private var worker: Job? = null

    /** The picture that is being sent, with its try. */
    private var sending: Pair<String, Job>? = null
    private var failureTold = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** How many sign-outs have taken pictures off the device: a picture that was being copied across one is not kept. */
    private var signOuts = 0

    /** The pictures of the last sign-out being deleted. Reading the device waits for it. */
    private var deleting: Job? = null

    /**
     * Starts for the Stylist who has signed in: their pictures that are still on the device are
     * shown again. Those that had failed stay failed; the others were interrupted and go on.
     */
    fun open(stylistId: String) {
        if (this.stylistId == stylistId) {
            return
        }

        close()
        this.stylistId = stylistId
        worker = scope.launch {
            // Signed in again at once: what the sign-out deletes is not shown again.
            deleting?.join()
            val kept = quietly { withContext(io) { store.all() } }.orEmpty().filter { it.stylistId == stylistId }

            _uploads.update { added ->
                // A picture added while the device was being read is in both.
                val known = added.map { it.id }.toSet()

                kept.filterNot { it.id in known }.map { shown(it, if (it.failed) UploadState.FAILED else UploadState.WAITING) } + added
            }
            // The Appointments may have been loaded before the device was read.
            dropRecorded()
            sendInTurn()
        }
    }

    /** The Stylist is no longer signed in. Nothing more is sent, and the pictures stay on the device for when they are back. */
    fun close() {
        worker?.cancel()
        worker = null
        sending = null
        stylistId = null
        failureTold = false
        _uploads.value = emptyList()
    }

    /**
     * The Stylist has signed out, as they asked: nothing more is sent, and their pictures are
     * deleted from the device. Not sent, they are lost, which is why the Stylist is asked first.
     * Does nothing more than [close] when no Stylist's pictures are shown: none were asked about.
     */
    fun forget() {
        val owner = stylistId

        close()
        if (owner != null) {
            signOuts++
            // Also when the app is going away: the pictures do not stay behind.
            deleting = scope.launch { quietly { withContext(NonCancellable + io) { store.removeAllOf(owner) } } }
        }
    }

    /**
     * Takes a selection. Every picture is first copied to the device as [read] gives it, which is
     * quick: from then on the selection is not lost when the app is closed. Then each is made into
     * the JPEG to upload and takes its turn. A picture that cannot be read is left out and the
     * others are not affected by it; what went wrong with each is returned.
     */
    suspend fun <S> add(target: PhotoTarget, sources: List<S>, read: suspend (S) -> ByteArray): List<ApiException> {
        val owner = stylistId ?: return emptyList()
        val signOutsBefore = signOuts
        val failures = mutableListOf<ApiException>()
        val copied = mutableListOf<PendingPhoto>()

        failureTold = false
        for (source in sources) {
            // The Stylist has signed out meanwhile: the rest of the selection is not copied to the device.
            if (signOuts != signOutsBefore) {
                break
            }

            failures += failureOrNull {
                val bytes = read(source)
                val replacing = target.replacingPhotoId.takeIf { copied.isEmpty() }
                val photo = PendingPhoto(newId(), owner, target.appointmentId, target.clientId, target.category, replacing, now(), raw = true)

                withContext(io) { store.add(photo, bytes) }
                if (signOuts != signOutsBefore) {
                    // They signed out while this one was being copied, and it may have been written after their pictures were deleted.
                    withContext(io) { store.remove(photo.id) }
                    return@failureOrNull
                }
                copied += photo
                // Not shown to someone else who has signed in meanwhile. It is on the device for its Stylist.
                if (stylistId == owner) {
                    _uploads.update { it + shown(photo, UploadState.PREPARING) }
                }
            } ?: continue
        }

        for (photo in copied) {
            // Removed meanwhile, or its Stylist has signed out: then it is prepared when its turn comes after they are back.
            if (!isPreparing(photo.id)) {
                continue
            }

            val failure = failureOrNull {
                val ready = prepared(photo)

                show(photo.id) { if (it.state == UploadState.PREPARING) it.copy(photo = ready, state = UploadState.WAITING) else it }
                wake.trySend(Unit)
            }

            if (failure != null && isPreparing(photo.id)) {
                failures += failure
                hide(photo.id)
                quietly { withContext(io) { store.remove(photo.id) } }
            }
        }

        return failures
    }

    private fun isPreparing(id: String) = _uploads.value.any { it.id == id && it.state == UploadState.PREPARING }

    /** The photo with its picture made into the JPEG to upload. */
    private suspend fun prepared(photo: PendingPhoto): PendingPhoto {
        val jpeg = prepare(store.picture(photo.id))

        return photo.copy(raw = false).also { ready -> withContext(io) { store.prepared(ready, jpeg) } }
    }

    /** 'Upload again' for one failed picture. */
    fun retry(id: String) = retryAll { it.id == id }

    /** 'Upload again' for every failed picture that [which] picks. */
    fun retryAll(which: (PendingPhoto) -> Boolean) {
        val failed = _uploads.value.filter { it.state == UploadState.FAILED && which(it.photo) }.map { it.id }.toSet()

        if (failed.isEmpty()) {
            return
        }

        failureTold = false
        _uploads.update { all -> all.map { if (it.id in failed) it.copy(state = UploadState.WAITING) else it } }
        wake.trySend(Unit)
    }

    /** 'Remove photo' for a picture that is not on the server: it is deleted from the device and never sent. */
    fun remove(id: String) = removeAll { it.id == id }

    /** Removes every picture that [which] picks. The one that is being sent is stopped first. */
    fun removeAll(which: (PendingPhoto) -> Boolean) {
        val removed = _uploads.value.filter { which(it.photo) }.map { it.id }.toSet()

        if (removed.isEmpty()) {
            return
        }

        val stopped = sending?.takeIf { it.first in removed }?.second

        stopped?.cancel()
        _uploads.update { all -> all.filterNot { it.id in removed } }
        scope.launch {
            // Deleted once the try has let go of the files.
            stopped?.join()
            quietly { withContext(io) { removed.forEach(store::remove) } }
        }
    }

    /**
     * Call this whenever Appointments have been loaded: every failed picture whose photo its
     * Appointment turns out to have ([isRecorded]) is done with. Only failed ones: a picture that
     * is waiting or being sent is recorded again by its own try, which the server answers as the first.
     */
    fun dropRecorded() {
        val recorded = _uploads.value.filter { it.state == UploadState.FAILED && isRecorded(it.photo) }.map { it.id }.toSet()

        if (recorded.isEmpty()) {
            return
        }

        _uploads.update { all -> all.filterNot { it.id in recorded } }
        scope.launch { quietly { withContext(io) { recorded.forEach(store::remove) } } }
    }

    private suspend fun sendInTurn() =
        coroutineScope {
            while (true) {
                val next = _uploads.value.firstOrNull { it.state == UploadState.WAITING }

                if (next == null) {
                    wake.receive()
                    continue
                }

                show(next.id) { it.copy(state = UploadState.UPLOADING) }
                // A job of its own, so that 'Remove photo' can stop this try and nothing else. It ends with the worker.
                val attempt = launch { attempt(next.photo) }

                sending = next.id to attempt
                attempt.join()
                sending = null
            }
        }

    private suspend fun attempt(waiting: PendingPhoto) {
        var photo = waiting

        try {
            val failure = failureOrNull {
                if (photo.raw) {
                    // The app was closed before this picture of a selection had been prepared.
                    photo = prepared(photo)
                    show(photo.id) { it.copy(photo = photo) }
                }

                val stored = photo.stored ?: send(photo, withContext(io) { store.picture(photo.id).readBytes() }).also { file ->
                    // Kept before it is recorded: another try records this file instead of uploading the picture again.
                    photo = photo.copy(stored = file)
                    show(photo.id) { it.copy(photo = photo) }
                    withContext(io) { store.update(photo) }
                }

                record(photo, stored)
            }

            settle(photo, failure)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The device's own storage failed while the outcome was being kept.
            tell(error.toApiException())
        } finally {
            // However the try ended, the picture does not go on looking as if it were being sent.
            show(photo.id) { if (it.state == UploadState.UPLOADING) it.copy(state = UploadState.FAILED) else it }
        }
    }

    private suspend fun settle(photo: PendingPhoto, failure: ApiException?) {
        when {
            // A conflict is the server's answer for a photo that an earlier try recorded without
            // its reply arriving, and whose category the Stylist has changed since: it has the photo.
            failure == null || failure.code == "CONFLICT" -> {
                // Gone from here in the same moment as [record] returns: whoever shows the photo as stored from then on shows it once.
                hide(photo.id)
                withContext(io) { store.remove(photo.id) }
                quietly { reload(photo, true) }
            }
            // The Appointment is gone, so there is nothing to add the photo to and no screen to show it on.
            failure.isNotFound -> {
                withContext(io) { store.remove(photo.id) }
                hide(photo.id)
                tell(failure)
                quietly { reload(photo, false) }
            }
            else -> {
                // A stored file the server does not take as a photo is refused every time it is sent: the next try uploads the picture anew.
                val kept = photo.copy(failed = true, stored = photo.stored.takeUnless { failure.isStoredFileRefused })

                show(photo.id) { it.copy(photo = kept, state = UploadState.FAILED) }
                tell(failure)
                withContext(io) { store.update(kept) }
                if (failure.outcomeUnknown || failure.screenOutOfDate) {
                    // The photo may have been recorded although the reply never came: the screen shows whether it was.
                    // Or its Client is no longer in the Appointment: the screen stops listing them.
                    quietly { reload(photo, false) }
                }
                if (isRecorded(photo)) {
                    // It was, and the Appointment shows the photo: this is not a picture that was not saved.
                    hide(photo.id)
                    withContext(io) { store.remove(photo.id) }
                }
            }
        }
    }

    /**
     * What went wrong in [block], if anything. A cancellation that is not this coroutine's own
     * is a failure like any other: a request the upload waited for was cancelled by someone else
     * (the sign-in provider drops the token request that everyone shares when it restarts its
     * refresh), and passing it on would end the try without a word.
     */
    private suspend fun failureOrNull(block: suspend () -> Unit): ApiException? =
        try {
            block()
            null
        } catch (error: CancellationException) {
            // Our own: 'Remove photo', a sign-out or the app going away.
            currentCoroutineContext().ensureActive()
            ApiException("A request the upload waited for was cancelled.", code = "INTERRUPTED", cause = error)
        } catch (error: Exception) {
            error.toApiException()
        }

    /**
     * Runs [block] for something a failure of which is not the Stylist's to act on here: the
     * device's storage, a reload. As in [failureOrNull], only this coroutine's own cancellation is passed on.
     */
    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        }

    private fun tell(failure: ApiException) {
        if (!failureTold) {
            failureTold = true
            onFailure(failure)
        }
    }

    private fun shown(photo: PendingPhoto, state: UploadState) = PhotoUpload(photo, state, store.picture(photo.id))

    private fun show(id: String, change: (PhotoUpload) -> PhotoUpload) {
        _uploads.update { all -> all.map { if (it.id == id) change(it) else it } }
    }

    private fun hide(id: String) {
        _uploads.update { all -> all.filterNot { it.id == id } }
    }
}
