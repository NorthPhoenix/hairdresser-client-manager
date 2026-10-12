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

    /**
     * An empty field is not an override waiting to be saved: there is nothing in it to save.
     * Neither is the saved override, however it is written. Text that is not an amount is unsaved input all the same.
     */
    fun finalTotal(draft: String, savedOverrideCents: Long?): Boolean {
        val saved = savedOverrideCents ?: return draft.isNotBlank()

        // Compared as text too: an override from before amounts had a maximum is refused as an amount.
        return draft.isNotBlank() && draft.trim() != Money.centsToPrice(saved) && Money.parse(draft) != Amount.Valid(saved)
    }

    /**
     * What the note field of an Appointment holds once the server has another note than before:
     * the save of the field was answered, or a reload brought what was saved elsewhere.
     * [shownBefore] is the note the Appointment showed until then, which is the text that was
     * sent while a save was on its way, and [shown] the note it shows now.
     *
     * A field that still holds [shownBefore] was not touched since, and follows the Appointment.
     * Anything else was typed since: it stays in the field, and is unsaved input from then on.
     * So what the Stylist types on while a save is on its way is not taken away by its answer.
     */
    fun noteDraft(draft: String, shownBefore: String, shown: String): String = if (appointmentNote(draft, shownBefore)) draft else shown

    /**
     * As [noteDraft], for the field of the final total override, whose text is compared as an
     * amount. A field that was emptied counts as typed in too, unless there was no override:
     * the amount that was just saved does not come back into a field the Stylist has cleared to
     * type another.
     */
    fun finalTotalDraft(draft: String, shownBeforeCents: Long?, shownCents: Long?): String {
        val untouched = !finalTotal(draft, shownBeforeCents) && (shownBeforeCents == null || draft.isNotBlank())

        return if (untouched) shownCents?.let(Money::centsToPrice).orEmpty() else draft
    }
}
