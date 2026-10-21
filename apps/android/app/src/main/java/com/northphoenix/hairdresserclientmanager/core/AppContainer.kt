package com.northphoenix.hairdresserclientmanager.core

import android.content.Context
import com.northphoenix.hairdresserclientmanager.auth.AuthGateway
import com.northphoenix.hairdresserclientmanager.auth.ClerkAuthGateway
import com.northphoenix.hairdresserclientmanager.auth.createDevAuthGateway
import com.northphoenix.hairdresserclientmanager.data.AndroidSmsGateway
import com.northphoenix.hairdresserclientmanager.data.HcmApi
import com.northphoenix.hairdresserclientmanager.data.SharedPreferencesReminderStore
import com.northphoenix.hairdresserclientmanager.data.TrpcClient
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
import com.northphoenix.hairdresserclientmanager.i18n.AndroidAppLocales
import com.northphoenix.hairdresserclientmanager.i18n.AppLanguageSetting
import com.northphoenix.hairdresserclientmanager.state.ReminderOutbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies. The app is small enough that a DI framework would only add ceremony. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val config: AppConfig = AppConfig.fromBuildConfig()
    val appLanguage = AppLanguageSetting(AndroidAppLocales(appContext))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Null when neither Clerk nor the debug-only dev sign-in is configured. */
    val auth: AuthGateway? = when {
        config.hasDevAuth -> createDevAuthGateway(appContext, config)
        config.hasClerk -> ClerkAuthGateway(appContext, config.clerkPublishableKey, scope)
        else -> null
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val tokenProvider: suspend () -> String? = { auth?.token() }

    val api = HcmApi(TrpcClient(config.trpcUrl, http, tokenProvider))
    val uploads = UploadThingClient(config.uploadThingUrl, http, tokenProvider)

    /** Client Reminders sent from this phone, and the device-local log of which Appointments were reminded. */
    val reminders by lazy { ReminderOutbox(AndroidSmsGateway(appContext), SharedPreferencesReminderStore(appContext), scope) }
}
