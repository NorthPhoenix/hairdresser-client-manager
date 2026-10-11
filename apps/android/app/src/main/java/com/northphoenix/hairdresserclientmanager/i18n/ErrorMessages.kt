package com.northphoenix.hairdresserclientmanager.i18n

import androidx.annotation.StringRes
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.ACCOUNT_NAME_NOT_SAVED
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.auth.CodePurpose
import com.northphoenix.hairdresserclientmanager.auth.SessionCheck
import com.northphoenix.hairdresserclientmanager.auth.SignInError
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.PhotoPreparer
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients

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
        message == "Client has Appointment Photos attached to this Appointment." -> R.string.error_client_has_photos
        // Removed from the Appointment on another device, and still on this screen until it is loaded again.
        isClientNotAttached -> R.string.error_client_not_in_appointment
        message == "End time must be after start time." -> R.string.appointment_end_before_start
        // Appointment Copying. Without a named source: no Completed Appointment has anything to copy, which is what the empty sheet says.
        message == "Completed source Appointment not found." -> R.string.copy_services_empty
        // The source the Stylist tapped is not what its card showed any more. Each says that nothing was copied, and why.
        message == ApiException.SOURCE_HAS_NOTHING_TO_COPY -> R.string.copy_services_source_empty
        message == "Source Appointment is no longer Completed." -> R.string.copy_services_source_not_completed
        message == "Source Appointment not found." -> R.string.copy_services_source_gone
        message == "Appointment total is too large." -> R.string.error_appointment_total_too_large
        // The limits of the server's input. The fields keep to them, so these are for what a field could not: text
        // and amounts saved before there were limits, a copied contact, a timezone only this device or only the server knows.
        message == "Amount is too large." -> R.string.error_amount_too_large
        message == "Text is too long." -> R.string.error_text_too_long
        message == "Timezone is not valid." -> R.string.error_timezone_invalid
        // The picture is on the device still: 'Upload again' sends it anew.
        isStoredFileRefused -> R.string.appointment_photo_upload_failed
        // Saved on another device since the form was filled. Nothing was saved; the form takes in what is saved now and keeps what was typed.
        isChangedElsewhere -> R.string.error_changed_elsewhere
        // An earlier try of this form was saved although its reply never came, and the form was changed since.
        isMadeWithOtherValues -> R.string.error_already_saved_as_before
        // Revoked on another device, and still on this screen until the Client is loaded again.
        message == "Profile Share not found." -> R.string.error_profile_share_gone
        // The sign-in provider's own reason is technical English: only that the name was not saved is said.
        code == ACCOUNT_NAME_NOT_SAVED -> R.string.account_name_not_saved
        // The Client Profile form refuses such an email itself, under the field, with these words.
        message == "Email is not valid." -> R.string.client_email_invalid
        code == PhotoPreparer.UNREADABLE -> R.string.error_image_unreadable
        code == PhotoPreparer.TOO_LARGE -> R.string.appointment_photo_too_large
        // Trying again cannot work until the server is set up for photos.
        code == UploadThingClient.NO_PHOTO_STORAGE -> R.string.appointment_photos_unavailable
        code == "UPLOAD_FAILED" || code == "UPLOAD_REJECTED" -> R.string.appointment_photo_upload_failed
        // What a refused sign-in really means is for the sign-in provider to say: see refusedSignInMessage.
        isUnauthorized -> R.string.error_sign_in_rejected
        isNotFound -> R.string.error_not_found
        code == "BAD_REQUEST" -> R.string.error_invalid_input
        else -> R.string.error_generic
    }

/** Why a Client cannot be removed from an Appointment yet. For Services and photos these are the words of the server's refusal. */
@StringRes
fun AppointmentClients.Kept.messageRes(): Int =
    when (this) {
        AppointmentClients.Kept.SERVICES -> R.string.error_client_has_services
        AppointmentClients.Kept.PHOTOS -> R.string.error_client_has_photos
        AppointmentClients.Kept.UNSAVED_PHOTOS -> R.string.error_client_has_photo_uploads
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
