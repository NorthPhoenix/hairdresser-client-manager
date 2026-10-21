package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.domain.CalendarWeek
import com.northphoenix.hairdresserclientmanager.domain.HomeOutcomes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** A day of the Stylist: [day] in [zone], the timezone its start and its end are worked out in. Another timezone is another key. */
internal data class DayKey(val day: LocalDate, val zone: ZoneId)

/** The seven days from [firstDay] on in [zone]: a week of the Calendar's week strip. */
internal data class WeekKey(val firstDay: LocalDate, val zone: ZoneId)

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
 * Calendar day ([days], `appointment.list`), a week of the Calendar's week strip ([weeks], `appointment.list`
 * for seven days), one Appointment by its id ([one], `appointment.get`)
 * and what Appointment Copying can copy from ([sources], `appointment.completedSources`).
 *
 * A change is told here, and reaches the reads in two ways. What is on screen gets what the
 * server has confirmed at once ([update], [clientSaved], [forget]), so it is right also when the
 * reload after the change fails. And the change is marked in [changes], so that no cached answer
 * that holds the changed Appointment or Client is served again: a day that is not on screen is
 * loaded anew the next time it is shown, and never shows, say, a Client's old phone number.
 * An Appointment that is new, or at another time, is in no answer of its day yet: the day is
 * marked by its name ([startChanged]), and every other cached day is still shown at once.
 *
 * A change the Stylist does not wait for is shown before the server has answered it ([ahead]):
 * every copy of the Appointment on screen has it at once, and a refresh that arrives while it is
 * on its way does not bring the old value back.
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
    fetchWeek: suspend (WeekKey) -> List<Appointment>,
) {
    /**
     * Today's list holds every Scheduled Appointment that is overdue as well, of whatever day. So
     * an Appointment that is in no answer yet can join it with another status ([statusChanged])
     * and with another time, or as a new one ([startChanged]): each is a change to the list.
     */
    val today = ReadCache(scope, changes, TODAY_LIMITS, about = { _, list -> list.flatMap { idsIn(it) } + TODAYS_LIST }, fetch = fetchToday)

    // The timezones a day was read in. One moment is on another day in another timezone, and each of them is another key.
    private val zones = mutableSetOf<ZoneId>()

    /**
     * A day's list is of the Appointments that start on that day (`appointment.list` selects by
     * the start alone). One that is new, or at another time, joins the list of the day it starts
     * on, which does not hold it yet: an answer is about its day as well ([startsOn]).
     */
    val days = ReadCache<DayKey, List<Appointment>>(scope, changes, DAY_LIMITS, about = { key, list -> list.flatMap { idsIn(it) } + startsOn(key) }) { key ->
        zones += key.zone
        fetchDay(key)
    }

    /**
     * The Appointments that start in a week of the Calendar's week strip, which marks the days that
     * have any. Such a list is about each of its seven days as a day's list is ([startsOn]).
     */
    val weeks = ReadCache<WeekKey, List<Appointment>>(
        scope,
        changes,
        WEEK_LIMITS,
        about = { key, list -> list.flatMap { idsIn(it) } + weekDays(key).map(::startsOn) },
    ) { key ->
        zones += key.zone
        fetchWeek(key)
    }

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

    // The changes to Appointments that are shown ahead of the server's answer (see [ahead]), by what names each: the Appointment and the edit.
    private val expected = MutableStateFlow<Map<String, Pair<String, (Appointment) -> Appointment>>>(emptyMap())

    /** Changes whenever an Appointment on screen does. */
    val shown: Flow<Unit> = combine(today.shown, days.shown, one.shown, newest, expected) { _, _, _, _, _ -> }

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

    /**
     * As [get], with how the read stands for an Appointment that is not there, and with what the
     * server has of it ([Read.saved]) where a change is shown ahead of its answer.
     */
    fun shownNow(id: String): Read<Appointment?> {
        val own = one.shown(AppointmentKey(id))

        return when {
            own?.state == LoadState.Loaded -> if (own.data == null) Read(LoadState.Missing) else own
            else -> inLists(id) ?: justCreated(id)?.let { Read(LoadState.Loaded, it.asShown(), it) } ?: own ?: Read()
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
        weeks.update { _, list -> list.map { if (it.id == id) change(it) else it } }
        one.update { key, appointment -> if (key.id == id && appointment != null) change(appointment) else appointment }
        changes.changed(id)
    }

    /**
     * A change to this Appointment that the Stylist does not wait for (see [Ahead]): [edit] is
     * on every copy of the Appointment on screen from [Ahead.show] on, whatever is loaded
     * meanwhile, and is what the server has once it has accepted the change ([update]).
     * [change] names it, as the key of its action does: showing it again replaces its edit.
     */
    fun ahead(change: String, id: String, edit: (Appointment) -> Appointment) = Ahead(
        show = {
            today.expect(change) { _, list -> list.map { if (it.id == id) edit(it) else it } }
            days.expect(change) { _, list -> list.map { if (it.id == id) edit(it) else it } }
            weeks.expect(change) { _, list -> list.map { if (it.id == id) edit(it) else it } }
            one.expect(change) { key, appointment -> if (key.id == id && appointment != null) edit(appointment) else appointment }
            expected.value += change to (id to edit)
        },
        saved = { update(id, edit) },
        settle = {
            today.settled(change)
            days.settled(change)
            weeks.settled(change)
            one.settled(change)
            expected.value -= change
        },
    )

    /**
     * An Appointment that left Today's list with an outcome and is being set back to Scheduled
     * ('Undo' on the Home View): [card] is how the list showed it. It is on the list again at once,
     * and once the server has it Scheduled it is what the list has of the server until the reload,
     * which answers the same: an overdue Scheduled Appointment is always on Today's list.
     * [change] names it apart from the status change itself.
     */
    fun backOnToday(change: String, card: Appointment) = Ahead(
        show = { today.expect(change) { _, list -> HomeOutcomes.withCardBack(list, card) } },
        saved = { today.update { _, list -> HomeOutcomes.withCardBack(list, card) } },
        settle = { today.settled(change) },
    )

    /**
     * The server has just saved this Client. Appointments repeat the Client's name, phone number,
     * language and address: every copy on screen gets them now, and no cached answer from before
     * the save is served again, on whatever day it is. So no reminder goes to the old number.
     */
    fun clientSaved(saved: ClientProfile) {
        today.update { _, list -> list.map { it.withClient(saved) } }
        days.update { _, list -> list.map { it.withClient(saved) } }
        weeks.update { _, list -> list.map { it.withClient(saved) } }
        one.update { _, appointment -> appointment?.withClient(saved) }
        sources.update { _, list -> list.map { it.withClient(saved) } }
        changes.changed(saved.id)
    }

    /**
     * Marks that the server has accepted another status for this Appointment, or may have. Put the
     * status into the copies on screen with [update].
     */
    fun statusChanged(id: String) = changes.changed(id, TODAYS_LIST)

    /**
     * Marks that the server has put an Appointment at [startsAt], or may have: a new one ([id]
     * null), or one it had, at another time. What that can make wrong is the day it starts on
     * now, Today's list, and for an Appointment the server had, its own read and the day it has
     * left, which are about it by its id. Every other cached day is served as it was.
     *
     * The day is marked in every timezone a day was read in, so that no cached answer is missed
     * after the Stylist's timezone has changed.
     */
    fun startChanged(id: String?, startsAt: Instant) {
        val itsDays = zones.map { startsOn(DayKey(startsAt.atZone(it).toLocalDate(), it)) }

        changes.changed(*(listOfNotNull(id, TODAYS_LIST) + itsDays).toTypedArray())
    }

    /** The server has just deleted this Appointment: it leaves the lists on screen, an open copy sheet's too, and its own screen says that it is gone. */
    fun forget(id: String) {
        today.update { _, list -> list.filterNot { it.id == id } }
        days.update { _, list -> list.filterNot { it.id == id } }
        weeks.update { _, list -> list.filterNot { it.id == id } }
        sources.update { _, list -> list.filterNot { it.id == id } }
        one.answer(AppointmentKey(id), null)
        changes.changed(id)
    }

    /** Empties the caches, for a Stylist who has signed out. Call [Changes.startOver] first. */
    suspend fun clear() {
        newest.value = null
        expected.value = emptyMap()
        zones.clear()
        today.clear()
        days.clear()
        weeks.clear()
        one.clear()
        sources.clear()
    }

    private fun listed(): List<Appointment> =
        (today.shown.value.values + days.shown.value.values).flatMap { it.read.data.orEmpty() }

    /** The Appointment as a list on screen has it: as it is shown, and as the server has it. */
    private fun inLists(id: String): Read<Appointment?>? =
        (today.shown.value.values + days.shown.value.values).firstNotNullOfOrNull { list ->
            list.read.data?.firstOrNull { it.id == id }?.let { shown ->
                Read(LoadState.Loaded, shown, list.read.saved?.firstOrNull { it.id == id } ?: shown)
            }
        }

    private fun justCreated(id: String): Appointment? = newest.value?.takeIf { it.value.id == id && changes.isCurrent(it) }?.value

    /** With the changes to it that are shown ahead of their answer. The reads lay them over their own answers themselves. */
    private fun Appointment.asShown(): Appointment =
        expected.value.values.fold(this) { shown, (id, edit) -> if (shown.id == id) edit(shown) else shown }

    private companion object {
        /** Today, and the day before it around midnight or after a timezone change. */
        val TODAY_LIMITS = CacheLimits(answers = 4)

        /** Two months of days to go back and forth between. */
        val DAY_LIMITS = CacheLimits(answers = 62)

        /** As for the days: two months of weeks. */
        val WEEK_LIMITS = CacheLimits(answers = 9)

        val ONE_LIMITS = CacheLimits(answers = 100)

        /** The copy sheets opened of late. A list is kept only until the next change (see [sources]). */
        val SOURCES_LIMITS = CacheLimits(answers = 20)

        /** Names Today's list as a whole, in what an answer is about: for an Appointment that is in no answer yet and can join the list. */
        const val TODAYS_LIST = "todays-list"

        /** Names the list of a day, in what an answer is about: for an Appointment that is in no answer yet and starts on that day. */
        fun startsOn(key: DayKey): String = "starts-on:${key.day}@${key.zone.id}"

        fun weekDays(key: WeekKey): List<DayKey> = CalendarWeek.days(key.firstDay).map { DayKey(it, key.zone) }

        /** What an Appointment repeats of others: a change to one of these is a change to it. */
        fun idsIn(appointment: Appointment): List<String> =
            listOf(appointment.id, appointment.primaryClientId) + appointment.participants.map { it.clientId }
    }
}
