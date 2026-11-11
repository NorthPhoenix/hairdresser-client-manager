package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.domain.HistoryTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTotalsTest {
    private fun entry(
        prices: List<Long> = listOf(12000, 8500),
        serviceTotal: Long? = prices.sum(),
        override: Long? = null,
        tip: Long? = null,
        clients: List<String> = listOf("c1"),
        status: AppointmentStatus = AppointmentStatus.COMPLETED,
    ) = ClientHistoryEntry(
        id = "a1",
        primaryClientName = "Анна",
        startsAt = "2026-11-04T17:00:00.000Z",
        status = status,
        participantClientIds = clients,
        serviceTotalCents = serviceTotal,
        finalTotalCentsOverride = override,
        tipCents = tip,
        services = prices.mapIndexed { index, price -> AppointmentService(id = "s$index", clientId = "c1", name = "s$index", priceCents = price) },
    )

    /** The column of the row: this Client's Services, the other Clients' line and the custom price's difference. */
    private fun column(entry: ClientHistoryEntry): Long =
        entry.services.sumOf { it.priceCents } + (HistoryTotals.othersCents(entry) ?: 0L) + (HistoryTotals.customPriceDifferenceCents(entry) ?: 0L)

    @Test
    fun `the visit comes to the sum of its Services and the Tip`() {
        assertEquals(20500L, HistoryTotals.totalCents(entry()))
        assertEquals(22500L, HistoryTotals.totalCents(entry(tip = 2000)))
    }

    @Test
    fun `a custom total replaces the sum, and the Tip is on top of it`() {
        val discounted = entry(override = 19000, tip = 2000)

        assertEquals(19000L, HistoryTotals.servicesPriceCents(discounted))
        assertEquals(21000L, HistoryTotals.totalCents(discounted))
        assertEquals(-1500L, HistoryTotals.customPriceDifferenceCents(discounted))
    }

    @Test
    fun `a custom total above the sum adds to it`() {
        assertEquals(1000L, HistoryTotals.customPriceDifferenceCents(entry(override = 21500)))
    }

    @Test
    fun `a free visit takes the whole sum off and still leads with its price`() {
        val free = entry(override = 0)

        assertEquals(0L, HistoryTotals.servicesPriceCents(free))
        assertTrue(HistoryTotals.showsPrice(free))
        assertEquals(-20500L, HistoryTotals.customPriceDifferenceCents(free))
    }

    @Test
    fun `no difference without a custom total, or with one equal to the sum`() {
        assertNull(HistoryTotals.customPriceDifferenceCents(entry()))
        assertNull(HistoryTotals.customPriceDifferenceCents(entry(override = 20500)))
    }

    @Test
    fun `the column adds up to the price of the Services, also of a group visit`() {
        val single = entry(override = 19000)
        val group = entry(prices = listOf(22050), serviceTotal = 26550, override = 25000, clients = listOf("c1", "c2"))

        assertEquals(HistoryTotals.servicesPriceCents(single), column(single))
        assertEquals(HistoryTotals.servicesPriceCents(group), column(group))
    }

    @Test
    fun `a group visit comes to the whole visit, with the other Clients' Services as one line`() {
        val group = entry(prices = listOf(22050), serviceTotal = 26550, clients = listOf("c1", "c2", "c3"))

        assertTrue(HistoryTotals.isWholeVisit(group))
        assertEquals(26550L, HistoryTotals.totalCents(group))
        assertEquals(4500L, HistoryTotals.othersCents(group))
        assertEquals(2, HistoryTotals.otherClients(group))
        assertFalse(HistoryTotals.isWholeVisit(entry()))
        assertNull(HistoryTotals.othersCents(entry()))
    }

    @Test
    fun `other Clients with nothing to pay have no line`() {
        assertNull(HistoryTotals.othersCents(entry(prices = listOf(22050), serviceTotal = 22050, clients = listOf("c1", "c2"))))
    }

    @Test
    fun `a Client of a group visit with no Services of their own still sees what it came to`() {
        val onlyOthers = entry(prices = emptyList(), serviceTotal = 4500, clients = listOf("c1", "c2"))

        assertTrue(HistoryTotals.showsPrice(onlyOthers))
        assertEquals(4500L, HistoryTotals.totalCents(onlyOthers))
    }

    @Test
    fun `from an older server a group visit's whole sum is unknown, so neither line is guessed`() {
        val group = entry(prices = listOf(22050), serviceTotal = null, override = 25000, clients = listOf("c1", "c2"))

        assertNull(HistoryTotals.othersCents(group))
        assertNull(HistoryTotals.customPriceDifferenceCents(group))
        assertEquals(20500L, HistoryTotals.totalCents(entry(serviceTotal = null)))
    }

    @Test
    fun `Services all at no price and a Tip alone have no price at the top`() {
        assertFalse(HistoryTotals.showsPrice(entry(prices = listOf(0, 0))))
        assertFalse(HistoryTotals.showsPrice(entry(prices = emptyList(), tip = 2000)))
        assertTrue(HistoryTotals.showsPrice(entry(prices = listOf(0), override = 3000)))
    }

    @Test
    fun `only Services with a price made free are free`() {
        assertTrue(HistoryTotals.isFree(entry(override = 0)))
        assertFalse(HistoryTotals.isFree(entry(override = 19000)))
        assertFalse(HistoryTotals.isFree(entry()))
        assertFalse(HistoryTotals.isFree(entry(prices = emptyList(), override = 0)))
    }

    @Test
    fun `a canceled or no-show visit, or one with nothing to pay, has no amount at the top`() {
        assertTrue(HistoryTotals.showsPrice(entry()))
        assertTrue(HistoryTotals.showsPrice(entry(status = AppointmentStatus.SCHEDULED)))
        assertFalse(HistoryTotals.showsPrice(entry(status = AppointmentStatus.CANCELED)))
        assertFalse(HistoryTotals.showsPrice(entry(status = AppointmentStatus.NO_SHOW)))
        assertFalse(HistoryTotals.showsPrice(entry(prices = emptyList())))
    }

    @Test
    fun `the lines of a row add up to the amount at the top, Tip included`() {
        val discounted = entry(override = 19000, tip = 2000)
        val group = entry(prices = listOf(22050), serviceTotal = 26550, override = 25000, tip = 1000, clients = listOf("c1", "c2"))

        assertEquals(HistoryTotals.totalCents(discounted), column(discounted) + 2000)
        assertEquals(HistoryTotals.totalCents(group), column(group) + 1000)
    }

    @Test
    fun `a custom price with no Services to compare it with has no difference`() {
        assertNull(HistoryTotals.customPriceDifferenceCents(entry(prices = emptyList(), override = 5000)))
        assertEquals(5000L, HistoryTotals.servicesPriceCents(entry(prices = emptyList(), override = 5000)))
    }

    @Test
    fun `a lone Service at its own price is not repeated under the price at the top`() {
        assertTrue(HistoryTotals.servicePriceRepeatsPrice(entry(prices = listOf(8500))))
        // A Tip is on top of the price, so the price at the top is still the Service's own.
        assertTrue(HistoryTotals.servicePriceRepeatsPrice(entry(prices = listOf(8500), tip = 1000)))
        assertFalse(HistoryTotals.servicePriceRepeatsPrice(entry(prices = listOf(8500), override = 8000)))
        assertFalse(HistoryTotals.servicePriceRepeatsPrice(entry(prices = listOf(8500), serviceTotal = 13000, clients = listOf("c1", "c2"))))
        assertFalse(HistoryTotals.servicePriceRepeatsPrice(entry()))
        // Without a price at the top, the Service's is the only place it is said.
        assertFalse(HistoryTotals.servicePriceRepeatsPrice(entry(prices = listOf(8500), status = AppointmentStatus.CANCELED)))
    }

    @Test
    fun `the price is of the whole of a group visit only when the whole visit is known`() {
        val group = listOf("c1", "c2")

        assertTrue(HistoryTotals.priceIsWholeVisit(entry(prices = listOf(22050), serviceTotal = 26550, clients = group)))
        // An older server sends this Client's Services alone: their sum is not the whole visit's, a custom total is.
        assertFalse(HistoryTotals.priceIsWholeVisit(entry(prices = listOf(22050), serviceTotal = null, clients = group)))
        assertTrue(HistoryTotals.priceIsWholeVisit(entry(prices = listOf(22050), serviceTotal = null, override = 25000, clients = group)))
        assertFalse(HistoryTotals.priceIsWholeVisit(entry()))
    }

    @Test
    fun `a Tip of 0 is none`() {
        assertNull(HistoryTotals.tipCents(entry(tip = 0)))
        assertEquals(2000L, HistoryTotals.tipCents(entry(tip = 2000)))
        assertEquals(20500L, HistoryTotals.totalCents(entry(tip = 0)))
    }

    @Test
    fun `only a scheduled visit is still to come`() {
        assertTrue(HistoryTotals.isUpcoming(entry(status = AppointmentStatus.SCHEDULED)))
        assertFalse(HistoryTotals.isUpcoming(entry()))
        assertFalse(HistoryTotals.isUpcoming(entry(status = AppointmentStatus.NO_SHOW)))
    }
}
