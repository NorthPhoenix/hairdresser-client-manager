package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientVisitDates
import com.northphoenix.hairdresserclientmanager.data.hcmJson
import com.northphoenix.hairdresserclientmanager.domain.ClientRowVisits
import com.northphoenix.hairdresserclientmanager.domain.ClientRowVisits.Day
import com.northphoenix.hairdresserclientmanager.domain.ClientRowVisits.Line
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits.Span
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits.SpanUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The line of a Client's row in the Clients View: the next Appointment, else the last visit, else none. */
class ClientRowVisitsTest {
    private val chicago = ZoneId.of("America/Chicago")

    // Sunday, November 8, 2026, 18:00 in Chicago.
    private val now = Instant.parse("2026-11-09T00:00:00Z")

    private fun dates(last: String? = null, next: String? = null) = ClientVisitDates("anna", lastVisitAt = last, nextAppointmentAt = next)

    @Test
    fun anAppointmentAheadComesBeforeTheLastVisit() {
        val next = "2026-11-20T23:00:00Z"

        assertEquals(
            Line.Next(Day.LATER, LocalDate.of(2026, 11, 20), next),
            ClientRowVisits.line(dates(last = "2026-11-03T20:00:00Z", next = next), now, chicago),
        )
    }

    @Test
    fun theDayOfTheNextAppointmentIsTheStylistsDay() {
        // 23:30 in Chicago tonight is already Monday in UTC: still today.
        assertEquals(Day.TODAY, (ClientRowVisits.line(dates(next = "2026-11-09T05:30:00Z"), now, chicago) as Line.Next).day)
        // Monday 10:00 in Chicago.
        assertEquals(Day.TOMORROW, (ClientRowVisits.line(dates(next = "2026-11-09T16:00:00Z"), now, chicago) as Line.Next).day)
        // Tuesday 00:30 in Chicago.
        assertEquals(Day.LATER, (ClientRowVisits.line(dates(next = "2026-11-10T06:30:00Z"), now, chicago) as Line.Next).day)
    }

    @Test
    fun anAppointmentThatHasStartedSinceTheServerAnsweredIsNotAhead() {
        assertEquals(
            Line.Last(Span(SpanUnit.DAYS, 5)),
            ClientRowVisits.line(dates(last = "2026-11-03T20:00:00Z", next = "2026-11-08T23:00:00Z"), now, chicago),
        )
        assertEquals(Line.None, ClientRowVisits.line(dates(next = "2026-11-08T23:00:00Z"), now, chicago))
    }

    @Test
    fun theLastVisitIsSaidAsTheProfileSaysIt() {
        // 09:00 in Chicago this morning.
        assertEquals(Line.Last(Span(SpanUnit.DAYS, 0)), ClientRowVisits.line(dates(last = "2026-11-08T15:00:00Z"), now, chicago))
        // Saturday 20:00 in Chicago is Sunday 02:00 in UTC: yesterday for the Stylist.
        assertEquals(Line.Last(Span(SpanUnit.DAYS, 1)), ClientRowVisits.line(dates(last = "2026-11-08T02:00:00Z"), now, chicago))
        assertEquals(Line.Last(Span(SpanUnit.WEEKS, 3)), ClientRowVisits.line(dates(last = "2026-10-16T18:00:00Z"), now, chicago))
        assertEquals(Line.Last(Span(SpanUnit.MONTHS, 4)), ClientRowVisits.line(dates(last = "2026-07-01T18:00:00Z"), now, chicago))
    }

    @Test
    fun aClientTheServerDoesNotListHasHadNoVisit() {
        assertEquals(Line.None, ClientRowVisits.line(null, now, chicago))
        assertEquals(Line.None, ClientRowVisits.line(dates(), now, chicago))
    }

    @Test
    fun aDateThatCannotBeReadIsLeftOut() {
        assertEquals(Line.None, ClientRowVisits.line(dates(last = "soon", next = "later"), now, chicago))
    }

    @Test
    fun readsTheServersAnswerAndFindsEachClient() {
        val answer = """
            [
              {"clientId": "anna", "lastVisitAt": "2026-11-03T20:00:00.000Z", "nextAppointmentAt": null, "added": true},
              {"clientId": "olga", "lastVisitAt": null, "nextAppointmentAt": "2026-11-21T00:00:00.000Z"}
            ]
        """.trimIndent()
        val byClient = ClientRowVisits.byClient(hcmJson.decodeFromString<List<ClientVisitDates>>(answer))

        assertEquals(setOf("anna", "olga"), byClient.keys)
        assertEquals(Line.Last(Span(SpanUnit.DAYS, 5)), ClientRowVisits.line(byClient["anna"], now, chicago))
        assertEquals(Line.Next(Day.LATER, LocalDate.of(2026, 11, 20), "2026-11-21T00:00:00.000Z"), ClientRowVisits.line(byClient["olga"], now, chicago))
    }
}
