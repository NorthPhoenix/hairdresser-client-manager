package com.northphoenix.hairdresserclientmanager.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.northphoenix.hairdresserclientmanager.data.GoogleCalendarMaker

/** How asking Google for access to make the Stylist's calendar ended. */
sealed interface GoogleCalendarAccess {
    data class Granted(val accessToken: String) : GoogleCalendarAccess

    /** The Stylist closed Google's screen. Nothing to say about it. */
    data object Canceled : GoogleCalendarAccess

    /**
     * Google did not give it. [network]: no connection to Google. Otherwise the app is not set up
     * for it (no Android client for this app's signing key, see docs/setup.md) or Google refused:
     * the calendar can be made on calendar.google.com instead.
     */
    data class Failed(val network: Boolean) : GoogleCalendarAccess
}

/**
 * Returns what asks Google, on this phone, for access to make calendars in the Stylist's account
 * ([GoogleCalendarMaker.SCOPE]). Google shows its own screens for it: which account, and whether
 * to allow it. [onResult] is called once per ask. No server takes part, and the access is used
 * once and not kept (ADR 0003).
 */
@Composable
fun rememberGoogleCalendarAccess(onResult: (GoogleCalendarAccess) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val answer = try {
            granted(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data))
        } catch (error: ApiException) {
            if (result.resultCode != Activity.RESULT_OK || error.statusCode == CommonStatusCodes.CANCELED) GoogleCalendarAccess.Canceled else failed(error)
        } catch (_: Exception) {
            if (result.resultCode != Activity.RESULT_OK) GoogleCalendarAccess.Canceled else GoogleCalendarAccess.Failed(network = false)
        }

        latest(answer)
    }

    return remember(context, launcher) {
        {
            ask(context) { result ->
                val pending = result.pendingIntent

                if (result.hasResolution() && pending != null) {
                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    latest(granted(result))
                }
            }.addOnFailureListener { latest(failed(it)) }
        }
    }
}

private fun ask(context: Context, onAnswer: (AuthorizationResult) -> Unit) =
    Identity.getAuthorizationClient(context)
        .authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GoogleCalendarMaker.SCOPE))).build())
        .addOnSuccessListener(onAnswer)

private fun granted(result: AuthorizationResult): GoogleCalendarAccess =
    result.accessToken?.takeIf { it.isNotBlank() }?.let(GoogleCalendarAccess::Granted) ?: GoogleCalendarAccess.Failed(network = false)

private fun failed(error: Exception): GoogleCalendarAccess =
    when ((error as? ApiException)?.statusCode) {
        CommonStatusCodes.CANCELED -> GoogleCalendarAccess.Canceled
        CommonStatusCodes.NETWORK_ERROR -> GoogleCalendarAccess.Failed(network = true)
        else -> GoogleCalendarAccess.Failed(network = false)
    }
