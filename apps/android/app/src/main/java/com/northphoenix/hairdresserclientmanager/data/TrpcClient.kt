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
                tokenProvider()?.let { header("Authorization", "Bearer $it") }
                if (isMutation) {
                    post((input?.toString() ?: "").toRequestBody(jsonMediaType))
                }
            }.build()

            try {
                http.newCall(request).await().use { decode(it.body.string(), it.code, output) }
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
    private fun <T> decode(body: String, httpStatus: Int, output: KSerializer<T>): T {
        val envelope = try {
            hcmJson.parseToJsonElement(body)
        } catch (error: Exception) {
            throw badResponse(httpStatus, error)
        } as? JsonObject ?: throw badResponse(httpStatus)

        envelope["error"]?.takeUnless { it is JsonNull }?.let { throw parseError(it, httpStatus) }

        val data = (envelope["result"] as? JsonObject)?.get("data") ?: throw badResponse(httpStatus)

        return try {
            hcmJson.decodeFromJsonElement(output, data)
        } catch (error: Exception) {
            throw badResponse(httpStatus, error)
        }
    }

    /** Set once the request has been handed to a live connection. */
    private class Sent : AtomicBoolean()

    companion object {
        private val jsonMediaType = "application/json".toMediaType()

        private fun badResponse(httpStatus: Int, cause: Throwable? = null) =
            ApiException("Unexpected server response ($httpStatus).", code = "BAD_RESPONSE", cause = cause)

        private fun parseError(error: JsonElement, httpStatus: Int): ApiException {
            if (error !is JsonObject) {
                return badResponse(httpStatus)
            }

            val code = (error["data"] as? JsonObject)?.get("code").textOrNull() ?: "UNKNOWN"
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
