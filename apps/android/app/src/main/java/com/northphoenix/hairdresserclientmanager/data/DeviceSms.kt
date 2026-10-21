package com.northphoenix.hairdresserclientmanager.data

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.northphoenix.hairdresserclientmanager.domain.Reminded
import com.northphoenix.hairdresserclientmanager.domain.ReminderLog
import com.northphoenix.hairdresserclientmanager.state.ReminderStore
import com.northphoenix.hairdresserclientmanager.state.SmsFailure
import com.northphoenix.hairdresserclientmanager.state.SmsGateway
import com.northphoenix.hairdresserclientmanager.state.SmsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** What this phone can do about sending an SMS itself. */
object DeviceSms {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Whether the device can send an SMS at all (a tablet without a mobile radio cannot). */
    fun isAvailable(context: Context): Boolean {
        val feature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY

        return context.packageManager.hasSystemFeature(feature)
    }
}

/**
 * Sends with Android's [SmsManager] on the default SMS subscription: a text that does not fit one
 * SMS goes in several parts, and it counts as sent when the system says every part was.
 */
class AndroidSmsGateway(context: Context) : SmsGateway {
    private val context = context.applicationContext
    private val counter = AtomicInteger()

    @Suppress("DEPRECATION")
    private fun manager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    override suspend fun send(number: String, text: String): SmsResult {
        if (!DeviceSms.hasPermission(context)) return SmsResult.Failed(SmsFailure.NO_PERMISSION)

        val action = "${context.packageName}.SMS_SENT.${counter.incrementAndGet()}"
        val parts = try {
            manager().divideMessage(text)
        } catch (_: RuntimeException) {
            return SmsResult.Failed(SmsFailure.OTHER)
        }

        val result = CompletableDeferred<SmsResult>()
        var pending = parts.size
        var failure: SmsFailure? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK && failure == null) failure = failureOf(resultCode)
                if (--pending == 0) result.complete(failure?.let(SmsResult::Failed) ?: SmsResult.Sent)
            }
        }

        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

        try {
            val sent = parts.indices.map { index ->
                PendingIntent.getBroadcast(context, index, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

            try {
                if (parts.size == 1) {
                    manager().sendTextMessage(number, null, text, sent.first(), null)
                } else {
                    manager().sendMultipartTextMessage(number, null, parts, ArrayList(sent), null)
                }
            } catch (_: SecurityException) {
                result.complete(SmsResult.Failed(SmsFailure.NO_PERMISSION))
            } catch (_: RuntimeException) {
                result.complete(SmsResult.Failed(SmsFailure.OTHER))
            }

            return withTimeoutOrNull(SEND_TIMEOUT_MS) { result.await() } ?: SmsResult.Failed(SmsFailure.TIMEOUT)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun failureOf(code: Int): SmsFailure = when (code) {
        SmsManager.RESULT_ERROR_RADIO_OFF -> SmsFailure.AIRPLANE_MODE
        SmsManager.RESULT_ERROR_NO_SERVICE -> SmsFailure.NO_SERVICE
        else -> SmsFailure.OTHER
    }

    private companion object {
        /** A send that has not been answered by now has not worked. */
        const val SEND_TIMEOUT_MS = 60_000L
    }
}

/** The log of reminders in this app's own preferences. */
class SharedPreferencesReminderStore(context: Context) : ReminderStore {
    private val preferences = context.applicationContext.getSharedPreferences("client_reminders_sent", Context.MODE_PRIVATE)

    override fun load(): Map<String, Reminded> =
        preferences.getStringSet(KEY, emptySet()).orEmpty().mapNotNull(ReminderLog::decode).associateBy { it.appointmentId }

    override fun save(entries: Map<String, Reminded>) {
        preferences.edit().putStringSet(KEY, entries.values.map(ReminderLog::encode).toSet()).apply()
    }

    private companion object {
        const val KEY = "reminded"
    }
}
