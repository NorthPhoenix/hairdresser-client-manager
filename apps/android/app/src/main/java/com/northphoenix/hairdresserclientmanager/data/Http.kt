package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * How many requests to one server the app lets run at once. OkHttp's own limit is 5 and holds
 * the others back until one of them is answered, while a reload of the lists asks for about a
 * dozen things together (the settings, the update offer, Today, Tomorrow, the Calendar's day and
 * months, the Client list, the Service Menu and its formula names, the screen that is open), up
 * to about 20 with every screen open. On a server that has just woken up each of the first five
 * takes about a second, so what came sixth or later, Today's list among them, left the phone only
 * then. This leaves room for a whole reload.
 */
const val MAX_REQUESTS_PER_HOST = 32

/**
 * The client of the app's own requests: to the API and the photo uploads (pictures are shown
 * through Coil's client, and the sign-in provider has one of its own). A whole reload goes to the
 * server at once (see [MAX_REQUESTS_PER_HOST]). Over HTTP/2 the requests share the connection
 * once one is open; when none is, as after a pause, several may open at the same moment and
 * OkHttp keeps one of them.
 */
fun appHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

val hcmJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * A failure the UI can show. [isNetwork] means there was no usable connection to the server;
 * for a change it also means the change was never sent.
 */
class ApiException(
    override val message: String,
    val code: String = "UNKNOWN",
    val isNetwork: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isUnauthorized: Boolean get() = code == "UNAUTHORIZED"

    /** Something the request refers to no longer exists on the server, so what the screen shows is out of date. */
    val isNotFound: Boolean get() = code == "NOT_FOUND"

    /**
     * A change was sent but the API's reply to it never came: none at all, or a gateway's failure
     * in its place. It may or may not have been saved.
     */
    val outcomeUnknown: Boolean get() = code == NO_REPLY

    /** The API refused what was sent to it: a value breaks one of its rules, or what is asked for cannot be done as things are. */
    val isRefusedInput: Boolean get() = code == "BAD_REQUEST"

    /** The change names a Client who is no longer one of the Appointment's Clients. */
    val isClientNotAttached: Boolean get() = code == "BAD_REQUEST" && message in CLIENT_NOT_ATTACHED

    /**
     * The API does not take the stored file as an Appointment Photo: it is not a file of this
     * server's photo storage, or another photo already names it. Sending the same file again is
     * refused again, so the picture has to be uploaded anew.
     */
    val isStoredFileRefused: Boolean get() = code == "BAD_REQUEST" && message in STORED_FILE_REFUSED

    /**
     * The API refused a change by who the Clients of an Appointment are now, or by what they have
     * in it. The app offers such a change only where its own copy of the Appointment allows it, so
     * that copy is out of date and the Appointment is loaded again.
     * A copy from an Appointment that has nothing to copy is such a change as well: the copy sheet
     * lists only Appointments that have, so its list is out of date.
     * So are a save from a form that is older than its record, and a creation the server has
     * already carried out: what the server has is loaded, and the Stylist goes on from there.
     */
    val screenOutOfDate: Boolean
        get() = isClientNotAttached ||
            (code == "BAD_REQUEST" && (message in CLIENT_KEPT || message == SOURCE_HAS_NOTHING_TO_COPY)) ||
            isChangedElsewhere ||
            isMadeWithOtherValues

    /**
     * A save named the version of the record its form was loaded from, and the server has another
     * one: the record was saved elsewhere since, on another device. Nothing was written. The record
     * is loaded again, and the form keeps what the Stylist changed in it (see `ChangedElsewhere`).
     */
    val isChangedElsewhere: Boolean get() = code == "CONFLICT" && message == RECORD_CHANGED

    /**
     * A creating request named a key the server has already carried out, with other values: an
     * earlier try of the same form was saved although its reply never came, and the form was
     * changed since. Nothing was written, and the record of the earlier try is loaded. The form
     * must not be sent again under another key: that would create a second record.
     */
    val isMadeWithOtherValues: Boolean get() = code == "CONFLICT" && message == REQUEST_MADE_WITH_OTHER_VALUES

    companion object {
        const val NO_REPLY = "NO_REPLY"

        /** The server's refusal of a form that is older than its record ([isChangedElsewhere]), for a save the app can tell that of before it is sent. */
        fun changedElsewhere() = ApiException(RECORD_CHANGED, code = "CONFLICT")

        // The API's own words. They are the only way it tells these refusals apart.
        private val CLIENT_NOT_ATTACHED = setOf(
            "Client must be attached to the Appointment.",
            "Primary Client must be attached to the Appointment.",
            "Service Client must be attached to the Appointment.",
            "Photo Client must be attached to the Appointment.",
        )
        private val CLIENT_KEPT = setOf(
            "Appointment must keep at least one Client.",
            "Client has Services attached to this Appointment.",
            "Client has Appointment Photos attached to this Appointment.",
        )
        private val STORED_FILE_REFUSED = setOf(
            "Appointment Photo URLs must point at UploadThing storage.",
            "This file already belongs to another Appointment Photo.",
        )
        const val SOURCE_HAS_NOTHING_TO_COPY = "Source Appointment has no Services to copy."
        private const val RECORD_CHANGED = "This record was changed since it was loaded."
        private const val REQUEST_MADE_WITH_OTHER_VALUES = "This request was already carried out with other values."
    }
}

/** Anything that went wrong, as a failure the UI can show. Callers rethrow cancellation before using this. */
internal fun Exception.toApiException(): ApiException =
    this as? ApiException ?: ApiException("Unexpected failure.", code = "UNEXPECTED", cause = this)

/** True when this failure, or what caused it, is the connection: no network, an unknown host, a timeout. */
internal fun Throwable.isConnectionFailure(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is IOException }

/** The content of a JSON string or other primitive, null for anything else. Replies are not trusted to have the expected shape. */
internal fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            },
        )
    }
