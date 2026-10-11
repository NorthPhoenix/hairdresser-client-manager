package com.northphoenix.hairdresserclientmanager

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

    @Test
    fun `the form of a Service opens with values that a save takes as they are`() {
        // The price of a Service is shown to the form through centsToPrice: saving without a change keeps it to the cent.
        for (cents in listOf(0L, 5L, 1_000L, 12_050L, Money.MAX_CENTS)) {
            assertEquals(cents, ServiceEdit.values("Cut", Money.centsToPrice(cents), "")?.priceCents)
        }
    }
}
