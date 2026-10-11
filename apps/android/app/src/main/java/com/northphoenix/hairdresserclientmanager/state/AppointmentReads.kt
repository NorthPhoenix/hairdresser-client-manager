package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** A day of the Stylist: [day] in [zone], the timezone its start and its end are worked out in. Another timezone is another key. */
internal data class DayKey(val day: LocalDate, val zone: ZoneId)

/** One Appointment, by its id. */
internal data class AppointmentKey(val id: String)

/**
 * The Appointment that Services are copied into. What can be copied depends on who its Clients
 * are, because a copy takes only the Services of Clients attached to both: with other Clients it
 * is another key, so a list is never shown for Clients the Appointment no longer has.
 */
internal data class CopyTargetKey(val appointmentId: String, val clientIds: Set<String>)

/**
 * Every read of Appointments, each through a [ReadCache]: [today] (`appointment.home`), a
 * Calendar day ([days], `appointment.list`), one Appointment by its id ([one], `appointment.get`)
 * and what Appointment Copying can copy from ([sources], `appointment.completedSources`).
 *
 * A change is told here, and reaches the reads in two ways. What is on screen gets what the
 * server has confirmed at once ([update], [clientSaved], [forget]), so it is right also when the
 * reload after the change fails. And the change is marked in [changes], so that no cached answer
 * that holds the changed Appointment or Client is served again: a day that is not on screen is
 * loaded anew the next time it is shown, and never shows, say, a Client's old phone number.
 *
 * Not thread-safe: use it from the thread of the scope.
 */
internal class AppointmentReads(
    scope: CoroutineScope,
    private val changes: Changes,
    fetchToday: suspend (DayKey) -> List<Appointment>,
    fetchDay: suspend (DayKey) -> List<Appointment>,
    /** Null when the server has no such Appointment. */
    find: suspend (id: String) -> Appointment?,
    fetchSources: suspend (CopyTargetKey) -> List<Appointment>,
) {
    /** Today's list holds every Scheduled Appointment that is overdue as well, so a change of any status is a change to it (see [statusChanged]). */
    val today = ReadCache(scope, changes, TODAY_LIMITS, about = { _, list -> list.flatMap { idsIn(it) } + ANY_STATUS }, fetch = fetchToday)

    val days = ReadCache(scope, changes, DAY_LIMITS, about = { _, list -> list.flatMap { idsIn(it) } }, fetch = fetchDay)

    /** The answer is null for an Appointment the server no longer has. */
    val one = ReadCache<AppointmentKey, Appointment?>(
        scope,
        changes,
        ONE_LIMITS,
        about = { key, appointment -> listOf(key.id) + appointment?.let { idsIn(it) }.orEmpty() },
    ) { find(it.id) }

    /**
     * The Completed Appointments that have something to copy into the key's Appointment, newest
     * first: the first is the source a copy takes when it names none. Each is stated for that
     * Appointment alone (which of its Services can be copied), so these are never taken for the
     * Appointments themselves ([get], [all]).
     *
     * Which Appointments are sources, and what of them, follows from the statuses, Services and
     * Clients of any of the Stylist's Appointments, also of one that is no source yet. So any
     * change at all puts a cached list out of date: it is loaded anew rather than copied from.
     */
    val sources = ReadCache(scope, changes, SOURCES_LIMITS, about = { _, _ -> listOf(Changes.ANY_CHANGE) }, fetch = fetchSources)

    // The reply that created an Appointment: its screen opens before any read of it has answered.
    private val newest = MutableStateFlow<Answer<Appointment>?>(null)

    /** Changes whenever an Appointment on screen does. */
    val shown: Flow<Unit> = combine(today.shown, days.shown, one.shown, newest) { _, _, _, _ -> }

    /**
     * The Appointment as it is on screen now: as its own read has it, and until that has answered
     * as a list on screen has it. Null when it is on no screen, or gone.
     */
    operator fun get(id: String): Appointment? = shownNow(id).data

    /** Every Appointment on screen now. */
    fun all(): Collection<Appointment> =
        (one.shown.value.values.mapNotNull { it.read.data } + listed()).distinctBy(Appointment::id)

    /** Whether a list on screen has a card for this Appointment: Today's, a day's, or an open copy sheet's. */
    fun isListed(id: String): Boolean = (listed() + sources.shown.value.values.flatMap { it.read.data.orEmpty() }).any { it.id == id }

    /**
     * What the screen of one Appointment shows, for as long as it is collected (see
     * [ReadCache.watch]). An Appointment that a list on screen has is shown from there until its
     * own read has answered, so its screen opens without a loading state.
     * [LoadState.Missing] when the server no longer has it.
     */
    fun watch(id: String): Flow<Read<Appointment?>> =
        combine(one.watch(AppointmentKey(id)), shown) { _, _ -> shownNow(id) }.distinctUntilChanged()

    /** As [get], with how the read stands for an Appointment that is not there. */
    fun shownNow(id: String): Read<Appointment?> {
        val own = one.shown(AppointmentKey(id))

        return when {
            own?.state == LoadState.Loaded -> if (own.data == null) Read(LoadState.Missing) else own
            else -> (listed().firstOrNull { it.id == id } ?: justCreated(id))?.let { Read(LoadState.Loaded, it) } ?: own ?: Read()
        }
    }

    /** The server has just created this Appointment: its screen shows the reply until its own read answers, or a change overtakes it. */
    fun created(appointment: Appointment) {
        newest.value = Answer(appointment, changes.count, idsIn(appointment))
    }

    /** Applies a change the server has just accepted to the copies of an Appointment on screen. */
    fun update(id: String, change: (Appointment) -> Appointment) {
        today.update { _, list -> list.map { if (it.id == id) change(it) else it } }
        days.update { _, list -> list.map { if (it.id == id) change(it) else it } }
        one.update { key, appointment -> if (key.id == id && appointment != null) change(appointment) else appointment }
        changes.changed(id)
    }

    /**
     * The server has just saved this Client. Appointments repeat the Client's name, phone number,
     * language and address: every copy on screen gets them now, and no cached answer from before
     * the save is served again, on whatever day it is. So no reminder goes to the old number.
     */
    fun clientSaved(saved: ClientProfile) {
        today.update { _, list -> list.map { it.withClient(saved) } }
        days.update { _, list -> list.map { it.withClient(saved) } }
        one.update { _, appointment -> appointment?.withClient(saved) }
        sources.update { _, list -> list.map { it.withClient(saved) } }
        changes.changed(saved.id)
    }

    /**
     * Marks that the server has accepted another status for this Appointment, or may have. Put the
     * status into the copies on screen with [update].
     */
    fun statusChanged(id: String) = changes.changed(id, ANY_STATUS)

    /** The server has just deleted this Appointment: it leaves the lists on screen, an open copy sheet's too, and its own screen says that it is gone. */
    fun forget(id: String) {
        today.update { _, list -> list.filterNot { it.id == id } }
        days.update { _, list -> list.filterNot { it.id == id } }
        sources.update { _, list -> list.filterNot { it.id == id } }
        one.answer(AppointmentKey(id), null)
        changes.changed(id)
    }

    /** Empties the caches, for a Stylist who has signed out. Call [Changes.startOver] first. */
    suspend fun clear() {
        newest.value = null
        today.clear()
        days.clear()
        one.clear()
        sources.clear()
    }

    private fun listed(): List<Appointment> =
        (today.shown.value.values + days.shown.value.values).flatMap { it.read.data.orEmpty() }

    private fun justCreated(id: String): Appointment? = newest.value?.takeIf { it.value.id == id && changes.isCurrent(it) }?.value

    private companion object {
        /** Today, and the day before it around midnight or after a timezone change. */
        val TODAY_LIMITS = CacheLimits(answers = 4)

        /** Two months of days to go back and forth between. */
        val DAY_LIMITS = CacheLimits(answers = 62)

        val ONE_LIMITS = CacheLimits(answers = 100)

        /** The copy sheets opened of late. A list is kept only until the next change (see [sources]). */
        val SOURCES_LIMITS = CacheLimits(answers = 20)

        /** Names the status of every Appointment, in what an answer is about. */
        const val ANY_STATUS = "appointment-status"

        /** What an Appointment repeats of others: a change to one of these is a change to it. */
        fun idsIn(appointment: Appointment): List<String> =
            listOf(appointment.id, appointment.primaryClientId) + appointment.participants.map { it.clientId }
    }
}
