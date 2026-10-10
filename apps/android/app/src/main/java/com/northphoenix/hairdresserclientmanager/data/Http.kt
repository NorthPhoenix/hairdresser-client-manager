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

    /** A change was sent but no reply came back, so it may or may not have been saved. */
    val outcomeUnknown: Boolean get() = code == NO_REPLY

    companion object {
        const val NO_REPLY = "NO_REPLY"
    }
}

/** Anything that went wrong, as a failure the UI can show. Callers rethrow cancellation before using this. */
internal fun Exception.toApiException(): ApiException =
    this as? ApiException ?: ApiException("Unexpected failure.", code = "UNEXPECTED", cause = this)

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
