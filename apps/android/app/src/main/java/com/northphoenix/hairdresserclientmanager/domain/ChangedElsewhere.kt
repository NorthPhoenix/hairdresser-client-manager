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
}
