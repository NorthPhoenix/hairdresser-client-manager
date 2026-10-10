package com.northphoenix.hairdresserclientmanager.i18n

import androidx.annotation.StringRes
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.auth.SessionCheck
import com.northphoenix.hairdresserclientmanager.data.ApiException

/**
 * The API reports failures in English only. Rather than show that text in a Russian UI,
 * failures a Stylist can actually run into are mapped to localized messages, and anything
 * else falls back to a message for its error code.
 */
@StringRes
fun ApiException.messageRes(): Int =
    when {
        isNetwork -> R.string.network_error
        outcomeUnknown -> R.string.error_outcome_unknown
        message == "Appointment must keep at least one Client." -> R.string.error_appointment_needs_client
        message == "Client has Services attached to this Appointment." -> R.string.error_client_has_services
        message == "End time must be after start time." -> R.string.appointment_end_before_start
        message == "Completed source Appointment not found." -> R.string.copy_services_empty
        code == "BAD_IMAGE" -> R.string.error_image_unreadable
        code == "UPLOAD_FAILED" || code == "UPLOAD_REJECTED" -> R.string.appointment_photo_upload_failed
        // What a refused sign-in really means is for the sign-in provider to say: see refusedSignInMessage.
        isUnauthorized -> R.string.error_sign_in_rejected
        isNotFound -> R.string.error_not_found
        code == "BAD_REQUEST" -> R.string.error_invalid_input
        else -> R.string.error_generic
    }

/**
 * What to say after the API refused the sign-in ([ApiException.isUnauthorized]), once the sign-in
 * provider has been asked about the session. Null when the session has ended: the sign-in screen
 * appears and says so itself.
 */
@StringRes
fun SessionCheck.refusedSignInMessage(): Int? =
    when (this) {
        SessionCheck.ENDED -> null
        SessionCheck.ALIVE -> R.string.error_sign_in_rejected
        // Nothing has ended, so nothing here may tell the Stylist to sign in again.
        SessionCheck.UNREACHABLE -> R.string.error_sign_in_unreachable
    }

/** Why the sign-in provider did not start. Its own text is technical English, so only the kind of failure is shown. */
@StringRes
fun AuthState.Failed.messageRes(): Int = if (isNetwork) R.string.network_error else R.string.error_generic
