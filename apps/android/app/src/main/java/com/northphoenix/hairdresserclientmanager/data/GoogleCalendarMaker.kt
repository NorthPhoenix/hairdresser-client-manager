package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Why Google did not make the calendar. */
class GoogleCalendarException(val status: Int, cause: Throwable? = null) : Exception("Google Calendar answered $status", cause) {
    /** No usable connection to Google. */
    val isNetwork: Boolean get() = status == 0
}

/**
 * Makes the Stylist's calendar of its own in her Google account (ADR 0003), with the access she
 * gave on this phone for [SCOPE]. That is all the app does through the Google Calendar API: the
 * events go through the phone's calendar.
 */
class GoogleCalendarMaker(private val http: OkHttpClient, private val calendarsUrl: HttpUrl = CALENDARS_URL.toHttpUrl()) {
    /** Makes a calendar named [name] in [timezone] and returns its address in Google ("…@group.calendar.google.com"). */
    suspend fun create(accessToken: String, name: String, timezone: String, description: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("summary", name)
            put("timeZone", timezone)
            put("description", description)
        }
        val request = Request.Builder()
            .url(calendarsUrl)
            .header("Authorization", "Bearer $accessToken")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (error: Exception) {
            if (error.isConnectionFailure()) throw GoogleCalendarException(0, error) else throw error
        }

        response.use {
            if (!it.isSuccessful) {
                throw GoogleCalendarException(it.code)
            }
            val id = runCatching { hcmJson.parseToJsonElement(it.body.string()).jsonObject["id"].textOrNull() }.getOrNull()

            id?.takeIf(String::isNotBlank) ?: throw GoogleCalendarException(it.code)
        }
    }

    companion object {
        /** "Make secondary Google calendars, and see, create, change, and delete events on them": only calendars the app makes. */
        const val SCOPE = "https://www.googleapis.com/auth/calendar.app.created"

        const val CALENDARS_URL = "https://www.googleapis.com/calendar/v3/calendars"
    }
}
