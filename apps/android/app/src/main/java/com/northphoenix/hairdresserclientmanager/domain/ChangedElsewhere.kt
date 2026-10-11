package com.northphoenix.hairdresserclientmanager.domain

/**
 * A form that is open while its record is saved somewhere else, on another device. When the
 * record is loaded again the form takes it in field by field, so that it neither shows nor sends
 * back what is no longer saved, and nothing the Stylist changed in the form is lost.
 *
 * The Client Profile form and the settings use it for every field they send.
 */
object ChangedElsewhere {
    /**
     * What a field shows once its record has been loaded again: [now], the record's value, when
     * the Stylist has not changed the field since it was filled with [loaded], and [typed], what
     * is in it, when they have. Compared as a save sends them, without the spaces around.
     */
    fun field(loaded: String, typed: String, now: String): String = if (typed.trim() == loaded.trim()) now else typed

    /**
     * True when [field] keeps [typed] in place of a value that was saved somewhere else since the
     * field was filled: the Stylist and the other device both changed it, and not to the same.
     * The next save puts [typed] over a value the Stylist has never seen, so a form that takes
     * such a record in tells them (`error_changed_elsewhere_too`).
     *
     * [sent] is what the form's last save sent for the field, while the form has not taken in
     * what came of it. A record that comes back with that was saved from this form, also when
     * the reply to the save never came: it collides with nothing typed since.
     */
    fun collides(loaded: String, typed: String, now: String, sent: String? = null): Boolean {
        val saved = now.trim()

        return typed.trim() != loaded.trim() && saved != loaded.trim() && saved != typed.trim() && saved != sent?.trim()
    }
}
