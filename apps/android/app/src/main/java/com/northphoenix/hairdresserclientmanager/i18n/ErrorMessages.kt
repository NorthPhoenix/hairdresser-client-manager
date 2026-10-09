package com.northphoenix.hairdresserclientmanager.i18n

import androidx.annotation.StringRes
import com.northphoenix.hairdresserclientmanager.R
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
        message == "Appointment must keep at least one Client." -> R.string.error_appointment_needs_client
        message == "Client has Services attached to this Appointment." -> R.string.error_client_has_services
        message == "End time must be after start time." -> R.string.appointment_end_before_start
        message == "Completed source Appointment not found." -> R.string.copy_services_empty
        code == "BAD_IMAGE" -> R.string.error_image_unreadable
        code == "UPLOAD_FAILED" || code == "UPLOAD_REJECTED" -> R.string.appointment_photo_upload_failed
        code == "UNAUTHORIZED" -> R.string.error_session_ended
        code == "NOT_FOUND" -> R.string.error_not_found
        code == "BAD_REQUEST" -> R.string.error_invalid_input
        else -> R.string.error_generic
    }
