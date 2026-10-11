package com.northphoenix.hairdresserclientmanager.state

/**
 * Orders the changes of one value the Stylist can tap through quickly, such as an Appointment's status.
 * Requests go out one at a time, and a value offered while one is in flight replaces any value still
 * waiting, so what ends up saved is always the last one offered.
 *
 * Not thread-safe: use it from one thread.
 */
internal class LatestWins<K, V> {
    private val wanted = mutableMapOf<K, V>()

    /** The value [key] is being changed to. Null when no change is in flight, or when that is the value. */
    fun pending(key: K): V? = wanted[key]

    /**
     * Makes [value] the one to end on. Returns true when the caller has to start [send],
     * false when a [send] already running for [key] will pick the value up.
     */
    fun offer(key: K, value: V): Boolean {
        val idle = key !in wanted
        wanted[key] = value
        return idle
    }

    /**
     * [offer] for a value whose saved state is known. Offering [saved] while nothing is in flight
     * starts nothing. While a change is in flight [saved] is only what the screen still shows:
     * going back to it is a change again, and the last one offered.
     */
    fun offer(key: K, value: V, saved: V?): Boolean = (key in wanted || value != saved) && offer(key, value)

    /** Sends the wanted value, and again for as long as a newer one was offered in the meantime. */
    suspend fun send(key: K, request: suspend (V) -> Unit) {
        try {
            do {
                val value = wanted.getValue(key)
                request(value)
            } while (wanted[key] != value)
        } finally {
            wanted -= key
        }
    }
}
