package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import java.time.LocalDate
import java.time.LocalTime

/**
 * Tells whether a screen holds input that leaving it would lose, so that Back asks first, and
 * only then. Nothing here counts what a save would not keep, such as spaces around a text.
 *
 * A form is compared through its content: what a save would send, as strings, which can be kept
 * in saved state. The form has unsaved input when its content differs from the content it had
 * when it was opened, filled or last saved.
 */
object UnsavedInput {
    fun clientForm(name: String, language: Language, phone: String, email: String, address: String, note: String): List<String> =
        listOf(name, language.name, phone, email, address, note).map(String::trim)

    /** [address] counts only at home, the one place where the form shows and sends it. */
    fun newAppointment(
        primaryClientId: String?,
        inlineClientName: String,
        additionalClientIds: List<String>,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime?,
        locationType: LocationType,
        address: String,
    ): List<String> = listOf(
        primaryClientId.orEmpty(),
        inlineClientName.trim(),
        additionalClientIds.joinToString(","),
        date.toString(),
        startTime.toString(),
        endTime?.toString().orEmpty(),
        locationType.name,
        if (locationType == LocationType.AT_HOME) address.trim() else "",
    )

    fun appointmentNote(draft: String, saved: String): Boolean = draft.trim() != saved.trim()

    /** An empty field is not an override waiting to be saved: there is nothing in it to save. */
    fun finalTotal(draft: String, savedOverrideCents: Int?): Boolean =
        draft.isNotBlank() && Money.priceToCents(draft) != savedOverrideCents
}
