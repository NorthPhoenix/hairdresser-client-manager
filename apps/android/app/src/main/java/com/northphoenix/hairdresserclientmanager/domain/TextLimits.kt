package com.northphoenix.hairdresserclientmanager.domain

/**
 * The longest text the server takes for each kind of field (`textLimits` in
 * packages/api/src/input.ts), counted as the server counts it: in UTF-16 code units, which is a
 * String's length. The server refuses a longer text and never cuts it, so a field with a limit
 * does not take more than the limit holds.
 */
object TextLimits {
    /** The name of a Client, of a Client created with an Appointment, of a Service and of a Service Menu Item. */
    const val NAME = 100
    const val PHONE = 40
    const val EMAIL = 254

    /** A Client's address, the salon's, and the address typed for one at-home Appointment. */
    const val ADDRESS = 300

    /** The Client Note, the note of an Appointment and the note of a Service. */
    const val NOTE = 5000
    const val FORMULA = 2000
    const val PLACEMENT = 200

    /**
     * What a field of at most [limit] characters holds after its text was changed from [before]
     * to [text]. Of what was typed or pasted, only as much is taken as there is room for; the
     * text around it stays whole, wherever in the field it was put. A character of two code
     * units (an emoji) is taken whole or not at all.
     *
     * Text that is longer than the limit already (saved before there was one, or copied from a
     * contact) can be shortened at any pace. It never grows.
     */
    fun typed(text: String, limit: Int, before: String): String {
        if (text.length <= limit || text.length <= before.length) {
            return text
        }

        // What was put in lies between what the two texts start with and what they end with in common.
        val start = text.commonPrefixWith(before).length
        val end = text.substring(start).commonSuffixWith(before.substring(start)).length
        val put = text.substring(start, text.length - end)
        var room = (limit - start - end).coerceAtLeast(0)

        if (room > 0 && put[room - 1].isHighSurrogate()) {
            room--
        }

        return text.substring(0, start) + put.substring(0, room) + text.substring(text.length - end)
    }
}
