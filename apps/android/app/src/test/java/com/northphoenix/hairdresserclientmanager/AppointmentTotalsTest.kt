package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentTotals
import com.northphoenix.hairdresserclientmanager.domain.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppointmentTotalsTest {
    private fun service(id: String, priceCents: Long, clientId: String = "c1") = AppointmentService(id = id, clientId = clientId, name = id, priceCents = priceCents)

    /** As the server sends it: the final total is the custom total when there is one, the sum otherwise. */
    private fun appointment(prices: List<Long> = listOf(5000, 3500), override: Long? = null, tip: Long? = null): Appointment {
        val services = prices.mapIndexed { index, price -> service("s$index", price) }
        val sum = services.sumOf { it.priceCents }

        return Appointment(
            id = "a1",
            primaryClientId = "c1",
            primaryClientName = "Света",
            services = services,
            serviceTotalCents = sum,
            finalTotalCents = override ?: sum,
            finalTotalCentsOverride = override,
            tipCents = tip,
            startsAt = "2026-10-16T23:00:00Z",
            status = AppointmentStatus.COMPLETED,
            locationType = LocationType.IN_SALON,
        )
    }

    @Test
    fun `the total is the price of the Services and the tip`() {
        assertEquals(8500, AppointmentTotals.totalCents(appointment()))
        assertEquals(10000, AppointmentTotals.totalCents(appointment(tip = 1500)))
        // A custom total is the price of the Services, and the tip comes on top of it.
        assertEquals(8000, AppointmentTotals.servicesPriceCents(appointment(override = 8000, tip = 1000)))
        assertEquals(9000, AppointmentTotals.totalCents(appointment(override = 8000, tip = 1000)))
    }

    @Test
    fun `a tip without Services is the whole amount`() {
        val tipOnly = appointment(prices = emptyList(), tip = 2000)

        assertEquals(2000, AppointmentTotals.totalCents(tipOnly))
        assertTrue(AppointmentTotals.hasAmount(tipOnly))
        assertFalse(AppointmentTotals.hasAmount(appointment(prices = emptyList())))
        assertTrue(AppointmentTotals.hasAmount(appointment(prices = listOf(0))))
    }

    @Test
    fun `the largest custom total with the largest tip is still counted right`() {
        val most = appointment(prices = listOf(Money.MAX_CENTS), override = Money.MAX_CENTS, tip = Money.MAX_CENTS)

        assertEquals(1_999_999_998L, AppointmentTotals.totalCents(most))
    }

    @Test
    fun `a group Appointment has one tip for the whole visit`() {
        val group = appointment().let { it.copy(services = it.services + service("s9", 2500, clientId = "c2"), serviceTotalCents = 11000, finalTotalCents = 11000, tipCents = 2000) }

        assertEquals(13000, AppointmentTotals.totalCents(group))
    }

    @Test
    fun `no tip is null, also when it is typed as zero`() {
        assertNull(AppointmentTotals.withTip(appointment(tip = 1500), 0).tipCents)
        assertNull(AppointmentTotals.withTip(appointment(tip = 1500), null).tipCents)
        assertEquals(1200L, AppointmentTotals.withTip(appointment(), 1200).tipCents)
        assertEquals(0, AppointmentTotals.tipCents(appointment()))
    }

    @Test
    fun `the chips are $5 to $20, and any other tip is one of her own`() {
        assertEquals(listOf(500L, 1000L, 1500L, 2000L), AppointmentTotals.TIP_CHOICES)
        assertTrue(AppointmentTotals.isTipChoice(1500))
        assertFalse(AppointmentTotals.isTipChoice(1200))
        assertFalse(AppointmentTotals.isTipChoice(null))
    }

    @Test
    fun `a custom total above the Services is offered as a tip`() {
        // Her first completed Appointments: +$15 on $85, +$10 on $65.
        assertEquals(1500L, AppointmentTotals.tipSuggestionCents(appointment(override = 10000)))
        assertEquals(1000L, AppointmentTotals.tipSuggestionCents(appointment(prices = listOf(6500), override = 7500)))
    }

    @Test
    fun `nothing is offered for a custom total at or below the Services, without one, or once a tip is recorded`() {
        assertNull(AppointmentTotals.tipSuggestionCents(appointment()))
        assertNull(AppointmentTotals.tipSuggestionCents(appointment(override = 8500)))
        assertNull(AppointmentTotals.tipSuggestionCents(appointment(override = 7000)))
        assertNull(AppointmentTotals.tipSuggestionCents(appointment(override = 10000, tip = 500)))
    }

    @Test
    fun `nothing is offered when there are no Services for the custom total to be above`() {
        // Then the custom total is the whole price of what she did, not a tip on top of it.
        assertNull(AppointmentTotals.tipSuggestionCents(appointment(prices = emptyList(), override = 6000)))
        assertNull(AppointmentTotals.tipSuggestionCents(appointment(prices = listOf(0), override = 6000)))
    }

    @Test
    fun `yes moves the difference into the tip and keeps the total`() {
        val before = appointment(override = 10000)
        val after = AppointmentTotals.withSuggestedTip(before)

        assertNull(after.finalTotalCentsOverride)
        assertEquals(8500, after.finalTotalCents)
        assertEquals(1500L, after.tipCents)
        assertEquals(AppointmentTotals.totalCents(before), AppointmentTotals.totalCents(after))
        assertNull(AppointmentTotals.tipSuggestionCents(after))
    }

    @Test
    fun `yes changes nothing when there is nothing to offer`() {
        val below = appointment(override = 7000)

        assertSame(below, AppointmentTotals.withSuggestedTip(below))
    }
}
