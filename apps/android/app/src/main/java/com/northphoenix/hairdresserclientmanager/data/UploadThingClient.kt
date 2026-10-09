package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.random.Random

/** A file that finished uploading, with whatever the route's `onUploadComplete` returned. */
data class UploadedFile(val key: String, val ufsUrl: String, val serverData: JsonElement?)

/**
 * Speaks the UploadThing v7 client protocol against the web app's `/api/uploadthing` route:
 * request a presigned URL from our server, then PUT the file to UploadThing's ingest URL.
 * There is no official Android SDK; this mirrors `uploadthing/client` 7.7.4.
 */
class UploadThingClient(
    private val routeUrl: String,
    private val http: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
) {
    suspend fun upload(slug: String, input: JsonObject, fileName: String, mimeType: String, bytes: ByteArray): UploadedFile =
        withContext(Dispatchers.IO) {
            val trace = TraceHeaders.generate()
            val presigned = requestPresignedUrl(slug, input, fileName, mimeType, bytes.size, trace)
            val rangeStart = resumeOffset(presigned.url, trace).coerceIn(0, bytes.size)
            val uploadBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    bytes.toRequestBody(mimeType.toMediaType(), rangeStart, bytes.size - rangeStart),
                )
                .build()
            val request = Request.Builder()
                .url(presigned.url)
                .put(uploadBody)
                .header("Range", "bytes=$rangeStart-")
                .header("x-uploadthing-version", CLIENT_VERSION)
                .header("b3", trace.b3)
                .header("traceparent", trace.traceparent)
                .build()
            val (status, body) = execute(request)
            val result = parseObject(body)
            val error = result?.get("error")?.jsonPrimitive?.contentOrNull

            if (status !in 200..299 || result == null || error != null) {
                throw ApiException(error ?: "Upload failed ($status).", code = "UPLOAD_FAILED")
            }

            UploadedFile(
                key = presigned.key,
                ufsUrl = result["ufsUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                serverData = result["serverData"],
            )
        }

    private suspend fun requestPresignedUrl(
        slug: String,
        input: JsonObject,
        fileName: String,
        mimeType: String,
        size: Int,
        trace: TraceHeaders,
    ): PresignedUrl {
        val url = routeUrl.toHttpUrl().newBuilder()
            .setQueryParameter("actionType", "upload")
            .setQueryParameter("slug", slug)
            .build()
        val payload = buildJsonObject {
            put("input", input)
            put(
                "files",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", fileName)
                            put("size", size)
                            put("type", mimeType)
                            put("lastModified", System.currentTimeMillis())
                        },
                    )
                },
            )
        }
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("x-uploadthing-package", CLIENT_PACKAGE)
            .header("x-uploadthing-version", CLIENT_VERSION)
            .header("b3", trace.b3)
            .header("traceparent", trace.traceparent)
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        val (status, body) = execute(request)

        if (status !in 200..299) {
            val message = parseObject(body)?.get("message")?.jsonPrimitive?.contentOrNull
            throw ApiException(message ?: "Upload could not start ($status).", code = "UPLOAD_REJECTED")
        }

        return try {
            hcmJson.decodeFromString(ListSerializer(PresignedUrl.serializer()), body).first()
        } catch (error: Exception) {
            throw ApiException("Unexpected upload response.", code = "BAD_RESPONSE", cause = error)
        }
    }

    /** UploadThing reports how many bytes it already holds so an interrupted upload can resume. */
    private suspend fun resumeOffset(presignedUrl: String, trace: TraceHeaders): Int {
        val request = Request.Builder()
            .url(presignedUrl)
            .head()
            .header("b3", trace.b3)
            .header("traceparent", trace.traceparent)
            .build()

        return try {
            http.newCall(request).await().use { it.header("x-ut-range-start")?.toIntOrNull() ?: 0 }
        } catch (error: IOException) {
            throw ApiException("Network request failed.", code = "NETWORK", isNetwork = true, cause = error)
        }
    }

    private suspend fun execute(request: Request): Pair<Int, String> =
        try {
            http.newCall(request).await().use { it.code to it.body.string() }
        } catch (error: IOException) {
            throw ApiException("Network request failed.", code = "NETWORK", isNetwork = true, cause = error)
        }

    private fun parseObject(body: String): JsonObject? =
        try {
            hcmJson.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class PresignedUrl(val url: String, val key: String)

    private data class TraceHeaders(val b3: String, val traceparent: String) {
        companion object {
            fun generate(): TraceHeaders {
                val traceId = randomHex(32)
                val spanId = randomHex(16)
                return TraceHeaders(b3 = "$traceId-$spanId-01", traceparent = "00-$traceId-$spanId-01")
            }

            private fun randomHex(length: Int): String =
                buildString(length) { repeat(length) { append("0123456789abcdef"[Random.nextInt(16)]) } }
        }
    }

    companion object {
        /** Keep in step with the `uploadthing` version in apps/web/package.json. */
        const val CLIENT_VERSION = "7.7.4"
        const val CLIENT_PACKAGE = "hcm-android"
        const val APPOINTMENT_PHOTO_SLUG = "appointmentPhoto"
        const val MAX_PHOTO_BYTES = 8 * 1024 * 1024

        fun decodeUploadedPhoto(serverData: JsonElement?): UploadedPhoto? =
            try {
                serverData?.let { hcmJson.decodeFromJsonElement<UploadedPhoto>(it) }
            } catch (_: Exception) {
                null
            }
    }
}
