package com.northphoenix.hairdresserclientmanager.i18n

import androidx.annotation.StringRes
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.auth.CodePurpose
import com.northphoenix.hairdresserclientmanager.auth.SessionCheck
import com.northphoenix.hairdresserclientmanager.auth.SignInError
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.PhotoPreparer
import com.northphoenix.hairdresserclientmanager.domain.Amount

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
        message == "Appointment total is too large." -> R.string.error_appointment_total_too_large
        // The Client Profile form refuses such an email itself, under the field, with these words.
        message == "Email is not valid." -> R.string.client_email_invalid
        code == PhotoPreparer.UNREADABLE -> R.string.error_image_unreadable
        code == PhotoPreparer.TOO_LARGE -> R.string.appointment_photo_too_large
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

/**
 * Why a step of signing in was refused, in the app language. The sign-in provider's own text is
 * English only, so none of it is shown: what it reports is a [SignInError] first.
 */
@StringRes
fun SignInError.messageRes(): Int =
    when (this) {
        SignInError.EMAIL_INVALID -> R.string.sign_in_error_email_invalid
        SignInError.EMAIL_TAKEN -> R.string.sign_in_error_email_taken
        SignInError.NO_ACCOUNT -> R.string.sign_in_error_no_account
        SignInError.WRONG_PASSWORD -> R.string.sign_in_error_wrong_password
        SignInError.PASSWORD_TOO_SHORT -> R.string.sign_in_error_password_too_short
        SignInError.PASSWORD_COMPROMISED -> R.string.sign_in_error_password_compromised
        SignInError.PASSWORD_REFUSED -> R.string.sign_in_error_password_refused
        SignInError.PASSWORDS_DIFFER -> R.string.sign_in_error_passwords_differ
        SignInError.WRONG_CODE -> R.string.sign_in_error_wrong_code
        SignInError.CODE_EXPIRED -> R.string.sign_in_error_code_expired
        SignInError.CODE_ATTEMPTS_USED_UP -> R.string.sign_in_error_code_attempts_used_up
        SignInError.TOO_MANY_REQUESTS -> R.string.sign_in_error_too_many_requests
        SignInError.ACCOUNT_LOCKED -> R.string.sign_in_error_account_locked
        SignInError.UNSUPPORTED -> R.string.sign_in_error_unsupported
        SignInError.INTERRUPTED -> R.string.sign_in_error_interrupted
        SignInError.NETWORK -> R.string.error_sign_in_unreachable
        SignInError.GENERIC -> R.string.error_generic
    }

/** What the code step says the emailed code is for. The text takes the email address as its argument. */
@StringRes
fun CodePurpose.bodyRes(): Int =
    when (this) {
        CodePurpose.SIGN_UP -> R.string.sign_in_code_body_sign_up
        CodePurpose.SIGN_IN -> R.string.sign_in_code_body_sign_in
        CodePurpose.NEW_DEVICE -> R.string.sign_in_code_body_new_device
        CodePurpose.PASSWORD_RESET -> R.string.sign_in_code_body_password_reset
    }

/**
 * Why an amount field refuses its text, or null for an amount that can be saved. The text of
 * `amount_too_large` takes the largest amount as its argument.
 */
@StringRes
fun Amount.refusalRes(): Int? =
    when (this) {
        is Amount.Valid -> null
        Amount.Empty -> R.string.amount_required
        Amount.Unreadable -> R.string.amount_unreadable
        Amount.Negative -> R.string.amount_negative
        Amount.TooLarge -> R.string.amount_too_large
    }
