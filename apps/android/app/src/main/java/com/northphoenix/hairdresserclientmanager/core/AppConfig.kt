package com.northphoenix.hairdresserclientmanager.core

import com.northphoenix.hairdresserclientmanager.BuildConfig

/**
 * Build-time configuration, read from `apps/android/local.properties` or Gradle properties.
 * Nothing here is secret: the Clerk publishable key is safe to ship in the app.
 */
data class AppConfig(
    val clerkPublishableKey: String,
    val apiBaseUrl: String,
    val webBaseUrl: String,
    val devAuthUserId: String,
) {
    val trpcUrl: String get() = "$apiBaseUrl/api/trpc"
    val uploadThingUrl: String get() = "$apiBaseUrl/api/uploadthing"
    val hasClerk: Boolean get() = clerkPublishableKey.isNotBlank()
    val hasDevAuth: Boolean get() = BuildConfig.DEBUG && devAuthUserId.isNotBlank()

    fun profileShareUrl(token: String): String = "$webBaseUrl/profile-shares/$token"

    companion object {
        fun fromBuildConfig(): AppConfig {
            val apiBaseUrl = normalizeBaseUrl(BuildConfig.API_BASE_URL)

            return AppConfig(
                clerkPublishableKey = BuildConfig.CLERK_PUBLISHABLE_KEY.trim(),
                apiBaseUrl = apiBaseUrl,
                webBaseUrl = normalizeBaseUrl(BuildConfig.WEB_BASE_URL).ifBlank { apiBaseUrl },
                devAuthUserId = BuildConfig.DEV_AUTH_USER_ID.trim(),
            )
        }

        /** Accepts either the web origin or the full tRPC URL, like the previous mobile app did. */
        fun normalizeBaseUrl(url: String): String =
            url.trim().replace(Regex("/api/trpc/?$"), "").trimEnd('/')
    }
}
