package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.domain.CalendarEventRow
import com.northphoenix.hairdresserclientmanager.domain.CalendarMirror
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetup
import com.northphoenix.hairdresserclientmanager.domain.MirrorEvent
import com.northphoenix.hairdresserclientmanager.domain.MirrorStep
import com.northphoenix.hairdresserclientmanager.domain.MirrorWords
import com.northphoenix.hairdresserclientmanager.domain.PhoneCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneSync
import com.northphoenix.hairdresserclientmanager.domain.WrittenEvent
import com.northphoenix.hairdresserclientmanager.domain.zoneOrSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * The phone's calendar store, as Calendar Integration uses it. Every call but [hasAccess] needs the
 * calendar permission and throws [SecurityException] without it.
 */
interface PhoneCalendarStore {
    fun hasAccess(): Boolean

    /** The Google calendars on the phone. */
    fun calendars(): List<PhoneCalendar>

    fun sync(): PhoneSync

    /** Asks Android to sync the calendars of these Google accounts now, so a calendar made on calendar.google.com comes sooner. */
    fun requestSync(accounts: Collection<String>)

    /** Makes the phone keep and sync the events of a calendar. */
    fun keepSyncing(calendarId: Long)

    /** The events of a calendar that are not deleted, with the Appointment the tag of each names. */
    fun events(calendarId: Long): List<CalendarEventRow>

    /** Writes [event] into the calendar and returns its row. */
    fun insert(calendarId: Long, event: MirrorEvent): Long

    fun update(eventId: Long, event: MirrorEvent)

    fun delete(eventId: Long)
}

/** The calendar the app mirrors into, for which Stylist, and since when. */
data class CalendarConnection(
    val stylistId: String,
    val calendarId: Long,
    val googleId: String,
    val accountName: String,
    val calendarName: String,
    /** Appointments that start from here on are mirrored. */
    val connectedAt: Instant,
)

/** Where Calendar Integration keeps, on this phone, what it needs between runs of the app. */
interface CalendarConnectionStore {
    var connection: CalendarConnection?

    /** Which event the app wrote for which Appointment of [connection], and what it said then. */
    var written: Map<String, WrittenEvent>

    /** When the last mirroring ended well, and how many Appointments it mirrored. */
    var lastSync: Pair<Instant, Int>?
}

/** Why the phone could not mirror the last time it tried. */
enum class CalendarProblem {
    /** Calendar access was taken away from the app. The Stylist allows it again. */
    NO_ACCESS,

    /** The calendar is not on the phone any more: deleted in Google, or its account removed from the phone. */
    CALENDAR_GONE,

    /** The Appointments could not be loaded. Passes on its own. */
    SERVER,

    /** The phone's calendar store failed. */
    PHONE,
    ;

    /** Something only the Stylist can put right, which Home tells her about. */
    val needsStylist: Boolean get() = this == NO_ACCESS || this == CALENDAR_GONE
}

data class CalendarMirrorState(
    /** The connection of the Stylist signed in now; null when she has none on this phone. */
    val connection: CalendarConnection? = null,
    val running: Boolean = false,
    val lastSyncedAt: Instant? = null,
    val mirrored: Int = 0,
    val problem: CalendarProblem? = null,
)

/**
 * Calendar Integration on this phone (ADR 0003): mirrors the Appointments of the Stylist who is
 * signed in into the calendar she connected. Each mirroring compares the events the Appointments
 * should have with those the app wrote (see [CalendarMirror.plan]), so it can run any number of
 * times, and does after every change and when the app comes into view. Requests that come close
 * together are one mirroring, and only one runs at a time.
 *
 * Nothing it does can make a change of the Stylist's fail: what goes wrong is [CalendarMirrorState.problem].
 */
class CalendarSync(
    private val phone: PhoneCalendarStore,
    private val store: CalendarConnectionStore,
    private val scope: CoroutineScope,
    private val fetch: suspend (start: Instant, end: Instant) -> List<Appointment>,
    private val words: (Language) -> MirrorWords,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
    private val quietMillis: Long = 1_500,
) {
    private var stylist: Stylist? = null
    private val _state = MutableStateFlow(CalendarMirrorState())
    val state: StateFlow<CalendarMirrorState> = _state.asStateFlow()

    // true: run at once ("Sync now"); false: after the changes that come together have come.
    private val requests = Channel<Boolean>(Channel.CONFLATED)

    init {
        scope.launch {
            for (urgent in requests) {
                if (!urgent) {
                    delay(quietMillis)
                }
                // What was asked for meanwhile is this mirroring too.
                requests.tryReceive()
                mirror()
            }
        }
    }

    /**
     * The Stylist signed in now, as the server last sent her; null when nobody is. Her timezone and
     * language are in every event, so a change of either mirrors again.
     */
    fun follow(next: Stylist?) {
        val previous = stylist
        val shown = next?.takeIf { it.onboardingComplete }

        stylist = shown
        if (shown?.id != previous?.id) {
            val connection = store.connection?.takeIf { it.stylistId == shown?.id }
            val last = store.lastSync.takeIf { connection != null }

            _state.value = CalendarMirrorState(connection = connection, lastSyncedAt = last?.first, mirrored = last?.second ?: 0)
        }
        if (shown != null && (shown.id != previous?.id || shown.timezone != previous.timezone || shown.language != previous.language)) {
            syncSoon()
        }
    }

    /** A change was saved, or may have been: mirror once the changes that come together have come. */
    fun syncSoon() {
        if (_state.value.connection != null) {
            requests.trySend(false)
        }
    }

    /** "Sync now", and a return to the setup screen with something put right. */
    fun syncNow() {
        if (_state.value.connection != null) {
            requests.trySend(true)
        }
    }

    // --- setup ---

    /** What the setup screen needs to know of the phone. */
    suspend fun phoneFacts(): PhoneFacts =
        withContext(io) {
            if (!phone.hasAccess()) {
                PhoneFacts(access = false)
            } else {
                try {
                    PhoneFacts(access = true, calendars = phone.calendars(), sync = phone.sync())
                } catch (_: SecurityException) {
                    PhoneFacts(access = false)
                }
            }
        }

    /** Asks Android to bring the calendars of [accounts] to the phone now. Quietly: it only makes it sooner. */
    fun askForSync(accounts: Collection<String>) {
        scope.launch {
            withContext(io) {
                try {
                    phone.requestSync(accounts)
                } catch (_: Exception) {
                    // Android syncs on its own as well.
                }
            }
        }
    }

    /**
     * Connects [calendar] for the Stylist signed in now: from now on her Appointments are mirrored
     * into it, starting with those that start from now. A new connection starts with no events written.
     */
    fun connect(calendar: PhoneCalendar) {
        val current = stylist ?: return
        val connection = CalendarConnection(current.id, calendar.id, calendar.ownerAccount, calendar.accountName, calendar.name, clock())

        store.connection = connection
        store.written = emptyMap()
        store.lastSync = null
        _state.value = CalendarMirrorState(connection = connection)
        scope.launch {
            withContext(io) {
                try {
                    if (!calendar.syncsEvents) {
                        phone.keepSyncing(calendar.id)
                    }
                } catch (_: Exception) {
                    // The mirroring tries again and says what is wrong.
                }
            }
        }
        syncNow()
    }

    /** Stops mirroring. The events that were written stay in the calendar. */
    fun disconnect() {
        store.connection = null
        store.written = emptyMap()
        store.lastSync = null
        _state.value = CalendarMirrorState()
    }

    // --- mirroring ---

    private suspend fun mirror() {
        val current = stylist ?: return
        val connection = store.connection?.takeIf { it.stylistId == current.id } ?: return

        _state.update { it.copy(running = true) }
        val problem = try {
            mirror(current, connection)
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            CalendarProblem.NO_ACCESS
        } catch (_: Exception) {
            CalendarProblem.PHONE
        }

        // Not for a connection that was replaced or ended while this ran.
        _state.update {
            if (it.connection?.stylistId != connection.stylistId || store.connection?.connectedAt != connection.connectedAt) {
                it.copy(running = false)
            } else {
                val last = store.lastSync

                it.copy(running = false, problem = problem, connection = store.connection, lastSyncedAt = last?.first, mirrored = last?.second ?: 0)
            }
        }
    }

    /** One mirroring. Null when it went well, else what stopped it. */
    private suspend fun mirror(stylist: Stylist, connection: CalendarConnection): CalendarProblem? {
        val calendar = when (val found = withContext(io) { find(connection) }) {
            is Found.Calendar -> found.calendar
            Found.NoAccess -> return CalendarProblem.NO_ACCESS
            Found.Gone -> return CalendarProblem.CALENDAR_GONE
        }

        // The phone may have made it a row of another number, after its account was added again; its name may have been changed in Google.
        if (calendar.id != connection.calendarId || calendar.name != connection.calendarName || calendar.accountName != connection.accountName) {
            store.connection = connection.copy(calendarId = calendar.id, calendarName = calendar.name, accountName = calendar.accountName)
        }

        val appointments = try {
            fetch(connection.connectedAt, clock().plus(HORIZON))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CalendarProblem.SERVER
        }
        // An event needs a timezone the phone knows. One it does not know is shown in the phone's own everywhere in the app (see [zoneOrSystem]).
        val desired = CalendarMirror.events(appointments, connection.connectedAt, zoneOrSystem(stylist.timezone).id, words(stylist.language))

        withContext(io) {
            if (!calendar.syncsEvents) {
                phone.keepSyncing(calendar.id)
            }

            val written = store.written
            val steps = CalendarMirror.plan(desired, written, phone.events(calendar.id))
            val result = written.toMutableMap()

            try {
                for (step in steps) {
                    when (step) {
                        is MirrorStep.Insert -> result[step.event.appointmentId] = WrittenEvent(phone.insert(calendar.id, step.event), step.event.fingerprint)
                        is MirrorStep.Update -> {
                            phone.update(step.eventId, step.event)
                            result[step.event.appointmentId] = WrittenEvent(step.eventId, step.event.fingerprint)
                        }
                        is MirrorStep.Keep -> Unit
                        is MirrorStep.Delete -> {
                            phone.delete(step.eventId)
                            result.values.removeAll { it.eventId == step.eventId }
                        }
                    }
                }
                // Every event of the app's that is not one of these was deleted, or was gone already.
                result.keys.retainAll(desired.map { it.appointmentId }.toSet())
            } finally {
                // Also what was done before a failure, so the next mirroring does not write it twice.
                store.written = result
            }
        }
        store.lastSync = clock() to desired.size

        return null
    }

    /** The connected calendar on the phone: by its row, else by its address in Google. */
    private fun find(connection: CalendarConnection): Found {
        if (!phone.hasAccess()) {
            return Found.NoAccess
        }

        val calendars = phone.calendars()
        val calendar = calendars.firstOrNull { it.id == connection.calendarId && it.ownerAccount.equals(connection.googleId, ignoreCase = true) }
            ?: CalendarSetup.find(calendars, connection.googleId)

        return calendar?.let(Found::Calendar) ?: Found.Gone
    }

    private sealed interface Found {
        data class Calendar(val calendar: PhoneCalendar) : Found

        data object NoAccess : Found

        data object Gone : Found
    }

    private companion object {
        /** How far ahead Appointments are mirrored: all there are. */
        val HORIZON: Duration = Duration.ofDays(366L * 5)
    }
}

/** What the phone says, for the setup screen. Without [access] it says nothing more. */
data class PhoneFacts(
    val access: Boolean,
    val calendars: List<PhoneCalendar> = emptyList(),
    val sync: PhoneSync = PhoneSync(),
)
