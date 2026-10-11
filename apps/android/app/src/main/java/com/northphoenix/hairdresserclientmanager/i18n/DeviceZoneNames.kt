package com.northphoenix.hairdresserclientmanager.i18n

import android.icu.text.TimeZoneNames
import android.icu.util.TimeZone
import android.icu.util.ULocale
import com.northphoenix.hairdresserclientmanager.domain.ZoneNames
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Timezones as Android knows them (ICU, which has the city and the name of each in every
 * language). The id it lists a timezone under is the one its names are kept by, which for some is
 * an earlier name ("Asia/Calcutta" for Kolkata). The Stylist never reads an id, and the server and
 * `java.time` take every name a timezone has.
 */
object DeviceZoneNames : ZoneNames {
    private val names = ConcurrentHashMap<Locale, TimeZoneNames>()

    override fun listed(): Collection<String> {
        // Only what java.time knows too: every time the app shows is worked out with it.
        val usable = ZoneId.getAvailableZoneIds()

        return TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null).filter { it in usable }
    }

    override fun all(): Collection<String> = ZoneId.getAvailableZoneIds()

    override fun listedId(id: String): String = TimeZone.getCanonicalID(id) ?: id

    override fun city(id: String, locale: Locale): String? =
        names(locale).getExemplarLocationName(id)?.takeIf(String::isNotBlank)

    override fun name(id: String, locale: Locale, at: Instant): String? {
        val names = names(locale)
        val date = at.toEpochMilli()
        val summerTime = TimeZone.getFrozenTimeZone(id).inDaylightTime(Date(date))
        val now = if (summerTime) TimeZoneNames.NameType.LONG_DAYLIGHT else TimeZoneNames.NameType.LONG_STANDARD

        return (names.getDisplayName(id, now, date) ?: names.getDisplayName(id, TimeZoneNames.NameType.LONG_GENERIC, date))
            ?.takeIf(String::isNotBlank)
    }

    private fun names(locale: Locale): TimeZoneNames =
        names.getOrPut(locale) { TimeZoneNames.getInstance(ULocale.forLocale(locale)) }
}
