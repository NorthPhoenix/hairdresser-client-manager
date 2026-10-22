package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal tRPC v11 HTTP client: unbatched calls, plain JSON (the router has no transformer).
 * Queries are `GET /path?input=<json>`, mutations are `POST /path` with the input as the body.
 */
class TrpcClient(
    private val baseUrl: String,
    http: OkHttpClient,
    /** Sent with every request: the app's version (`X-App-Version-Code`, `X-App-Version-Name`), which the server keeps per Stylist. */
    private val appHeaders: Map<String, String> = emptyMap(),
    private val tokenProvider: suspend () -> String?,
) {
    // A network interceptor runs once a connection exists, just before the request is written to it.
    // A failure after that point no longer proves that the server did not get the request.
    private val http = http.newBuilder()
        .addNetworkInterceptor { chain ->
            chain.request().tag(Sent::class.java)?.set(true)
            chain.proceed(chain.request())
        }
        .build()

    suspend inline fun <reified T> query(path: String, input: JsonElement? = null): T =
        call(path, input, isMutation = false, serializer<T>())

    suspend inline fun <reified T> mutation(path: String, input: JsonElement? = null): T =
        call(path, input, isMutation = true, serializer<T>())

    suspend fun <T> call(path: String, input: JsonElement?, isMutation: Boolean, output: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/$path".toHttpUrl().newBuilder().apply {
                if (!isMutation && input != null) {
                    addQueryParameter("input", input.toString())
                }
            }.build()
            val sent = Sent()
            val request = Request.Builder().url(url).tag(Sent::class.java, sent).apply {
                appHeaders.forEach { (name, value) -> header(name, value) }
                tokenProvider()?.let { header("Authorization", "Bearer $it") }
                if (isMutation) {
                    post((input?.toString() ?: "").toRequestBody(jsonMediaType))
                }
            }.build()

            try {
                http.newCall(request).await().use { decode(it.body.string(), it.code, isMutation, output) }
            } catch (error: IOException) {
                throw if (isMutation && sent.get()) {
                    // The change may have been saved: only the reply is missing.
                    ApiException("No reply from the server.", code = ApiException.NO_REPLY, cause = error)
                } else {
                    ApiException("Network request failed.", code = "NETWORK", isNetwork = true, cause = error)
                }
            }
        }

    /**
     * Other software can answer in the API's place (a gateway, a rate limiter, the dev backend's
     * catch-all), so every part of the reply's shape is checked instead of assumed.
     */
    private fun <T> decode(body: String, httpStatus: Int, isMutation: Boolean, output: KSerializer<T>): T {
        // A change answered with a server-side failure that is not the API's own: a gateway or the
        // hosting platform spoke in its place, perhaps after the API had saved the change (a
        // timeout, a crash after the commit). The outcome is as unknown as with no reply at all.
        val maybeSaved = isMutation && httpStatus in 500..599

        fun notFromApi(cause: Throwable? = null) =
            if (maybeSaved) {
                ApiException("No reply from the API ($httpStatus).", code = ApiException.NO_REPLY, cause = cause)
            } else {
                ApiException("Unexpected server response ($httpStatus).", code = "BAD_RESPONSE", cause = cause)
            }

        val envelope = try {
            hcmJson.parseToJsonElement(body)
        } catch (error: Exception) {
            throw notFromApi(error)
        } as? JsonObject ?: throw notFromApi()

        envelope["error"]?.takeUnless { it is JsonNull }?.let { error ->
            // The API always names its code, so an error without one is somebody else's.
            throw parseError(error)?.takeUnless { maybeSaved && it.code == UNCODED } ?: notFromApi()
        }

        val data = (envelope["result"] as? JsonObject)?.get("data") ?: throw notFromApi()

        return try {
            hcmJson.decodeFromJsonElement(output, data)
        } catch (error: Exception) {
            throw notFromApi(error)
        }
    }

    /** Set once the request has been handed to a live connection. */
    private class Sent : AtomicBoolean()

    companion object {
        private val jsonMediaType = "application/json".toMediaType()

        /** The headers that name the app's version, which the server records per Stylist. */
        fun versionHeaders(versionCode: Int, versionName: String): Map<String, String> =
            mapOf("X-App-Version-Code" to versionCode.toString(), "X-App-Version-Name" to versionName)

        /** The code of an error that came without tRPC's own. */
        private const val UNCODED = "UNKNOWN"

        /** The error as a failure to show, or null when it is not an object at all. */
        private fun parseError(error: JsonElement): ApiException? {
            if (error !is JsonObject) {
                return null
            }

            val code = (error["data"] as? JsonObject)?.get("code").textOrNull() ?: UNCODED
            val rawMessage = error["message"].textOrNull().orEmpty()

            return ApiException(message = readableMessage(rawMessage), code = code)
        }

        /** Zod validation failures arrive as a JSON array of issues; show the first issue's message. */
        private fun readableMessage(rawMessage: String): String {
            if (!rawMessage.trimStart().startsWith("[")) {
                return rawMessage.ifBlank { "Request failed." }
            }

            return try {
                val issues = hcmJson.parseToJsonElement(rawMessage) as JsonArray
                (issues.first() as JsonObject)["message"].textOrNull() ?: rawMessage
            } catch (_: Exception) {
                rawMessage
            }
        }
    }
}
