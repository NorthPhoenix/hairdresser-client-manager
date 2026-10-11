package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** The change names a Client who is no longer one of the Appointment's Clients. */
    val isClientNotAttached: Boolean get() = code == "BAD_REQUEST" && message in CLIENT_NOT_ATTACHED

    /**
     * The API refused a change by who the Clients of an Appointment are now, or by what they have
     * in it. The app offers such a change only where its own copy of the Appointment allows it, so
     * that copy is out of date and the Appointment is loaded again.
     * A copy from an Appointment that has nothing to copy is such a change as well: the copy sheet
     * lists only Appointments that have, so its list is out of date.
     */
    val screenOutOfDate: Boolean
        get() = isClientNotAttached || (code == "BAD_REQUEST" && (message in CLIENT_KEPT || message == SOURCE_HAS_NOTHING_TO_COPY))

    companion object {
        const val NO_REPLY = "NO_REPLY"

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
        const val SOURCE_HAS_NOTHING_TO_COPY = "Source Appointment has no Services to copy."
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
