package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Minimal tRPC v11 HTTP client: unbatched calls, plain JSON (the router has no transformer).
 * Queries are `GET /path?input=<json>`, mutations are `POST /path` with the input as the body.
 */
class TrpcClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
) {
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
            val request = Request.Builder().url(url).apply {
                tokenProvider()?.let { header("Authorization", "Bearer $it") }
                if (isMutation) {
                    post((input?.toString() ?: "").toRequestBody(jsonMediaType))
                }
            }.build()

            val response = try {
                http.newCall(request).await()
            } catch (error: IOException) {
                throw ApiException("Network request failed.", code = "NETWORK", isNetwork = true, cause = error)
            }

            response.use {
                val body = try {
                    it.body.string()
                } catch (error: IOException) {
                    throw ApiException("Network request failed.", code = "NETWORK", isNetwork = true, cause = error)
                }
                decode(body, it.code, output)
            }
        }

    private fun <T> decode(body: String, httpStatus: Int, output: KSerializer<T>): T {
        val envelope = try {
            hcmJson.parseToJsonElement(body).jsonObject
        } catch (error: Exception) {
            throw ApiException("Unexpected server response ($httpStatus).", code = "BAD_RESPONSE", cause = error)
        }

        envelope["error"]?.let { throw parseError(it.jsonObject) }

        val data = envelope["result"]?.jsonObject?.get("data")
            ?: throw ApiException("Unexpected server response ($httpStatus).", code = "BAD_RESPONSE")

        return try {
            hcmJson.decodeFromJsonElement(output, data)
        } catch (error: SerializationException) {
            throw ApiException("Unexpected server response.", code = "BAD_RESPONSE", cause = error)
        }
    }

    companion object {
        private val jsonMediaType = "application/json".toMediaType()

        internal fun parseError(error: JsonObject): ApiException {
            val code = error["data"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            val rawMessage = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()

            return ApiException(message = readableMessage(rawMessage), code = code)
        }

        /** Zod validation failures arrive as a JSON array of issues; show the first issue's message. */
        private fun readableMessage(rawMessage: String): String {
            if (!rawMessage.trimStart().startsWith("[")) {
                return rawMessage.ifBlank { "Request failed." }
            }

            return try {
                val issues = hcmJson.parseToJsonElement(rawMessage) as JsonArray
                issues.first().jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: rawMessage
            } catch (_: Exception) {
                rawMessage
            }
        }
    }
}
