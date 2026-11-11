package com.northphoenix.hairdresserclientmanager.domain

import java.time.Duration
import java.time.Instant

/** A Google calendar on the phone, as Calendar Integration sees it in Android's calendar store. */
data class PhoneCalendar(
    /** Its row in the phone's calendar store. */
    val id: Long,
    /** The Google account it belongs to on the phone: an email address. */
    val accountName: String,
    /** Its name as Google Calendar shows it. */
    val name: String,
    /** The calendar's own address in Google: the account's email for the account's main calendar, "…@group.calendar.google.com" for one made beside it. */
    val ownerAccount: String,
    /** Whether events may be added to it (contributor access or more). */
    val canWrite: Boolean,
    /** Whether the phone keeps its events and syncs them with Google. Calendar Integration turns it on for its calendar. */
    val syncsEvents: Boolean,
) {
    /** The account's main calendar, named after the account. */
    val isMain: Boolean get() = ownerAccount.equals(accountName, ignoreCase = true)
}

/** Whether the app may use the phone's calendar, and if not, whether Android can still ask. */
enum class CalendarAccess {
    GRANTED,
    ASKABLE,

    /** Refused for good: only Android's settings for the app can allow it now. */
    BLOCKED,
}

/** Android's sync settings that decide whether a calendar made in Google comes to the phone. */
data class PhoneSync(
    /** "Auto-sync data", for all accounts. */
    val auto: Boolean = true,
    /** The Google accounts whose calendar sync is off. */
    val calendarOffFor: Set<String> = emptySet(),
)

/** The calendar the app has asked Google to create, from when, until it is on the phone. */
data class PendingCalendar(val googleId: String, val since: Instant)

/** Where the setup of Calendar Integration is, and so what the screen asks the Stylist to do next. */
sealed interface CalendarSetupStep {
    /** The app may not use the phone's calendar yet. */
    data class Access(val blocked: Boolean) : CalendarSetupStep

    /** The phone has no Google calendar at all: no Google account on it, or one whose calendar does not sync. */
    data class NoGoogleAccount(val sync: PhoneSync) : CalendarSetupStep

    /** Ready for a calendar: one is made, or [existing], a calendar of the app's name already on the phone, is used. */
    data class Calendar(val existing: PhoneCalendar?) : CalendarSetupStep

    /**
     * The calendar was made in Google and has not come to the phone yet. [slow]: longer than it
     * usually takes, so the screen says what can hold it up, such as sync that is off.
     */
    data class Waiting(val slow: Boolean, val sync: PhoneSync) : CalendarSetupStep

    /** The calendar that was made is on the phone: the app connects to it. */
    data class Arrived(val calendar: PhoneCalendar) : CalendarSetupStep
}

object CalendarSetup {
    /** A calendar made in Google usually reaches the phone well within this. */
    val SLOW_AFTER: Duration = Duration.ofSeconds(60)

    /** How the app names the calendar it makes, in both languages: a calendar named either is taken for it. */
    val NAMES = listOf("Клиенты", "Clients")

    /** Whether [name] is the name of the app's calendar, without the difference of letter case, "ё" and "е", and spaces. */
    fun isOwnName(name: String): Boolean = ColorFormulaOffer.fold(name) in NAMES.map(ColorFormulaOffer::fold)

    /** The calendar that Google made as [googleId], once it is on the phone. */
    fun find(calendars: List<PhoneCalendar>, googleId: String): PhoneCalendar? = calendars.firstOrNull { it.ownerAccount.equals(googleId, ignoreCase = true) }

    /**
     * The calendars the Stylist can pick instead of making one: those she may add events to,
     * one of the app's name first, then each account's main calendar, then the rest by name.
     */
    fun choices(calendars: List<PhoneCalendar>): List<PhoneCalendar> =
        calendars.filter { it.canWrite }.sortedWith(compareBy<PhoneCalendar>({ !isOwnName(it.name) }, { !it.isMain }, { it.name.lowercase() }, { it.accountName }))

    /** The next step, from what the phone says now. */
    fun step(access: CalendarAccess, calendars: List<PhoneCalendar>, pending: PendingCalendar?, sync: PhoneSync, now: Instant): CalendarSetupStep {
        if (access != CalendarAccess.GRANTED) {
            return CalendarSetupStep.Access(blocked = access == CalendarAccess.BLOCKED)
        }
        if (pending != null) {
            return find(calendars, pending.googleId)?.let(CalendarSetupStep::Arrived)
                ?: CalendarSetupStep.Waiting(slow = Duration.between(pending.since, now) >= SLOW_AFTER, sync = sync)
        }
        if (calendars.isEmpty()) {
            return CalendarSetupStep.NoGoogleAccount(sync)
        }

        return CalendarSetupStep.Calendar(existing = calendars.firstOrNull { it.canWrite && isOwnName(it.name) })
    }
}
