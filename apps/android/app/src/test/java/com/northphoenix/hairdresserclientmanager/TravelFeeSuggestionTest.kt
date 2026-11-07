package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.TravelFeeSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelFeeSuggestionTest {
    private val cutItem = ServiceMenuItem(id = "m1", name = "Стрижка", defaultPriceCents = 4_000)
    private val travelItem = ServiceMenuItem(id = "m2", name = "Выезд на дом к клиенту", defaultPriceCents = 2_000)
    private val menu = listOf(cutItem, travelItem)

    private val cut = AppointmentService(id = "sv1", clientId = "c1", menuItemId = "m1", name = "Стрижка", priceCents = 4_000)
    private val travel = AppointmentService(id = "sv2", clientId = "c1", menuItemId = "m2", name = "Выезд на дом к клиенту", priceCents = 2_000)

    private fun appointment(vararg services: AppointmentService, at: LocationType = LocationType.AT_HOME) = Appointment(
        id = "a1",
        primaryClientId = "c1",
        primaryClientName = "Анна",
        participants = listOf(AppointmentParticipant(clientId = "c1", name = "Анна", isPrimary = true)),
        services = services.toList(),
        startsAt = "2026-11-08T16:00:00.000Z",
        status = AppointmentStatus.SCHEDULED,
        locationType = at,
    )

    @Test
    fun `an at-home Appointment with a Service from a travel item asks about that item`() {
        assertEquals(travelItem, TravelFeeSuggestion.item(appointment(cut, travel), menu, refused = emptySet()))
    }

    @Test
    fun `nothing is asked in the salon, without such a Service, or while an item is the Travel Fee`() {
        assertNull(TravelFeeSuggestion.item(appointment(cut, travel, at = LocationType.IN_SALON), menu, emptySet()))
        assertNull(TravelFeeSuggestion.item(appointment(cut), menu, emptySet()))
        assertNull(TravelFeeSuggestion.item(appointment(), menu, emptySet()))

        // Another item already is the Travel Fee: the menu has one, and nothing is offered in its place.
        val marked = listOf(cutItem.copy(travelFee = true), travelItem)
        assertNull(TravelFeeSuggestion.item(appointment(cut, travel), marked, emptySet()))
    }

    @Test
    fun `an item she said no to is not asked about again`() {
        assertNull(TravelFeeSuggestion.item(appointment(cut, travel), menu, refused = setOf("m2")))
    }

    @Test
    fun `a Service whose item is no longer in the menu, or that has none, is not asked about`() {
        val custom = travel.copy(id = "sv3", menuItemId = null)

        assertNull(TravelFeeSuggestion.item(appointment(cut, travel), listOf(cutItem), emptySet()))
        assertNull(TravelFeeSuggestion.item(appointment(cut, custom), menu, emptySet()))
    }

    @Test
    fun `travel fees are told by whole words in Russian and English`() {
        listOf(
            "Выезд на дом к клиенту", "выезд", "Оплата выезда", "ВЫЕЗД (по городу)", "Дорога", "Транспорт",
            "Travel fee", "Trip charge", "Mileage", "Callout",
        ).forEach { assertTrue(it, TravelFeeSuggestion.looksLikeTravelFee(it)) }

        listOf(
            "Выездная стрижка", "Стрижка на дому", "Стрижка", "Strip", "Stripping", "Balayage", "",
        ).forEach { assertFalse(it, TravelFeeSuggestion.looksLikeTravelFee(it)) }
    }
}
