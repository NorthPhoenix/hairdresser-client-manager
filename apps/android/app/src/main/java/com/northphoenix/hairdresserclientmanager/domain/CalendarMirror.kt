package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * One event as Calendar Integration writes it for an Appointment (ADR 0003): named after the
 * primary Client, at the Appointment's place, in the Stylist's timezone. What it says is all here,
 * so two events with the same [fingerprint] read the same.
 */
data class MirrorEvent(
    val appointmentId: String,
    val title: String,
    val description: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long,
    val timezone: String,
) {
    /** What was written, in a short string: an event whose Appointment still makes the same one is not written again. */
    val fingerprint: String by lazy {
        val text = listOf(title, description, location, startMillis.toString(), endMillis.toString(), timezone).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

        digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The words of an event's description, in the Stylist's language. Each takes what it introduces:
 * `services("Стрижка, Укладка")` is "Услуги: Стрижка, Укладка".
 */
interface MirrorWords {
    fun services(names: String): String

    fun clients(names: String): String

    fun phone(number: String): String

    /** The last line: where the event comes from, and that it is changed there. */
    val footer: String
}

/** The event the app wrote for an Appointment, as it remembers it: which one, and what it said then. */
data class WrittenEvent(val eventId: Long, val fingerprint: String)

/** An event in the calendar, and the Appointment its tag names, if it has one of the app's tags. */
data class CalendarEventRow(val eventId: Long, val appointmentId: String?)

/** What one step of mirroring does to the calendar. */
sealed interface MirrorStep {
    /** The Appointment has no event yet, or its event is gone: write one. */
    data class Insert(val event: MirrorEvent) : MirrorStep

    /** The Appointment's event says something else than it should now. */
    data class Update(val eventId: Long, val event: MirrorEvent) : MirrorStep

    /** The Appointment's event is as it should be. */
    data class Keep(val appointmentId: String, val written: WrittenEvent) : MirrorStep

    /** One of the app's events whose Appointment is no longer mirrored: deleted, canceled, or moved to before the connection. */
    data class Delete(val eventId: Long) : MirrorStep
}

object CalendarMirror {
    /** An Appointment without an end time is an event of this length (ADR 0003). */
    val DEFAULT_LENGTH: Duration = Duration.ofMinutes(60)

    private const val TAG_PREFIX = "hcm://appointment/"

    /** The tag an event carries for its Appointment (`CUSTOM_APP_URI`), so the app finds it when it no longer remembers writing it. */
    fun tag(appointmentId: String): String = TAG_PREFIX + appointmentId

    /** The Appointment a tag names; null for an event that is not the app's. */
    fun appointmentOfTag(tag: String?): String? = tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)?.takeIf { it.isNotEmpty() }

    /**
     * Whether an Appointment is mirrored: it starts at or after [connectedAt] and is not canceled.
     * Completed and no-show ones keep their event, since what the event says does not include the
     * status.
     */
    fun isMirrored(appointment: Appointment, connectedAt: Instant): Boolean {
        val start = runCatching { Instant.parse(appointment.startsAt) }.getOrNull() ?: return false

        return appointment.status != AppointmentStatus.CANCELED && !start.isBefore(connectedAt)
    }

    /** The events [appointments] should have, in the Stylist's [timezone]. */
    fun events(appointments: List<Appointment>, connectedAt: Instant, timezone: String, words: MirrorWords): List<MirrorEvent> =
        appointments.filter { isMirrored(it, connectedAt) }.mapNotNull { event(it, timezone, words) }

    /** The event of one Appointment; null for one whose start time cannot be read. */
    fun event(appointment: Appointment, timezone: String, words: MirrorWords): MirrorEvent? {
        val start = runCatching { Instant.parse(appointment.startsAt) }.getOrNull() ?: return null
        val end = appointment.endsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.takeIf { it.isAfter(start) } ?: start.plus(DEFAULT_LENGTH)

        return MirrorEvent(
            appointmentId = appointment.id,
            title = title(appointment),
            description = description(appointment, words),
            location = appointment.locationAddress.trim(),
            startMillis = start.toEpochMilli(),
            endMillis = end.toEpochMilli(),
            timezone = timezone,
        )
    }

    /** The primary Client's name, and how many other Clients a group Appointment has: "Наташа +2". */
    fun title(appointment: Appointment): String {
        val others = otherClients(appointment).size

        return if (others == 0) appointment.primaryClientName else "${appointment.primaryClientName} +$others"
    }

    /**
     * The Services by name, every Client of a group Appointment, the primary Client's phone, and
     * where the event comes from, one to a line. Never Color Formulas, notes, prices, tips or photos.
     */
    fun description(appointment: Appointment, words: MirrorWords): String {
        val services = serviceNames(appointment)
        val others = otherClients(appointment)
        val phone = appointment.participants.firstOrNull { it.clientId == appointment.primaryClientId }?.phone?.trim().orEmpty()

        return listOfNotNull(
            services.takeIf { it.isNotEmpty() }?.let(words::services),
            others.takeIf { it.isNotEmpty() }?.let { words.clients((listOf(appointment.primaryClientName) + it).joinToString(", ")) },
            phone.takeIf { it.isNotEmpty() }?.let { words.phone(PhoneFormat.display(it)) },
            words.footer,
        ).joinToString("\n")
    }

    /** The Clients of the Appointment other than the primary one, in the order they were added. */
    private fun otherClients(appointment: Appointment): List<String> =
        appointment.participants.filter { it.clientId != appointment.primaryClientId }.map { it.name }

    /** The names of the Services in the order they were added, one name once with how many there are: "Стрижка ×2, Укладка". */
    private fun serviceNames(appointment: Appointment): String =
        appointment.services
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .entries
            .joinToString(", ") { (name, count) -> if (count == 1) name else "$name ×$count" }

    /**
     * What to do to the calendar so that it has [desired] and nothing else of the app's.
     *
     * The app's events are those it remembers writing ([written]) and those that carry its tag.
     * An Appointment's event is the one the app remembers, while the calendar still has it, else
     * one tagged with it. It is changed when what it should say is not what the app wrote, also
     * when it was changed in Google Calendar since, which the app does not see; written again when
     * the calendar no longer has it; and every other event of the app's, a second one tagged with
     * the same Appointment too, is deleted. Events that are not the app's are never touched.
     */
    fun plan(desired: List<MirrorEvent>, written: Map<String, WrittenEvent>, rows: List<CalendarEventRow>): List<MirrorStep> {
        val present = rows.map { it.eventId }.toSet()
        val tagged = rows.filter { it.appointmentId != null }.groupBy({ it.appointmentId!! }, { it.eventId })
        val claimed = mutableSetOf<Long>()
        val steps = mutableListOf<MirrorStep>()

        for (event in desired) {
            val remembered = written[event.appointmentId]?.takeIf { it.eventId in present }
            val current = remembered?.eventId ?: tagged[event.appointmentId]?.firstOrNull()

            when {
                current == null -> steps += MirrorStep.Insert(event)
                remembered != null && remembered.fingerprint == event.fingerprint -> steps += MirrorStep.Keep(event.appointmentId, remembered)
                else -> steps += MirrorStep.Update(current, event)
            }
            current?.let(claimed::add)
        }

        val ours = written.values.map { it.eventId }.toSet() + tagged.values.flatten()

        rows.filter { it.eventId in ours && it.eventId !in claimed }.forEach { steps += MirrorStep.Delete(it.eventId) }

        return steps
    }
}
