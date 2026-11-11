package com.northphoenix.hairdresserclientmanager.data

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.northphoenix.hairdresserclientmanager.domain.CalendarEventRow
import com.northphoenix.hairdresserclientmanager.domain.CalendarMirror
import com.northphoenix.hairdresserclientmanager.domain.MirrorEvent
import com.northphoenix.hairdresserclientmanager.domain.PendingCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneSync
import com.northphoenix.hairdresserclientmanager.domain.WrittenEvent
import com.northphoenix.hairdresserclientmanager.state.CalendarConnection
import com.northphoenix.hairdresserclientmanager.state.CalendarConnectionStore
import com.northphoenix.hairdresserclientmanager.state.PhoneCalendarStore
import kotlinx.serialization.Serializable
import java.time.Instant

/** Google accounts, as Android names their type. */
private const val GOOGLE = "com.google"

/** What the app needs of Android's calendar permission. */
object DeviceCalendar {
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun hasPermission(context: Context): Boolean =
        PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * [PhoneCalendarStore] on Android's calendar store ([CalendarContract]). Its Google calendars are
 * synced with Google by Android, so an event written here is in Google Calendar everywhere once
 * the phone has synced.
 *
 * With [alsoLocal] (debug builds only) the phone's local calendars count as Google ones, so the
 * setup and the mirroring can be tried on an emulator without a Google account.
 */
class AndroidPhoneCalendarStore(context: Context, private val alsoLocal: Boolean = false) : PhoneCalendarStore {
    private val context = context.applicationContext
    private val resolver: ContentResolver = this.context.contentResolver

    override fun hasAccess(): Boolean = DeviceCalendar.hasPermission(context)

    override fun calendars(): List<PhoneCalendar> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.SYNC_EVENTS,
        )
        val types = listOfNotNull(GOOGLE, CalendarContract.ACCOUNT_TYPE_LOCAL.takeIf { alsoLocal })
        val selection = types.joinToString(" OR ") { "${Calendars.ACCOUNT_TYPE} = ?" }
        val calendars = mutableListOf<PhoneCalendar>()

        resolver.query(Calendars.CONTENT_URI, projection, selection, types.toTypedArray(), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val account = cursor.getString(1).orEmpty()

                calendars += PhoneCalendar(
                    id = cursor.getLong(0),
                    accountName = account,
                    name = cursor.getString(2).orEmpty(),
                    ownerAccount = cursor.getString(3).orEmpty().ifEmpty { account },
                    canWrite = cursor.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    syncsEvents = cursor.getInt(5) == 1,
                )
            }
        }

        return calendars
    }

    override fun sync(): PhoneSync =
        try {
            val accounts = calendars().map { it.accountName }.toSet()

            PhoneSync(
                auto = ContentResolver.getMasterSyncAutomatically(),
                calendarOffFor = accounts.filterTo(mutableSetOf()) { !ContentResolver.getSyncAutomatically(Account(it, GOOGLE), CalendarContract.AUTHORITY) },
            )
        } catch (_: SecurityException) {
            PhoneSync()
        }

    override fun requestSync(accounts: Collection<String>) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }

        accounts.forEach { ContentResolver.requestSync(Account(it, GOOGLE), CalendarContract.AUTHORITY, extras) }
    }

    override fun keepSyncing(calendarId: Long) {
        val values = ContentValues().apply { put(Calendars.SYNC_EVENTS, 1) }

        resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), values, null, null)
    }

    override fun events(calendarId: Long): List<CalendarEventRow> {
        val rows = mutableListOf<CalendarEventRow>()

        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.CUSTOM_APP_URI),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += CalendarEventRow(cursor.getLong(0), CalendarMirror.appointmentOfTag(cursor.getString(1)))
            }
        }

        return rows
    }

    override fun insert(calendarId: Long, event: MirrorEvent): Long {
        val values = values(event).apply { put(Events.CALENDAR_ID, calendarId) }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("The calendar did not take the event")

        return ContentUris.parseId(uri)
    }

    override fun update(eventId: Long, event: MirrorEvent) {
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values(event), null, null)
    }

    override fun delete(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    /** Everything an event says. No notifications of its own (the app has Stylist Reminders), and no guests to invite. */
    private fun values(event: MirrorEvent) = ContentValues().apply {
        put(Events.TITLE, event.title)
        put(Events.DESCRIPTION, event.description)
        put(Events.EVENT_LOCATION, event.location)
        put(Events.DTSTART, event.startMillis)
        put(Events.DTEND, event.endMillis)
        put(Events.EVENT_TIMEZONE, event.timezone)
        put(Events.HAS_ALARM, 0)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        put(Events.CUSTOM_APP_PACKAGE, context.packageName)
        put(Events.CUSTOM_APP_URI, CalendarMirror.tag(event.appointmentId))
    }
}

/** [CalendarConnectionStore] in this app's own preferences. Device-local bookkeeping about the copy in the calendar (ADR 0003, 0009). */
class SharedPreferencesCalendarConnectionStore(context: Context) : CalendarConnectionStore {
    private val preferences = context.applicationContext.getSharedPreferences("calendar_integration", Context.MODE_PRIVATE)

    override var connection: CalendarConnection?
        get() = read<StoredConnection>(CONNECTION)?.let {
            CalendarConnection(it.stylistId, it.calendarId, it.googleId, it.accountName, it.calendarName, Instant.ofEpochMilli(it.connectedAt))
        }
        set(value) = write(CONNECTION, value?.let { StoredConnection(it.stylistId, it.calendarId, it.googleId, it.accountName, it.calendarName, it.connectedAt.toEpochMilli()) })

    override var written: Map<String, WrittenEvent>
        get() = read<Map<String, StoredEvent>>(WRITTEN).orEmpty().mapValues { (_, event) -> WrittenEvent(event.eventId, event.fingerprint) }
        set(value) = write(WRITTEN, value.mapValues { (_, event) -> StoredEvent(event.eventId, event.fingerprint) }.takeIf { it.isNotEmpty() })

    override var pending: PendingCalendar?
        get() = read<StoredPending>(PENDING)?.let { PendingCalendar(it.googleId, Instant.ofEpochMilli(it.since)) }
        set(value) = write(PENDING, value?.let { StoredPending(it.googleId, it.since.toEpochMilli()) })

    override var lastSync: Pair<Instant, Int>?
        get() = read<StoredSync>(LAST_SYNC)?.let { Instant.ofEpochMilli(it.at) to it.mirrored }
        set(value) = write(LAST_SYNC, value?.let { (at, mirrored) -> StoredSync(at.toEpochMilli(), mirrored) })

    private inline fun <reified T> read(key: String): T? =
        preferences.getString(key, null)?.let { runCatching { hcmJson.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> write(key: String, value: T?) {
        preferences.edit().apply { if (value == null) remove(key) else putString(key, hcmJson.encodeToString(value)) }.apply()
    }

    @Serializable
    private data class StoredConnection(
        val stylistId: String,
        val calendarId: Long,
        val googleId: String,
        val accountName: String,
        val calendarName: String,
        val connectedAt: Long,
    )

    @Serializable
    private data class StoredEvent(val eventId: Long, val fingerprint: String)

    @Serializable
    private data class StoredPending(val googleId: String, val since: Long)

    @Serializable
    private data class StoredSync(val at: Long, val mirrored: Int)

    private companion object {
        const val CONNECTION = "connection"
        const val WRITTEN = "written"
        const val PENDING = "pending"
        const val LAST_SYNC = "last_sync"
    }
}
