package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.Appointment
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The days of [month] in rows of weeks that start on [firstDayOfWeek]. A cell is null where the
 * week reaches into another month.
 */
fun weeksOf(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate?>> {
    val before = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells = List<LocalDate?>(before) { null } + (1..month.lengthOfMonth()).map(month::atDay)

    return cells.chunked(7) { week -> week + List(7 - week.size) { null } }
}

/**
 * The timezone [timezone] names, or null when this device knows none by that name. The server
 * checks a timezone when it is saved, but a Stylist who was saved before it did can hold any text,
 * and a device can know fewer timezones than the server.
 */
fun zoneOrNull(timezone: String?): ZoneId? =
    try {
        timezone?.trim()?.takeIf(String::isNotEmpty)?.let(ZoneId::of)
    } catch (_: Exception) {
        null
    }

/**
 * Times have to be shown in some timezone: without one of the Stylist's that the device can use
 * ([zoneOrNull]) it is the device's own. Whoever shows the Stylist's timezone says so then.
 */
fun zoneOrSystem(timezone: String?): ZoneId = zoneOrNull(timezone) ?: ZoneId.systemDefault()

/**
 * All Appointment times are shown in the Stylist's configured timezone.
 *
 * [thisYear] is the year of the Stylist's today: a day of another year is shown with its year.
 * Without it the short forms never show a year. [uses24HourClock] is the device's 12/24-hour
 * setting, which times and the time picker follow; without it the clock is the one of [locale].
 */
class TimeFormat(val locale: Locale, val zone: ZoneId, private val thisYear: Int? = null, uses24HourClock: Boolean? = null) {
    // "8 октября" in Russian, "October 8" in English.
    private val dayFirst = locale.language == "ru"
    private val localeUses12HourClock =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale).contains('a')

    /** Twelve-hour clock with AM/PM, as opposed to a 24-hour clock. */
    val uses12HourClock: Boolean = uses24HourClock?.not() ?: localeUses12HourClock

    // The locale's own way of writing a time when that is the clock in use, otherwise the usual way of the other clock.
    private val time = when {
        uses12HourClock == localeUses12HourClock -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        uses12HourClock -> DateTimeFormatter.ofPattern("h:mm a", locale)
        else -> DateTimeFormatter.ofPattern("HH:mm", locale)
    }
    private val mediumDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val fullDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    private val dayAndMonth = DateTimeFormatter.ofPattern(if (dayFirst) "d MMMM" else "MMMM d", locale)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    private val shortWeekday = DateTimeFormatter.ofPattern("EEE", locale)
    private val monthAndYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    private val shortDate = DateTimeFormatter.ofPattern(if (dayFirst) "d MMM" else "MMM d", locale)
    private val shortDateWithYear = DateTimeFormatter.ofPattern(if (dayFirst) "d MMM yyyy" else "MMM d, yyyy", locale)

    /** Monday in Russian, Sunday in US English. */
    val firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    fun zoned(iso: String): ZonedDateTime = Instant.parse(iso).atZone(zone)

    fun time(iso: String): String = time.format(zoned(iso))

    fun time(value: LocalTime): String = time.format(value)

    /** When a reminder went: "19:30" on [now]'s day, "Oct 20, 19:30" on another day. */
    fun stamp(at: Instant, now: Instant): String {
        val moment = at.atZone(zone)

        return if (moment.toLocalDate() == now.atZone(zone).toLocalDate()) time.format(moment) else shortDate(moment.toLocalDate()) + ", " + time.format(moment)
    }

    fun mediumDate(date: LocalDate): String = mediumDate.format(date)

    /** "Oct 8", and "Oct 8, 2025" for a day of another year. */
    fun shortDate(date: LocalDate): String = (if (isThisYear(date)) shortDate else shortDateWithYear).format(date)

    /** "Thursday, October 8, 2026" */
    fun fullDate(date: LocalDate): String = capitalize(fullDate.format(date))

    /** "Thursday, October 8", and in full ("Wednesday, October 8, 2025") for a day of another year. */
    fun longDay(date: LocalDate): String =
        if (isThisYear(date)) capitalize(weekday.format(date)) + ", " + dayAndMonth.format(date) else fullDate(date)

    /**
     * A day as the one-line title of a screen: "Thursday, October 8", and "Wed, Oct 8, 2025" for a
     * day of another year. [longDay] of such a day is longer than the line, and the year at its end
     * is the first thing the ellipsis cuts.
     */
    fun dayTitle(date: LocalDate): String =
        if (isThisYear(date)) longDay(date) else shortWeekday(date) + ", " + shortDateWithYear.format(date)

    fun shortWeekday(date: LocalDate): String = capitalize(shortWeekday.format(date)).trimEnd('.')

    fun monthAndYear(date: LocalDate): String = capitalize(monthAndYear.format(date))

    /** "14:00 – 15:30" or just "14:00" when the Appointment has no end time. */
    fun timeRange(appointment: Appointment): String =
        listOfNotNull(time(appointment.startsAt), appointment.endsAt?.let(::time)).joinToString(" – ")

    /**
     * The time range of an Appointment named next to another one that is on [day]: with its own
     * date in front ("Oct 8, 11:30 PM – 12:30 AM") when it starts on another day.
     */
    fun timeRangeSeenFrom(day: LocalDate, appointment: Appointment): String {
        val startDay = zoned(appointment.startsAt).toLocalDate()

        return if (startDay == day) timeRange(appointment) else shortDate(startDay) + ", " + timeRange(appointment)
    }

    /** "Oct 8, 2026, 2:00 PM – 3:30 PM", used where the date is not otherwise visible. */
    fun dateAndTimeRange(appointment: Appointment): String =
        mediumDate(zoned(appointment.startsAt).toLocalDate()) + ", " + timeRange(appointment)

    /** Day boundaries as instants, for the `start`/`end` range inputs. */
    fun dayRange(date: LocalDate): Pair<Instant, Instant> =
        date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

    /**
     * Null when the clock never shows [time] on [date] in the Stylist's timezone: the night the
     * clocks go forward skips an hour. java.time would move such a time an hour later without a word.
     */
    fun toInstant(date: LocalDate, time: LocalTime): Instant? {
        val local = LocalDateTime.of(date, time)

        return if (zone.rules.getValidOffsets(local).isEmpty()) null else local.atZone(zone).toInstant()
    }

    private fun isThisYear(date: LocalDate): Boolean = thisYear == null || date.year == thisYear

    private fun capitalize(text: String): String = text.replaceFirstChar { it.titlecase(locale) }
}
