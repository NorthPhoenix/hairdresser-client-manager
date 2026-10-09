package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

fun zoneOrSystem(timezone: String?): ZoneId =
    try {
        if (timezone.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(timezone.trim())
    } catch (_: Exception) {
        ZoneId.systemDefault()
    }

/** All Appointment times are shown in the Stylist's configured timezone. */
class TimeFormat(val locale: Locale, val zone: ZoneId) {
    // "8 октября" in Russian, "October 8" in English.
    private val dayFirst = locale.language == "ru"
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val mediumDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val dayAndMonth = DateTimeFormatter.ofPattern(if (dayFirst) "d MMMM" else "MMMM d", locale)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    private val shortWeekday = DateTimeFormatter.ofPattern("EEE", locale)
    private val monthAndYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    private val shortDate = DateTimeFormatter.ofPattern(if (dayFirst) "d MMM" else "MMM d", locale)

    /** Twelve-hour clock with AM/PM, as opposed to a 24-hour clock. */
    val uses12HourClock: Boolean =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale).contains('a')

    fun today(): LocalDate = LocalDate.now(zone)

    fun zoned(iso: String): ZonedDateTime = Instant.parse(iso).atZone(zone)

    fun time(iso: String): String = time.format(zoned(iso))

    fun time(value: LocalTime): String = time.format(value)

    fun mediumDate(date: LocalDate): String = mediumDate.format(date)

    fun shortDate(date: LocalDate): String = shortDate.format(date)

    /** "Thursday, October 8" */
    fun longDay(date: LocalDate): String = capitalize(weekday.format(date)) + ", " + dayAndMonth.format(date)

    fun shortWeekday(date: LocalDate): String = capitalize(shortWeekday.format(date)).trimEnd('.')

    fun monthAndYear(date: LocalDate): String = capitalize(monthAndYear.format(date))

    /** "14:00 – 15:30" or just "14:00" when the Appointment has no end time. */
    fun timeRange(appointment: Appointment): String =
        listOfNotNull(time(appointment.startsAt), appointment.endsAt?.let(::time)).joinToString(" – ")

    /** "Oct 8, 2026, 2:00 PM – 3:30 PM", used where the date is not otherwise visible. */
    fun dateAndTimeRange(appointment: Appointment): String =
        mediumDate(zoned(appointment.startsAt).toLocalDate()) + ", " + timeRange(appointment)

    /** Day boundaries as instants, for the `start`/`end` range inputs. */
    fun dayRange(date: LocalDate): Pair<Instant, Instant> =
        date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

    fun toInstant(date: LocalDate, time: LocalTime): Instant = LocalDateTime.of(date, time).atZone(zone).toInstant()

    private fun capitalize(text: String): String = text.replaceFirstChar { it.titlecase(locale) }
}
