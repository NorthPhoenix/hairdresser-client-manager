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

    /** The receipt's lines above the total: this Client's Services, the other Clients', the discount or surcharge, the Tip. */
    private fun lines(entry: ClientHistoryEntry): Long =
        entry.services.sumOf { it.priceCents } +
            (HistoryTotals.othersCents(entry) ?: 0L) +
            (HistoryTotals.customPriceDifferenceCents(entry) ?: 0L) +
            (HistoryTotals.tipCents(entry) ?: 0L)

    @Test
    fun `the total is the Services and the Tip`() {
        assertEquals(20500L, HistoryTotals.totalCents(entry()))
        assertEquals(22500L, HistoryTotals.totalCents(entry(tip = 2000)))
    }

    @Test
    fun `a custom total replaces the sum, and the Tip is added to it`() {
        val discounted = entry(override = 19000, tip = 2000)

        assertEquals(19000L, HistoryTotals.servicesPriceCents(discounted))
        assertEquals(21000L, HistoryTotals.totalCents(discounted))
        assertEquals(-1500L, HistoryTotals.customPriceDifferenceCents(discounted))
    }

    @Test
    fun `a custom total above the sum is a surcharge`() {
        assertEquals(1000L, HistoryTotals.customPriceDifferenceCents(entry(override = 21500)))
    }

    @Test
    fun `a free visit with a Tip comes to the Tip`() {
        val free = entry(override = 0, tip = 1000)

        assertEquals(-20500L, HistoryTotals.customPriceDifferenceCents(free))
        assertEquals(1000L, HistoryTotals.totalCents(free))
        assertTrue(HistoryTotals.showsTotal(free))
    }

    @Test
    fun `no discount line without a custom total, with one equal to the sum, or with no Services to compare it with`() {
        assertNull(HistoryTotals.customPriceDifferenceCents(entry()))
        assertNull(HistoryTotals.customPriceDifferenceCents(entry(override = 20500)))
        assertNull(HistoryTotals.customPriceDifferenceCents(entry(prices = emptyList(), override = 5000)))
        assertEquals(5000L, HistoryTotals.totalCents(entry(prices = emptyList(), override = 5000)))
    }

    @Test
    fun `the receipt adds up to the total`() {
        val cases = listOf(
            entry(),
            entry(tip = 2000),
            entry(override = 19000, tip = 2000),
            entry(override = 21500),
            entry(override = 0, tip = 1000),
            entry(prices = listOf(22050), serviceTotal = 26550, override = 25000, tip = 2000, clients = listOf("c1", "c2")),
            entry(prices = listOf(22050), serviceTotal = 26550, clients = listOf("c1", "c2")),
        )

        cases.forEach { assertEquals(HistoryTotals.totalCents(it), lines(it)) }
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

        assertTrue(HistoryTotals.showsTotal(onlyOthers))
        assertEquals(4500L, HistoryTotals.totalCents(onlyOthers))
        assertEquals(4500L, HistoryTotals.othersCents(onlyOthers))
    }

    @Test
    fun `from an older server a group visit's whole sum is unknown, so neither line is guessed`() {
        val group = entry(prices = listOf(22050), serviceTotal = null, override = 25000, clients = listOf("c1", "c2"))

        assertNull(HistoryTotals.othersCents(group))
        assertNull(HistoryTotals.customPriceDifferenceCents(group))
        assertEquals(20500L, HistoryTotals.totalCents(entry(serviceTotal = null)))
    }

    @Test
    fun `a Tip alone has a total, Services all at no price with nothing set have none`() {
        assertTrue(HistoryTotals.showsTotal(entry(prices = emptyList(), tip = 2000)))
        assertEquals(2000L, HistoryTotals.totalCents(entry(prices = emptyList(), tip = 2000)))
        assertFalse(HistoryTotals.showsTotal(entry(prices = listOf(0, 0))))
        assertFalse(HistoryTotals.showsTotal(entry(prices = emptyList())))
    }

    @Test
    fun `a canceled or no-show visit was not charged and has no total`() {
        assertTrue(HistoryTotals.showsTotal(entry(status = AppointmentStatus.SCHEDULED)))
        assertFalse(HistoryTotals.showsTotal(entry(status = AppointmentStatus.CANCELED)))
        assertFalse(HistoryTotals.showsTotal(entry(status = AppointmentStatus.NO_SHOW)))
        assertFalse(HistoryTotals.wasCharged(entry(status = AppointmentStatus.NO_SHOW)))
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
