package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.ServiceEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M22: an edit of a Service on an Appointment saves the name, the price and the note of its form together. */
class ServiceEditTest {
    @Test
    fun `an edit sends the name as one line, the price in whole cents and the note`() {
        assertEquals(
            ServiceEdit.Values(name = "Fix Me Again", priceCents = 1_250, note = "new note"),
            ServiceEdit.values(name = "  Fix Me Again ", price = "12,50", note = " new note\n"),
        )
    }

    @Test
    fun `a note that was emptied is sent empty, which clears it`() {
        assertEquals(ServiceEdit.Values("Fix Me", 1_000, ""), ServiceEdit.values("Fix Me", "10.00", "   "))
    }

    @Test
    fun `a note keeps its line breaks`() {
        assertEquals("first line\nsecond line", ServiceEdit.values("Fix Me", "10", "first line\nsecond line")?.note)
    }

    @Test
    fun `a Service can be made free`() {
        assertFalse(ServiceEdit.isPriceRefused("0"))
        assertEquals(0L, ServiceEdit.values("Fix Me", "0", "")?.priceCents)
    }

    @Test
    fun `a price is refused as it is when a Service is added ad hoc, and nothing is saved`() {
        // Empty, not an amount, negative, and above the server's maximum.
        for (price in listOf("", "  ", "abc", "12.345.6", "-5", "10000000.00")) {
            assertTrue(price, ServiceEdit.isPriceRefused(price))
            assertNull(price, ServiceEdit.values("Fix Me", price, "note"))
        }
        assertFalse(ServiceEdit.isPriceRefused(Money.centsToPrice(Money.MAX_CENTS)))
        assertEquals(Money.MAX_CENTS, ServiceEdit.values("Fix Me", Money.centsToPrice(Money.MAX_CENTS), "")?.priceCents)
    }

    @Test
    fun `a name with nothing to read is refused as the server refuses it, and nothing is saved`() {
        for (name in listOf("", "   ", "​", "\t")) {
            assertTrue(ServiceEdit.isNameRefused(name))
            assertNull(ServiceEdit.values(name, "10", "note"))
        }
        assertFalse(ServiceEdit.isNameRefused("Cut"))
    }

    private val short = ServiceMenuItem(id = "item_short", name = "Покраска в один цвет короткие", defaultPriceCents = 5_000, group = "Покраска в один цвет")
    private val long = ServiceMenuItem(id = "item_long", name = "Покраска в один цвет длинные", defaultPriceCents = 7_050, group = "Покраска в один цвет")
    private val variants = listOf(short, long)

    // Started from the short variant, then given a name and a price by hand.
    private val service = AppointmentService(
        id = "s1",
        clientId = "c1",
        menuItemId = "item_short",
        name = "Покраска короткие + тон",
        priceCents = 5_500,
        note = "Без аммиака",
    )

    @Test
    fun `a variant picked in the form fills its name and default price, and the save switches the Service to it`() {
        val (name, price) = ServiceEdit.picked(long, service)

        assertEquals("Покраска в один цвет длинные" to "70.50", name to price)
        assertEquals(
            ServiceEdit.Values("Покраска в один цвет длинные", 7_050, "Без аммиака", menuItemId = "item_long"),
            ServiceEdit.values(name, price, service.note, switchTo = ServiceEdit.switchTo("item_long", service, variants)),
        )
    }

    @Test
    fun `picking the Service's own variant puts back its own name and price, not the menu's`() {
        // Picked again after another variant, to undo it: a price set by hand is not lost.
        assertEquals("Покраска короткие + тон" to "55.00", ServiceEdit.picked(short, service))
    }

    @Test
    fun `a save without another variant keeps the Service's own item`() {
        assertNull(ServiceEdit.switchTo("item_short", service, variants))
        assertNull(ServiceEdit.switchTo(null, service, variants))
        assertNull(ServiceEdit.values("Fix Me", "10", "")?.menuItemId)
    }

    @Test
    fun `a variant that left the menu while the form was open is not sent, so the form still saves`() {
        // Deleted on another device: the refusal would come back on every save.
        assertNull(ServiceEdit.switchTo("item_long", service, listOf(short)))
        assertNull(ServiceEdit.switchTo("item_long", service, emptyList()))
        assertEquals(5_500L, ServiceEdit.values("Покраска короткие + тон", "55", "", switchTo = ServiceEdit.switchTo("item_long", service, emptyList()))?.priceCents)
    }

    @Test
    fun `a picked variant is not saved while the name or the price is refused`() {
        assertNull(ServiceEdit.values("Fix Me", "", "", switchTo = "item_long"))
        assertNull(ServiceEdit.values(" ", "10", "", switchTo = "item_long"))
    }

    @Test
    fun `the form of a Service opens with values that a save takes as they are`() {
        // The price of a Service is shown to the form through centsToPrice: saving without a change keeps it to the cent.
        for (cents in listOf(0L, 5L, 1_000L, 12_050L, Money.MAX_CENTS)) {
            assertEquals(cents, ServiceEdit.values("Cut", Money.centsToPrice(cents), "")?.priceCents)
        }
    }
}
